package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.*;
import cn.iocoder.yudao.framework.etl.core.connection.EtlStagedOutputPort;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlBatchRunReqDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 持久化输出意图、独立目标端回执与质量验收；平台行锁内不执行外部 I/O。 */
@Service
@Slf4j
public class EtlTargetBatchService {

    private static final Set<String> SAFE_NODES = Set.of("read-db", "generate-records", "random-records", "column-map",
            "data-filter", "data-clean", "data-encrypt", "data-decrypt", "data-merge", "data-desensitize",
            "data-condition",
            "field-derive", "add-constant", "row-column", "sort-records", "field-split", "id-generator", "calculator",
            "value-map", "quality-check", "number-range", "split-to-rows", "flatten-row", "sort-merge",
            "multi-join", "cartesian-product", "boundary-bridge", "managed-db-output");

    @Resource
    private EtlTargetBatchMapper batches;

    @Resource
    private EtlQualityBatchMapper qualityBatches;

    @Resource
    private EtlRecordMapper records;

    @Resource
    private EtlRunCoordinator coordinator;

    @Resource
    private EtlTargetDatabaseAdapter database;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private QualityTaskApi qualityApi;

    @Resource
    private EtlArchiveService archives;

    /**
     * 判断是否存在受管输出条件。
     *
     * @param dag DAG
     * @return 判断结果
     */
    public boolean hasManagedOutput(EtlDag dag) {
        return dag.getNodes().stream().anyMatch(n -> "managed-db-output".equals(n.getToolCode()));
    }

    /**
     * 创建端口。
     *
     * @param record 运行记录
     * @param dag DAG
     * @param parameters 参数定义或取值
     * @return ETL已暂存输出端口
     */
    public EtlStagedOutputPort createPort(EtlRecordDO record, EtlDag dag, Map<String, String> parameters) {
        if (!hasManagedOutput(dag)) {
            return null;
        }
        if (record.getQualityTaskId() != null) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "受管输出使用节点批次检测任务，不能再绑定流程级全表检测任务");
        }
        var snapshot = JSONUtil.parseObj(record.getConfigJson());
        var frozen = snapshot.getJSONObject("targetPlans");
        if (frozen == null) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "运行缺少冻结的目标绑定，请重新发布");
        }
        Map<String, JSONObject> plans = new LinkedHashMap<>();
        for (var node : dag.getNodes()) {
            if ("managed-db-output".equals(node.getToolCode())) {
                var plan = frozen.getJSONObject(node.getNo());
                if (plan == null) {
                    throw exception(ETL_TARGET_INTEGRITY_FAILED);
                }
                var current = database.prepareConfig(plan, record.getExecutionUserId());
                if (!Objects.equals(plan.getStr("bindingHash"), current.getStr("bindingHash"))) {
                    throw exception(ETL_TARGET_CONFIG_INVALID, "目标数据源绑定已变化，请重新发布并复核目标");
                }
                plans.put(node.getNo(), plan);
            }
        }
        return (nodeNo, rows) -> stage(record, nodeNo, plans.get(nodeNo), rows);
    }

    /**
     * 校验ETL 目标批次输入及状态。
     *
     * @param dag DAG
     * @param actor 操作人编号
     * @param parameters 参数定义或取值
     * @return 处理后的键值映射
     */
    public Map<String, JSONObject> validate(EtlDag dag, Long actor, Map<String, String> parameters) {
        Map<String, JSONObject> plans = new LinkedHashMap<>();
        if (!hasManagedOutput(dag)) {
            return plans;
        }
        var variables = new EtlVariableContext(parameters);
        for (var node : dag.getNodes()) {
            if (!SAFE_NODES.contains(node.getToolCode()) || node.isFailGoOn()) {
                throw exception(ETL_TARGET_CONFIG_INVALID,
                        "受管批次流程只允许受限读源、确定性转换和受管输出，不能忽略节点失败");
            }
            var config = JSONUtil.parseObj(variables.resolveJson(Objects.requireNonNullElse(node.getNodeJson(), "{}")));
            if ("read-db".equals(node.getToolCode()) && config.getInt("ifSql", 0) != 0) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "受管批次暂只接受整表读取，禁止未经证明无副作用的自定义 SQL");
            }
            if (!"managed-db-output".equals(node.getToolCode())) {
                continue;
            }
            if (!Boolean.TRUE.equals(
                    qualityApi.validateForRun(config.getLong("qualityTaskId"), actor).getCheckedData())) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "批次检测任务尚未授权");
            }
            plans.put(node.getNo(), database.prepareConfig(config, actor));
        }
        return plans;
    }

    /**
     * 暂存ETL 目标批次。
     *
     * @param record 运行记录
     * @param nodeNo 节点序号
     * @param config 处理配置
     * @param rows 待处理的行数据
     */
    private void stage(EtlRecordDO record, String nodeNo, JSONObject config, List<Map<String, Object>> rows) {
        if (config == null) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "输出节点未经过预检");
        }
        var quality = qualityBatches.selectOne(new LambdaQueryWrapperX<EtlQualityBatchDO>()
                .eq(EtlQualityBatchDO::getRecordId, record.getId())
                .eq(EtlQualityBatchDO::getNodeNo, config.getStr("qualityNodeNo")));
        if (quality == null || quality.getInputCount() == null || quality.getInputCount() != rows.size()) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "目标输入与上游完整质检范围不一致");
        }
        String batchKey = DigestUtil.sha256Hex(record.getId() + ":" + nodeNo);
        var replay = JSONUtil.parseObj(record.getConfigJson()).getJSONObject("governanceReplay");
        EtlTargetBatchDO original = null;
        if (replay != null) {
            if (!replay.getBool("confirmedTargetReplacement", false)) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "回放必须确认目标批次替换");
            }
            var expected = replay.getJSONObject("targets").getJSONObject(nodeNo);
            if (expected == null) {
                throw exception(ETL_TARGET_INTEGRITY_FAILED);
            }
            original = require(expected.getLong("batchId"));
            if (!Objects.equals(original.getRecordId(),
                    expected.getLong("recordId", replay.getLong("recordId"))) || !"COMMITTED".equals(
                    original.getCommitStatus())
                    || !Objects.equals(original.getPayloadHash(), expected.getStr("payloadHash"))
                    || !JSONUtil.parseObj(original.getConfigJson()).equals(config)) {
                throw exception(ETL_TARGET_INTEGRITY_FAILED);
            }
        }
        String physicalKey = original == null ? batchKey : EtlTargetDatabaseAdapter.physicalKey(original);
        var source = database.request(config, physicalKey, rows, false);
        var output = database.request(config, physicalKey, rows, true);
        long passed = rows.stream().filter(r -> "PASS".equals(r.get(config.getStr("statusColumn")))).count();
        long warned = rows.stream().filter(r -> "WARN".equals(r.get(config.getStr("statusColumn")))).count();
        long rejected = rows.size() - passed - warned;
        if (!Objects.equals(quality.getPassCount(), passed) || !Objects.equals(quality.getWarnCount(), warned)
                || !Objects.equals(quality.getRejectCount(), rejected)) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        String identity = identityHash(config.getJSONArray("businessKeys").toList(String.class), rows);
        if (original != null && (!Objects.equals(original.getInputCount(), (long) rows.size()) || !Objects.equals(
                original.getIdentityHash(), identity))) {
            throw exception(ETL_TARGET_CONFIG_INVALID,
                    "修复后必须覆盖原批次完整业务键集合；新增、缺失或重复业务键均不能回放");
        }
        if (!identity.equals(quality.getIdentityHash()) || !config.getJSONArray("businessKeys").toList(String.class)
                .equals(JsonUtils.parseArray(quality.getIdentityFieldsJson(), String.class))) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        var batch = new EtlTargetBatchDO();
        batch.setRecordId(record.getId());
        batch.setNodeNo(nodeNo);
        batch.setExecutionUserId(record.getExecutionUserId());
        batch.setQualityBatchId(quality.getId());
        batch.setQualityTaskId(config.getLong("qualityTaskId"));
        batch.setBatchKey(batchKey);
        if (original != null) {
            batch.setPhysicalBatchKey(physicalKey);
            batch.setReplayOfBatchId(original.getId());
            batch.setReplayOfRecordId(original.getRecordId());
            batch.setWorkOrderId(replay.getLong("workOrderId"));
            batch.setWorkOrderSubmissionId(replay.getLong("submissionId"));
            batch.setExpectedPayloadHash(original.getPayloadHash());
            batch.setOperationKey(batchKey);
        }
        batch.setConfigJson(config.toString());
        batch.setPayloadJson(AtomicBatchCodec.encode(output));
        batch.setSourcePayloadJson(AtomicBatchCodec.encode(source));
        batch.setPayloadHash(output.payloadHash());
        batch.setInputCount((long) rows.size());
        batch.setOutputCount((long) output.rowCount());
        batch.setAdmittedCount(passed + (config.getBool("allowWarnings", true) ? warned : 0));
        batch.setRejectedCount(rejected);
        batch.setIdentityHash(identity);
        batch.setCommitStatus("STAGED");
        batch.setQualityStatus("PENDING");
        batch.setAttemptCount(0);
        owned(record, () -> {
            var existing = batches.selectOne(
                    new LambdaQueryWrapperX<EtlTargetBatchDO>().eq(EtlTargetBatchDO::getBatchKey, batchKey));
            if (existing != null) {
                if (!"STAGED".equals(existing.getCommitStatus()) || !existing.getPayloadHash()
                        .equals(batch.getPayloadHash())
                        || !existing.getConfigJson().equals(batch.getConfigJson()) || !existing.getIdentityHash()
                        .equals(identity)
                        || !Objects.equals(existing.getSourcePayloadJson(), batch.getSourcePayloadJson())
                        || !Objects.equals(existing.getInputCount(), batch.getInputCount()) || !Objects.equals(
                        existing.getOutputCount(), batch.getOutputCount())
                        || !Objects.equals(existing.getAdmittedCount(), batch.getAdmittedCount()) || !Objects.equals(
                        existing.getRejectedCount(), batch.getRejectedCount())) {
                    throw exception(ETL_TARGET_INTEGRITY_FAILED);
                }
            } else if (batches.insert(batch) != 1 || batch.getId() == null) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
        });
    }

    /** 仅在引擎确认所有节点线程成功退出后，才在同一本地事务内批准每个输出端。 */
    public boolean approve(EtlRecordDO record, EtlDag dag) {
        List<EtlTargetBatchDO> targets = listRaw(record.getId());
        long expected = dag.getNodes().stream().filter(n -> "managed-db-output".equals(n.getToolCode())).count();
        if (targets.size() != expected || targets.stream().anyMatch(b -> !"STAGED".equals(b.getCommitStatus()))) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        boolean blocked = targets.stream().anyMatch(b -> (b.getReplayOfBatchId() != null || "BATCH_BLOCK".equals(
                JSONUtil.parseObj(b.getConfigJson()).getStr("disposition")))
                && !Objects.equals(b.getInputCount(), b.getAdmittedCount()));
        owned(record, () -> {
            for (var batch : targets) {
                if (batches.update(null, Wrappers.lambdaUpdate(EtlTargetBatchDO.class)
                        .eq(EtlTargetBatchDO::getId, batch.getId()).eq(EtlTargetBatchDO::getCommitStatus, "STAGED")
                        .set(EtlTargetBatchDO::getCommitStatus, blocked ? "BLOCKED" : "READY")
                        .set(EtlTargetBatchDO::getQualityStatus, blocked ? "NOT_RUN" : "PENDING")) != 1) {
                    throw exception(ETL_TARGET_STATE_CHANGED);
                }
            }
        });
        return !blocked;
    }

    /**
     * 提交已批准。
     *
     * @param record 运行记录
     * @return 处理是否成功
     */
    public boolean commitApproved(EtlRecordDO record) {
        if (record == null || records.selectById(record.getId()) == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        var targets = listRaw(record.getId());
        if (targets.isEmpty()) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        for (var batch : targets) {
            process(batch.getId());
        }
        return listRaw(record.getId()).stream().allMatch(b -> "COMMITTED".equals(b.getCommitStatus()));
    }

    /**
     * 转发待处理。
     */
    public void relayPending() {
        for (var candidate : batches.selectPending(LocalDateTime.now())) {
            try {
                process(candidate.getId());
            } catch (RuntimeException failure) {
                log.warn("ETL 目标批次等待恢复 id={} errorType={}", candidate.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        for (var candidate : batches.selectRecoveryCandidates()) {
            try {
                coordinator.reconcileManagedCommit(candidate.getId());
            } catch (RuntimeException failure) {
                log.warn("ETL 提交回执汇总等待恢复 recordId={} errorType={}", candidate.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 处理ETL 目标批次。
     *
     * @param id 记录编号
     */
    private void process(Long id) {
        EtlTargetBatchDO batch = require(id);
        if ("COMMITTED".equals(batch.getCommitStatus())) {
            reconcileQuality(batch);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!Set.of("READY", "RETRY", "UNKNOWN", "COMMITTING").contains(batch.getCommitStatus())) {
            return;
        }
        if (batch.getAttemptCount() >= 10) {
            batches.update(null, Wrappers.lambdaUpdate(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getId, id)
                    .eq(EtlTargetBatchDO::getAttemptCount, batch.getAttemptCount())
                    .eq(EtlTargetBatchDO::getCommitStatus, batch.getCommitStatus())
                    .and(w -> w.isNull(EtlTargetBatchDO::getLeaseUntil).or().le(EtlTargetBatchDO::getLeaseUntil, now))
                    .set(EtlTargetBatchDO::getCommitStatus, "FAILED"));
            return;
        }
        var claimed = coordinator.claimTargetCommit(id);
        if (claimed.isEmpty()) {
            return;
        }
        batch = claimed.get();
        String token = batch.getLeaseToken();
        try {
            // 先前尝试结果不确定时，后续停止仅允许查询回执，绝不进行新的目标端写入。
            var record = records.selectById(batch.getRecordId());
            var receipt = Boolean.TRUE.equals(record.getStopRequested())
                    ? database.findCommitted(batch)
                    .orElseThrow(() -> exception(ETL_TARGET_STATE_CHANGED)) : database.commit(batch);
            coordinator.completeTargetCommit(batch, JsonUtils.toJsonString(receipt));
        } catch (RuntimeException failure) {
            batches.update(null, claimUpdate(id, token).set(EtlTargetBatchDO::getCommitStatus,
                            batch.getAttemptCount() >= 10 ? "FAILED" : "UNKNOWN")
                    .set(EtlTargetBatchDO::getLastError, failure.getClass().getSimpleName())
                    .set(EtlTargetBatchDO::getNextAttemptAt,
                            now.plusSeconds(Math.min(1800, 30L << Math.min(batch.getAttemptCount(), 6)))));
        }
    }

    /**
     * 认领更新。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return Lambda更新条件包装器ETL 目标批次
     */
    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EtlTargetBatchDO> claimUpdate(Long id,
            String token) {
        return Wrappers.lambdaUpdate(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getId, id)
                .eq(EtlTargetBatchDO::getCommitStatus, "COMMITTING").eq(EtlTargetBatchDO::getLeaseToken, token)
                .apply("lease_until>clock_timestamp()")
                .set(EtlTargetBatchDO::getLeaseToken, null).set(EtlTargetBatchDO::getLeaseUntil, null);
    }

    /**
     * 对账质量。
     *
     * @param batch 目标批次信息
     */
    private void reconcileQuality(EtlTargetBatchDO batch) {
        if (!Set.of("PENDING", "RUNNING").contains(batch.getQualityStatus())) {
            return;
        }
        var claimed = coordinator.claimTargetQuality(batch.getId());
        if (claimed.isEmpty()) {
            return;
        }
        batch = claimed.get();
        final Long batchId = batch.getId();
        final String token = batch.getLeaseToken();
        try {
            reconcileQualityClaim(batch);
            batches.update(null, qualityUpdate(batchId, token).set(EtlTargetBatchDO::getAttemptCount, 0)
                    .set(EtlTargetBatchDO::getLastError, null)
                    .setSql("next_attempt_at=clock_timestamp()+interval '30 seconds'"));
        } catch (RuntimeException failure) {
            batches.update(null, qualityUpdate(batchId, token)
                    .set(EtlTargetBatchDO::getQualityStatus,
                            batch.getAttemptCount() >= 10 ? "UNAVAILABLE" : batch.getQualityStatus())
                    .set(EtlTargetBatchDO::getLastError, failure.getClass().getSimpleName())
                    .setSql("next_attempt_at=clock_timestamp()+({0} * interval '1 second')",
                            Math.min(1800, 30L << Math.min(batch.getAttemptCount(), 6))));
        }
    }

    /**
     * 获取ETL 目标批次的质量更新。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return Lambda更新条件包装器ETL 目标批次
     */
    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EtlTargetBatchDO> qualityUpdate(Long id,
            String token) {
        return Wrappers.lambdaUpdate(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getId, id)
                .eq(EtlTargetBatchDO::getCommitStatus, "COMMITTED").eq(EtlTargetBatchDO::getLeaseToken, token)
                .apply("lease_until>clock_timestamp()")
                .set(EtlTargetBatchDO::getLeaseToken, null).set(EtlTargetBatchDO::getLeaseUntil, null);
    }

    /**
     * 对账质量认领。
     *
     * @param batch 目标批次信息
     */
    private void reconcileQualityClaim(EtlTargetBatchDO batch) {
        if (batch.getAdmittedCount() == 0) {
            batches.update(null,
                    Wrappers.lambdaUpdate(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getId, batch.getId())
                            .eq(EtlTargetBatchDO::getLeaseToken, batch.getLeaseToken())
                            .apply("lease_until>clock_timestamp()")
                            .eq(EtlTargetBatchDO::getCommitStatus, "COMMITTED")
                            .eq(EtlTargetBatchDO::getQualityStatus, "PENDING")
                            .set(EtlTargetBatchDO::getQualityStatus, "EMPTY"));
            return;
        }
        var evidence = qualityBatches.selectById(batch.getQualityBatchId());
        if (evidence == null || !"REGISTERED".equals(evidence.getRegistrationStatus())) {
            return;
        }
        JSONObject config = JSONUtil.parseObj(batch.getConfigJson());
        access.requireUserPermission(batch.getExecutionUserId(), ExecutionAccessService.PERMISSION_ETL_RUN);
        if (batch.getQualityInstanceId() == null) {
            QualityEtlBatchRunReqDTO request = new QualityEtlBatchRunReqDTO();
            request.setQualityTaskId(batch.getQualityTaskId());
            request.setExecutionUserId(batch.getExecutionUserId());
            request.setInlineReportId(evidence.getGovernanceReportId());
            request.setQualityBatchId(String.valueOf(evidence.getId()));
            request.setQualityNodeId(evidence.getNodeNo());
            var manifest = new QualityEtlBatchRunReqDTO.Manifest();
            manifest.setSchemaVersion(1);
            manifest.setTargetBatchId(batch.getId());
            manifest.setRunId(String.valueOf(batch.getRecordId()));
            manifest.setRecordId(batch.getRecordId());
            manifest.setReplayOfBatchId(batch.getReplayOfBatchId());
            manifest.setReplayOfRecordId(batch.getReplayOfRecordId());
            manifest.setWorkOrderSubmissionId(batch.getWorkOrderSubmissionId());
            manifest.setNodeId(batch.getNodeNo());
            manifest.setDatabaseId(config.getLong("databaseId"));
            manifest.setSchemaName(config.getStr("schemaName"));
            manifest.setTableName(config.getStr("tableName"));
            manifest.setBatchColumn(config.getStr("batchColumn"));
            manifest.setBatchValue(EtlTargetDatabaseAdapter.physicalKey(batch));
            manifest.setRowColumn(config.getStr("rowColumn"));
            manifest.setStatusColumn(config.getStr("statusColumn"));
            manifest.setPayloadHash(batch.getPayloadHash());
            manifest.setRowCount(batch.getOutputCount());
            manifest.setAdmittedRowCount(batch.getAdmittedCount());
            manifest.setAllowWarnings(config.getBool("allowWarnings", true));
            manifest.setIdentityFields(config.getJSONArray("businessKeys").toList(String.class));
            manifest.setIdentityHash(batch.getIdentityHash());
            request.setManifest(manifest);
            Long instanceId = qualityApi.executeForEtlBatch(request).getCheckedData();
            if (instanceId == null) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
            int changed = batches.update(null,
                    Wrappers.lambdaUpdate(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getId, batch.getId())
                            .eq(EtlTargetBatchDO::getLeaseToken, batch.getLeaseToken())
                            .apply("lease_until>clock_timestamp()")
                            .eq(EtlTargetBatchDO::getCommitStatus, "COMMITTED")
                            .isNull(EtlTargetBatchDO::getQualityInstanceId)
                            .set(EtlTargetBatchDO::getQualityInstanceId, instanceId)
                            .set(EtlTargetBatchDO::getQualityStatus, "RUNNING"));
            if (changed != 1) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
            batch.setQualityInstanceId(instanceId);
        }
        var result = qualityApi.getRunResult(batch.getQualityInstanceId(), batch.getExecutionUserId()).getCheckedData();
        if (result == null || !Objects.equals(result.getInstanceId(), batch.getQualityInstanceId()) || !Objects.equals(
                result.getTaskId(), batch.getQualityTaskId())) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        if (!Set.of("SUCCESS", "FAILED").contains(Objects.requireNonNullElse(result.getInstanceStatus(), ""))) {
            return;
        }
        boolean accepted = "SUCCESS".equals(result.getInstanceStatus()) && Objects.equals(result.getErrorRuleCount(),
                0L)
                && Objects.equals(result.getFailedRuleCount(), 0L) && Objects.equals(result.getFailCount(), 0L);
        batches.update(null, Wrappers.lambdaUpdate(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getId, batch.getId())
                .eq(EtlTargetBatchDO::getLeaseToken, batch.getLeaseToken()).apply("lease_until>clock_timestamp()")
                .eq(EtlTargetBatchDO::getQualityInstanceId, batch.getQualityInstanceId())
                .in(EtlTargetBatchDO::getQualityStatus, "PENDING", "RUNNING")
                .set(EtlTargetBatchDO::getQualityStatus, accepted ? "PASSED" : "FAILED"));
    }

    /**
     * 查询运行的提交与质量状态。
     *
     * @param recordId 记录编号
     * @return 运行的提交与质量状态结果集合
     */
    public List<EtlTargetBatchRespVO> list(Long recordId) {
        if (records.selectById(recordId) == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        return listRaw(recordId).stream().map(b -> BeanUtils.toBean(b, EtlTargetBatchRespVO.class)).toList();
    }

    /**
     * 读取通过准入的目标批次。
     *
     * @param id 记录编号
     * @param afterRow 变更后行
     * @param limit 读取或处理上限
     * @return ETL目标行响应数据
     */
    public EtlTargetRowsRespVO rows(Long id, long afterRow, int limit) {
        return rows(id, SecurityFrameworkUtils.getLoginUserId(), afterRow, limit);
    }

    /**
     * 提取ETL 目标批次行数据。
     *
     * @param id 记录编号
     * @param actor 操作人编号
     * @param afterRow 变更后行
     * @param limit 读取或处理上限
     * @return ETL目标行响应数据
     */
    private EtlTargetRowsRespVO rows(Long id, Long actor, long afterRow, int limit) {
        var batch = require(id);
        archives.requireActive(batch.getRecordId());
        access.requireUserPermission(actor, "dataforge:etl:query");
        var record = records.selectById(batch.getRecordId());
        if (!Objects.equals(record.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS) || Boolean.TRUE.equals(
                record.getStopRequested())
                || listRaw(record.getId()).stream().anyMatch(b -> !"COMMITTED".equals(b.getCommitStatus())
                || !Set.of("PASSED", "EMPTY").contains(b.getQualityStatus()))) {
            throw exception(ETL_TARGET_NOT_AVAILABLE);
        }
        if (afterRow < 0 || limit < 1 || limit > 200) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "每页必须为 1 至 200 行且游标非负");
        }
        if ("EMPTY".equals(batch.getQualityStatus())) {
            return new EtlTargetRowsRespVO(List.of(), afterRow, false);
        }
        var rows = database.readAdmitted(batch, actor, afterRow, limit + 1);
        boolean more = rows.size() > limit;
        var visible = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        long next = afterRow;
        if (!visible.isEmpty()) {
            Object ordinal = visible.getLast().get(JSONUtil.parseObj(batch.getConfigJson()).getStr("rowColumn"));
            try {
                next = Long.parseLong(String.valueOf(ordinal));
            } catch (RuntimeException invalid) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "行游标未获读取授权或被修改，无法安全分页");
            }
            if (next <= afterRow) {
                throw exception(ETL_TARGET_INTEGRITY_FAILED);
            }
        }
        return new EtlTargetRowsRespVO(visible, next, more);
    }

    /**
     * 读取ETL 目标批次的描述及当前状态。
     *
     * @param id 记录编号
     * @param actor 操作人编号
     * @return ETL 目标批次描述响应数据
     */
    public cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchDescribeRespDTO describe(Long id, Long actor) {
        access.requireUserPermission(actor, "dataforge:etl:query");
        var batch = require(id);
        archives.requireActive(batch.getRecordId());
        database.authorizeDescription(batch, actor);
        var record = records.selectById(batch.getRecordId());
        var config = JSONUtil.parseObj(batch.getConfigJson());
        var result = BeanUtils.toBean(batch,
                cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchDescribeRespDTO.class);
        result.setBatchId(id);
        result.setRunStatus(record.getRunStatus());
        result.setDatabaseId(config.getLong("databaseId"));
        result.setSchemaName(config.getStr("schemaName"));
        result.setTableName(config.getStr("tableName"));
        result.setFields(EtlTargetDatabaseAdapter.fields(config));
        var qualityBatch = qualityBatches.selectById(batch.getQualityBatchId());
        if (qualityBatch == null || !Objects.equals(qualityBatch.getRecordId(), batch.getRecordId())
                || !Objects.equals(qualityBatch.getInputCount(), batch.getInputCount())) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        result.setPassCount(qualityBatch.getPassCount());
        result.setWarnCount(qualityBatch.getWarnCount());
        result.setRejectCount(qualityBatch.getRejectCount());
        boolean available = Objects.equals(record.getRunStatus(),
                EtlRecordDO.RUN_STATUS_SUCCESS) && !Boolean.TRUE.equals(record.getStopRequested())
                && listRaw(record.getId()).stream().allMatch(
                b -> "COMMITTED".equals(b.getCommitStatus()) && Set.of("PASSED", "EMPTY")
                        .contains(b.getQualityStatus()));
        // 准入描述也验证目标物化状态；不把平台中的历史 PASSED 当成外部表仍未变化的证明。
        if (available && !"EMPTY".equals(batch.getQualityStatus())) {
            database.readAdmitted(batch, actor, 0, 1);
        }
        result.setAvailable(available);
        return result;
    }

    /**
     * 读取对应消费者。
     *
     * @param request 请求参数
     * @return ETL 目标批次行响应数据
     */
    public cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchRowsRespDTO readForConsumer(
            cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchReadReqDTO request) {
        access.requireUserPermission(request.getExecutionUserId(), "dataforge:etl:query");
        var batch = require(request.getBatchId());
        var config = JSONUtil.parseObj(batch.getConfigJson());
        if (!Objects.equals(batch.getPayloadHash(), request.getExpectedPayloadHash())
                || !Objects.equals(config.getLong("databaseId"), request.getExpectedDatabaseId())
                || !Objects.equals(config.getStr("schemaName"), request.getExpectedSchemaName())
                || !Objects.equals(config.getStr("tableName"), request.getExpectedTableName())) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        var page = rows(batch.getId(), request.getExecutionUserId(), request.getAfterRow(), request.getLimit());
        var result = new cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchRowsRespDTO();
        result.setRows(page.rows().stream().map(row -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            row.forEach((key, value) -> wire.put(key,
                    value instanceof java.math.BigDecimal decimal ? decimal.toPlainString() : value));
            return wire;
        }).toList());
        result.setNextAfterRow(page.nextAfterRow());
        result.setHasMore(page.hasMore());
        return result;
    }

    /**
     * 人工恢复目标回执或质量登记对账。
     *
     * @param request 请求参数
     */
    public void retry(EtlTargetRetryReqVO request) {
        var batch = require(request.getBatchId());
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, "dataforge:etl:run");
        access.requireUserPermission(batch.getExecutionUserId(), "dataforge:etl:run");
        coordinator.retryTargetBatch(batch.getId(), batch.getRecordId(), actor, request.getReason());
    }

    /**
     * 列出原始。
     *
     * @param recordId 记录编号
     * @return 原始结果集合
     */
    public List<EtlTargetBatchDO> listRaw(Long recordId) {
        return batches.selectList(
                new LambdaQueryWrapperX<EtlTargetBatchDO>().eq(EtlTargetBatchDO::getRecordId, recordId)
                        .orderByAsc(EtlTargetBatchDO::getId));
    }

    /**
     * 校验并获取ETL 目标批次。
     *
     * @param id 记录编号
     * @return ETL 目标批次
     */
    public EtlTargetBatchDO require(Long id) {
        EtlTargetBatchDO batch = batches.selectById(id);
        if (batch == null || records.selectById(batch.getRecordId()) == null) {
            throw exception(ETL_TARGET_BATCH_NOT_EXISTS);
        }
        return batch;
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param record 运行记录
     * @param work 工作
     */
    private void owned(EtlRecordDO record, Runnable work) {
        if (!coordinator.writeOwned(record.getId(), record.getExecutionToken(), () -> {
            if (coordinator.shouldStop(record.getId(), record.getExecutionToken())) {
                throw exception(ETL_RUN_LEASE_LOST);
            }
            work.run();
        })) {
            throw exception(ETL_RUN_LEASE_LOST);
        }
    }

    /**
     * 获取ETL 目标批次的身份摘要。
     *
     * @param keys 键
     * @param rows 待处理的行数据
     * @return 计算后的摘要或签名字符串
     */
    static String identityHash(List<String> keys, List<Map<String, Object>> rows) {
        var identity = new EtlPopulationIdentity(keys);
        rows.forEach(identity::add);
        return identity.hash();
    }
}
