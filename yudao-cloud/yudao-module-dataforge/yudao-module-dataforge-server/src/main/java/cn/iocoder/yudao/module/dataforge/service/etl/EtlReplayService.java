package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlReplayReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** Governance 负责修复决策；DataForge 固定并执行已授权的完整数据集合重放。 */
@Service
@Slf4j
public class EtlReplayService {

    @Resource
    private EtlReplayMapper replays;

    @Resource
    private EtlRecordMapper records;

    @Resource
    private EtlPipelineMapper pipelines;

    @Resource
    private EtlTargetBatchMapper targets;

    @Resource
    private EtlRunCoordinator coordinator;

    @Resource
    private EtlNodeAccessService nodeAccess;

    @Resource
    private EtlTargetBatchService targetService;

    @Resource
    private EtlPolicyPlanService policies;

    @Resource
    private EtlDesignService design;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private QualityTaskApi quality;

    @Resource
    private EtlArchiveService archives;

    @Resource
    private EtlEnvironmentService environments;

    @Resource
    private EtlScriptAdmissionService scriptAdmissionService;

    /**
     * 校验当前用户查询权限并读取指定运行的治理重放状态。
     *
     * @param recordId 记录编号
     * @return ETL 重放状态响应数据
     */
    public cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlReplayStatusRespVO status(Long recordId) {
        access.requireUserPermission(SecurityFrameworkUtils.getLoginUserId(), "dataforge:etl:query");
        if (records.selectById(recordId) == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        var replay = replays.selectOne(Wrappers.lambdaQuery(EtlReplayDO.class).eq(EtlReplayDO::getRecordId, recordId));
        return cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(replay,
                cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlReplayStatusRespVO.class);
    }

    /**
     * 复核当前操作者和原执行人的权限后重试治理复检关联。
     *
     * @param request 请求参数
     */
    public void retry(cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlReplayRetryReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, ExecutionAccessService.PERMISSION_ETL_RUN);
        var replay = replays.selectOne(
                Wrappers.lambdaQuery(EtlReplayDO.class).eq(EtlReplayDO::getRecordId, request.getRecordId()));
        if (replay == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        access.requireUserPermission(replay.getActorId(), ExecutionAccessService.PERMISSION_ETL_RUN);
        coordinator.retryReplayAttachment(request.getRecordId(), actor, request.getReason());
    }

    /**
     * 固定已授权的完整目标重放快照，复核血缘及目标状态后受理幂等运行。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    public RunReceipt create(EtlReplayReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, ExecutionAccessService.PERMISSION_ETL_RUN);
        if (!Boolean.TRUE.equals(request.getConfirmedTargetReplacement()) || !Set.of("ORIGINAL", "LATEST_PUBLISHED")
                .contains(request.getSnapshotMode())) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "回放版本与目标替换确认不能为空");
        }
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(request));
        // 请求标识与内容摘要共同约束幂等：同一请求只能取回原运行，不能借重试修改覆盖范围。
        var previous = replays.selectOne(Wrappers.lambdaQuery(EtlReplayDO.class).eq(EtlReplayDO::getActorId, actor)
                .eq(EtlReplayDO::getRequestId, request.getRequestId()));
        if (previous != null) {
            if (!hash.equals(previous.getRequestHash())) {
                throw exception(ETL_RUN_REQUEST_CONFLICT);
            }
            var run = records.selectById(previous.getRecordId());
            if (run == null) {
                throw exception(ETL_RECORD_NOT_EXISTS);
            }
            return new RunReceipt(run.getId(), true, run.getRunStatus(), Boolean.TRUE.equals(run.getStopRequested()));
        }
        var authorization = new QualityEtlWorkOrderReplayDescribeReqDTO();
        authorization.setExecutionUserId(actor);
        authorization.setWorkOrderId(request.getWorkOrderId());
        authorization.setSubmissionId(request.getSubmissionId());
        var scope = quality.describeEtlWorkOrderReplay(authorization).getCheckedData();
        if (scope == null || !Objects.equals(scope.getWorkOrderId(), request.getWorkOrderId()) || !Objects.equals(
                scope.getSubmissionId(), request.getSubmissionId())
                || scope.getOriginalTargets() == null || scope.getOriginalTargets().isEmpty()) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        Long sourceId;
        try {
            sourceId = Long.valueOf(scope.getRunId());
        } catch (RuntimeException invalid) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        var original = records.selectById(sourceId);
        if (original == null || !Objects.equals(original.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS)) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        archives.requireActive(sourceId);
        var pipeline = pipelines.selectById(original.getPipelineId());
        if (pipeline == null) {
            throw exception(ETL_PIPELINE_NOT_EXISTS);
        }
        String sourceSnapshot = "ORIGINAL".equals(
                request.getSnapshotMode()) ? original.getConfigJson() : pipeline.getPublishedConfigJson();
        if (sourceSnapshot == null || ("LATEST_PUBLISHED".equals(request.getSnapshotMode()) && !Objects.equals(
                pipeline.getStatus(), EtlPipelineDO.STATUS_RELEASED))) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        var snapshot = JSONUtil.parseObj(sourceSnapshot);
        environments.validateFrozen(snapshot, actor);
        snapshot.remove("governanceReplay");
        var nodesJson = snapshot.getJSONArray(snapshot.containsKey("executionNodes") ? "executionNodes" : "nodes");
        var edgesJson = snapshot.getJSONArray(snapshot.containsKey("executionEdges") ? "executionEdges" : "edges");
        if (nodesJson == null || edgesJson == null || snapshot.getJSONObject("targetPlans") == null) {
            throw exception(ETL_TARGET_INTEGRITY_FAILED);
        }
        var nodes = EtlGraphConverter.toNodeDefs(maps(nodesJson));
        var dag = EtlDag.build(nodes, EtlGraphConverter.toEdgeDefs(maps(edgesJson)));
        scriptAdmissionService.requireAvailable(nodes);
        design.requireValid(dag);
        // 原始运行保留其固定的参数覆盖值；最新发布版本明确从其
        // 默认值开始，授权与已接受工作线程的快照必须使用相同值。
        if ("LATEST_PUBLISHED".equals(request.getSnapshotMode())) {
            snapshot.remove("parameterValues");
        }
        Map<String, String> params = EtlRunParameterResolver.resolve(snapshot.getJSONArray("parameters"),
                snapshot.getJSONObject("parameterValues"));
        snapshot.set("parameterValues", params);
        nodeAccess.authorize(nodes, actor, params);
        policies.authorize(policies.decode(snapshot.get("governancePlans")), actor);
        var bindings = targetService.validate(dag, actor, params);
        var originalTargets = targets.selectList(
                Wrappers.lambdaQuery(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getRecordId, sourceId));
        // 治理授权、原运行和当前图的目标必须形成完整对应，避免只修复部分目标而破坏同批数据的一致性。
        if (bindings.size() != originalTargets.size() || originalTargets.size() != scope.getOriginalTargets().size()) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "回放必须覆盖原运行所有目标，并属于同一完整检测范围");
        }
        var replayTargets = new JSONObject();
        for (var root : originalTargets) {
            // 已被重放替代的批次沿血缘追到当前有效批次，后续替换仅针对这个已核验的版本。
            var target = EtlReplayLineage.current(targets, root);
            archives.requireActive(target.getRecordId());
            var currentRun = records.selectById(target.getRecordId());
            if (currentRun == null || !Objects.equals(currentRun.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS)
                    || Boolean.TRUE.equals(currentRun.getOccupiesSlot()) || Boolean.TRUE.equals(
                    currentRun.getStopRequested())) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
            var described = scope.getOriginalTargets().stream()
                    .filter(t -> Objects.equals(t.getTargetBatchId(), target.getId())).findFirst()
                    .orElseThrow(() -> exception(ETL_TARGET_INTEGRITY_FAILED));
            if (!Objects.equals(described.getRecordId(), target.getRecordId()) || !Objects.equals(
                    described.getSinkNodeId(), target.getNodeNo())
                    || !Objects.equals(described.getQualityTaskId(), target.getQualityTaskId()) || !"COMMITTED".equals(
                    target.getCommitStatus())
                    || !Set.of("PASSED", "FAILED", "EMPTY").contains(target.getQualityStatus())
                    || !Objects.equals(scope.getIdentityHash(), target.getIdentityHash()) || !Objects.equals(
                    scope.getTotalCount(), target.getInputCount())
                    || !JSONUtil.parseObj(target.getConfigJson()).equals(bindings.get(target.getNodeNo()))) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "原目标、检测范围或冻结绑定已变化，不能覆盖其他数据");
            }
            replayTargets.set(target.getNodeNo(),
                    new JSONObject().set("batchId", target.getId()).set("recordId", target.getRecordId())
                            .set("payloadHash", target.getPayloadHash()));
        }
        snapshot.set("targetPlans", bindings);
        snapshot.set("governanceReplay",
                new JSONObject().set("recordId", sourceId).set("workOrderId", request.getWorkOrderId())
                        .set("submissionId", request.getSubmissionId()).set("targets", replayTargets)
                        .set("confirmedTargetReplacement", true)
                        .set("snapshotMode", request.getSnapshotMode())
                        .set("sourceSnapshotHash", DigestUtil.sha256Hex(sourceSnapshot)));
        var prepared = EtlRecordDO.builder().pipelineId(original.getPipelineId()).name(original.getName())
                .pipelineRevision("ORIGINAL".equals(
                        request.getSnapshotMode()) ? original.getPipelineRevision() : pipeline.getPublishedRevision())
                .executionUserId(actor).configJson(snapshot.toString()).build();
        var command = new EtlReplayDO();
        command.setSourceRecordId(sourceId);
        command.setWorkOrderId(request.getWorkOrderId());
        command.setSubmissionId(request.getSubmissionId());
        command.setActorId(actor);
        command.setRequestId(request.getRequestId());
        command.setRequestHash(hash);
        command.setSnapshotMode(request.getSnapshotMode());
        command.setReason(request.getReason());
        // 现有持久化调度器抢占已接受的运行；协调器事务内不进行远程调用。
        return coordinator.enqueueGovernedReplay(pipeline, prepared, command);
    }

    /**
     * 扫描待恢复的复检关联记录，逐项尝试关联并隔离单项失败。
     */
    public void relay() {
        for (var candidate : replays.selectPending()) {
            try {
                attach(candidate.getId());
            } catch (RuntimeException failure) {
                log.warn("ETL 回放复检关联待恢复 id={} errorType={}", candidate.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 以数据库租约认领已提交目标的复检关联，失败时保留可重试状态。
     *
     * @param id 记录编号
     */
    private void attach(Long id) {
        var replay = replays.selectById(id);
        if (replay == null || !Set.of("PENDING", "RETRY", "ATTACHING").contains(replay.getAttachStatus())) {
            return;
        }
        var batches = targets.selectList(
                Wrappers.lambdaQuery(EtlTargetBatchDO.class).eq(EtlTargetBatchDO::getRecordId, replay.getRecordId()));
        if (batches.isEmpty() || batches.stream().anyMatch(b -> !"COMMITTED".equals(b.getCommitStatus())
                || !Set.of("PASSED", "FAILED").contains(b.getQualityStatus()))) {
            return;
        }
        if (replay.getAttemptCount() >= 10) {
            replays.update(null, Wrappers.lambdaUpdate(EtlReplayDO.class).eq(EtlReplayDO::getId, id)
                    .eq(EtlReplayDO::getAttemptCount, replay.getAttemptCount())
                    .apply("lease_until IS NULL OR lease_until<=clock_timestamp()")
                    .set(EtlReplayDO::getAttachStatus, "FAILED")
                    .set(EtlReplayDO::getLeaseToken, null).set(EtlReplayDO::getLeaseUntil, null));
            return;
        }
        String token = UUID.randomUUID().toString();
        // 数据库租约允许崩溃后接管；回写同时校验令牌，防止旧节点迟到的 RPC 结果覆盖新一轮处理。
        int changed = replays.update(null, Wrappers.lambdaUpdate(EtlReplayDO.class).eq(EtlReplayDO::getId, id)
                .eq(EtlReplayDO::getAttachStatus, replay.getAttachStatus())
                .eq(EtlReplayDO::getAttemptCount, replay.getAttemptCount())
                .apply("(lease_until IS NULL OR lease_until<=clock_timestamp()) AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp())")
                .set(EtlReplayDO::getAttachStatus, "ATTACHING").set(EtlReplayDO::getLeaseToken, token)
                .setSql("lease_until=clock_timestamp()+interval '2 minutes',attempt_count=attempt_count+1"));
        if (changed != 1) {
            return;
        }
        try {
            access.requireUserPermission(replay.getActorId(), ExecutionAccessService.PERMISSION_ETL_RUN);
            var request = new QualityEtlWorkOrderRecheckAttachReqDTO();
            request.setExecutionUserId(replay.getActorId());
            request.setWorkOrderId(replay.getWorkOrderId());
            request.setSubmissionId(replay.getSubmissionId());
            request.setRequestId("etl-replay:" + replay.getId());
            request.setTargetBatchIds(batches.stream().map(EtlTargetBatchDO::getId).sorted().toList());
            if (quality.attachEtlWorkOrderRecheck(request).getCheckedData() == null) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
            replays.update(null, attachUpdate(id, token).set(EtlReplayDO::getAttachStatus, "ATTACHED")
                    .set(EtlReplayDO::getLastError, null));
        } catch (RuntimeException failure) {
            replays.update(null, attachUpdate(id, token).set(EtlReplayDO::getAttachStatus,
                            replay.getAttemptCount() + 1 >= 10 ? "FAILED" : "RETRY")
                    .set(EtlReplayDO::getLastError, failure.getClass().getSimpleName())
                    .setSql("next_attempt_at=clock_timestamp()+({0} * interval '1 second')",
                            Math.min(1800, 30L << Math.min(replay.getAttemptCount(), 6))));
        }
    }

    /**
     * 构造仅允许当前有效关联租约持有者回写的条件，并释放该租约。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return Lambda更新条件包装器ETL 重放
     */
    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EtlReplayDO> attachUpdate(Long id,
            String token) {
        return Wrappers.lambdaUpdate(EtlReplayDO.class).eq(EtlReplayDO::getId, id)
                .eq(EtlReplayDO::getAttachStatus, "ATTACHING")
                .eq(EtlReplayDO::getLeaseToken, token).apply("lease_until>clock_timestamp()")
                .set(EtlReplayDO::getLeaseToken, null).set(EtlReplayDO::getLeaseUntil, null);
    }

    /**
     * 将 JSON 数组转换为对象映射列表。
     *
     * @param values 字段值集合
     * @return 将 JSON 数组转换为对象映射列表结果集合
     */
    private List<Map<String, Object>> maps(JSONArray values) {
        return JsonUtils.parseObject(values.toString(),
                new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                });
    }
}
