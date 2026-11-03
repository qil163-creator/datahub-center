package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol;
import cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt;
import cn.iocoder.yudao.module.dataforge.service.execution.RunLease;
import cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics;
import cn.iocoder.yudao.module.dataforge.service.execution.RunResourceScope;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.ETL;
import static cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.*;

/**
 * ETL 持久受理的短事务边界。外部权限、图结构与连接能力校验必须在调用前完成；
 * 锁顺序统一为流程定义、运行记录、子记录，事务内不提交线程或调用外部服务。
 */
@Service
@RequiredArgsConstructor
public class EtlRunCoordinator {

    private final EtlPipelineMapper pipelines;

    private final EtlRecordMapper records;

    private final RunExecutionProperties properties;

    @jakarta.annotation.Resource
    private RunMetrics metrics;

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlQualityBatchMapper qualityBatches;

    @jakarta.annotation.Resource
    private EtlVersionService versionService;

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlTargetBatchMapper targetBatches;

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlReplayMapper replays;

    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlArchiveMapper archives;

    /**
     * 归档运行。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void archiveRun(cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlArchiveReqVO request,
            Long actor) {
        var record = lockRun(request.getRecordId());
        if (record == null || actor == null || Boolean.TRUE.equals(record.getOccupiesSlot())
                || !java.util.Set.of(3, 4, 5).contains(record.getRunStatus()) || request.getRetentionDays() == null
                || request.getRetentionDays() < 30 || request.getRetentionDays() > 3650 || request.getReason() == null || request.getReason()
                .isBlank()) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        long pendingTargets = targetBatches.selectCount(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getRecordId,
                                record.getId())
                        .and(w -> w.in(
                                        cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                        "READY", "COMMITTING", "UNKNOWN", "RETRY")
                                .or()
                                .in(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getQualityStatus,
                                        "PENDING", "RUNNING", "UNAVAILABLE")));
        if (pendingTargets > 0) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        var archive = archives.selectOne(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRecordId,
                                record.getId()));
        if (archive != null && archive.getRestoredAt() == null) {
            return;
        }
        var now = records.databaseNow();
        if (archive == null) {
            archive = new cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO();
        }
        boolean fresh = archive.getId() == null;
        archive.setRecordId(record.getId());
        archive.setActorId(actor);
        archive.setReason(request.getReason());
        archive.setArchivedAt(now);
        archive.setEvidenceAccessUntil(now.plusDays(request.getRetentionDays()));
        int changed;
        if (fresh) {
            changed = archives.insert(archive);
        } else {
            changed = archives.update(null,
                    Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO.class)
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getId,
                                    archive.getId())
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getActorId, actor)
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getReason,
                                    request.getReason())
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getArchivedAt, now)
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getEvidenceAccessUntil,
                                    archive.getEvidenceAccessUntil())
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRestoredAt, null)
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRestoredBy,
                                    null));
        }
        if (changed != 1 || archives.action(record.getId(), actor, "ARCHIVE", request.getReason()) != 1) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
    }

    /**
     * 恢复已归档运行。
     *
     * @param recordId 记录编号
     * @param actor 操作人编号
     * @param reason 操作原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void restoreArchivedRun(Long recordId, Long actor, String reason) {
        if (actor == null || reason == null || reason.isBlank() || reason.length() > 1000 || lockRun(
                recordId) == null) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        var archive = archives.selectOne(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRecordId, recordId));
        if (archive == null || archive.getRestoredAt() != null) {
            return;
        }
        var now = records.databaseNow();
        if (!archive.getEvidenceAccessUntil().isAfter(now)) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "归档保留期已结束，不能再恢复回放权限");
        }
        if (archives.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getId, archive.getId())
                        .isNull(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRestoredAt)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRestoredAt, now)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO::getRestoredBy,
                                actor)) != 1
                || archives.action(recordId, actor, "RESTORE", reason) != 1) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
    }

    /**
     * 在本地事务内复核重放目标血缘与内容，原子保存运行意图和治理重放命令。
     *
     * @param observed 先前读取的状态
     * @param prepared 已准备的执行配置或记录
     * @param command 待处理命令
     * @return 运行回执
     */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt enqueueGovernedReplay(EtlPipelineDO observed, EtlRecordDO prepared,
            cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO command) {
        var current = lockTask(observed.getId());
        var existing = replays.selectOne(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getActorId,
                                command.getActorId())
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getRequestId,
                                command.getRequestId()));
        if (existing != null) {
            if (!Objects.equals(existing.getRequestHash(), command.getRequestHash())) {
                throw exception(ETL_RUN_REQUEST_CONFLICT);
            }
            var old = records.selectById(existing.getRecordId());
            return new RunReceipt(old.getId(), true, old.getRunStatus(), Boolean.TRUE.equals(old.getStopRequested()));
        }
        if (!sameDefinition(current, observed)) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        var original = records.selectById(command.getSourceRecordId());
        if (original == null || !Objects.equals(original.getPipelineId(), current.getId())
                || !Objects.equals(original.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS)
                || Boolean.TRUE.equals(original.getOccupiesSlot()) || Boolean.TRUE.equals(
                original.getStopRequested())) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        var originals = targetBatches.selectList(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getRecordId,
                                original.getId()));
        if (originals.isEmpty()) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        // 受理事务内再次核对血缘头和内容摘要，关闭远程授权、快照准备与真正入队之间的状态变化窗口。
        var chosen = JSONUtil.parseObj(prepared.getConfigJson()).getJSONObject("governanceReplay")
                .getJSONObject("targets");
        if (chosen.size() != originals.size()) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        for (var root : originals) {
            var head = EtlReplayLineage.current(targetBatches, root);
            var selection = chosen.getJSONObject(head.getNodeNo());
            var headRun = records.selectById(head.getRecordId());
            if (selection == null || !Objects.equals(head.getId(), selection.getLong("batchId"))
                    || !Objects.equals(head.getPayloadHash(), selection.getStr("payloadHash"))
                    || !"COMMITTED".equals(head.getCommitStatus()) || !java.util.Set.of("PASSED", "FAILED", "EMPTY")
                    .contains(head.getQualityStatus())
                    || headRun == null || !Objects.equals(headRun.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS)
                    || Boolean.TRUE.equals(headRun.getOccupiesSlot()) || Boolean.TRUE.equals(
                    headRun.getStopRequested())) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
        }
        long active = replays.selectCount(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getSourceRecordId,
                                original.getId())
                        .inSql(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getRecordId,
                                "SELECT id FROM dh_dataforge_etl_record WHERE deleted=0 AND (run_status IN (1,2,6) OR occupies_slot=true)"));
        if (active > 0 || pending(current.getId()) >= properties.getPerTaskPending()) {
            throw exception(ETL_RUN_PENDING_LIMIT);
        }
        String key = "governed-replay:" + command.getActorId() + ":" + command.getRequestId();
        var receipt = insert(current, prepared, key, command.getRequestHash(), EtlRecordDO.EXECUTE_TYPE_HAND, null);
        command.setRecordId(receipt.recordId());
        command.setAttachStatus("PENDING");
        command.setAttemptCount(0);
        if (replays.insert(command) != 1) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        return receipt;
    }

    /**
     * 校验目标提交租约并保存回执，同时将被替换批次转为失效血缘。
     *
     * @param claim 当前认领凭据
     * @param receiptJson 回执JSON
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean completeTargetCommit(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO claim,
            String receiptJson) {
        if (lockRun(claim.getRecordId()) == null || receiptJson == null) {
            return false;
        }
        int changed = targetBatches.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId, claim.getId())
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                "COMMITTING")
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseToken,
                                claim.getLeaseToken())
                        .apply("lease_until>clock_timestamp()")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                "COMMITTED")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getReceiptJson,
                                receiptJson)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getAttemptCount, 0)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseToken, null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseUntil, null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getNextAttemptAt,
                                null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLastError,
                                null));
        if (changed == 1 && claim.getReplayOfBatchId() != null) {
            // 新批次提交与旧批次失效同事务完成；旧内容已变化时整体回滚，避免形成两条有效血缘。
            int replaced = targetBatches.update(null,
                    Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId,
                                    claim.getReplayOfBatchId())
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getPayloadHash,
                                    claim.getExpectedPayloadHash())
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                    "COMMITTED")
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                    "SUPERSEDED")
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getReplacedByBatchId,
                                    claim.getId()));
            if (replaced != 1) {
                throw exception(ETL_TARGET_STATE_CHANGED);
            }
        }
        return changed == 1;
    }

    /** 父记录锁将提交意图的接受与停止串行化；远程数据库调用在本方法返回后执行。 */
    @Transactional(rollbackFor = Exception.class)
    public void retryReplayAttachment(Long recordId, Long actor, String reason) {
        var record = lockRun(recordId);
        if (record == null || actor == null || reason == null || reason.isBlank() || reason.length() > 1000
                || !Objects.equals(record.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS)) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        var replay = replays.selectOne(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getRecordId, recordId));
        if (replay == null || !"FAILED".equals(replay.getAttachStatus())) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        var batch = targetBatches.selectList(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getRecordId,
                                recordId)
                        .orderByAsc(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId));
        if (batch.isEmpty()) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        int changed = replays.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getId, replay.getId())
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getAttachStatus, "FAILED")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getAttachStatus,
                                "PENDING")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getAttemptCount, 0)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getLeaseToken, null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getLeaseUntil, null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO::getNextAttemptAt, null));
        if (changed != 1 || targetBatches.insertAction(batch.getFirst().getId(), actor, "RECHECK_RETRY", reason,
                "FAILED") != 1) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
    }

    /** 父记录锁将提交意图的接受与停止串行化；远程数据库调用在本方法返回后执行。 */
    @Transactional(rollbackFor = Exception.class)
    public Optional<cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO> claimTargetCommit(
            Long batchId) {
        var seen = targetBatches.selectById(batchId);
        if (seen == null) {
            throw exception(ETL_TARGET_BATCH_NOT_EXISTS);
        }
        var record = lockRun(seen.getRecordId());
        if (record == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        var batch = targetBatches.selectById(batchId);
        if (!java.util.Set.of("READY", "RETRY", "UNKNOWN", "COMMITTING")
                .contains(batch.getCommitStatus()) || batch.getAttemptCount() >= 10) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(record.getStopRequested()) && "READY".equals(batch.getCommitStatus())) {
            targetBatches.update(null,
                    Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId, batchId)
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                    "READY")
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                    "CANCELLED")
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getQualityStatus,
                                    "NOT_RUN"));
            return Optional.empty();
        }
        String token = java.util.UUID.randomUUID().toString();
        int changed = targetBatches.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId, batchId)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                batch.getCommitStatus())
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getAttemptCount,
                                batch.getAttemptCount())
                        .apply("(lease_until IS NULL OR lease_until<=clock_timestamp()) AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp())")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                "COMMITTING")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseToken,
                                token)
                        .setSql("lease_until=clock_timestamp()+interval '2 minutes',attempt_count=attempt_count+1"));
        return changed == 1 ? Optional.of(targetBatches.selectById(batchId)) : Optional.empty();
    }

    /**
     * 认领目标质量。
     *
     * @param batchId 批次编号
     * @return ETL 目标批次
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO> claimTargetQuality(
            Long batchId) {
        var observed = targetBatches.selectById(batchId);
        if (observed == null || lockRun(observed.getRecordId()) == null) {
            return Optional.empty();
        }
        var batch = targetBatches.selectById(batchId);
        if (!"COMMITTED".equals(batch.getCommitStatus()) || !java.util.Set.of("PENDING", "RUNNING")
                .contains(batch.getQualityStatus())) {
            return Optional.empty();
        }
        if (batch.getAttemptCount() >= 10) {
            targetBatches.update(null,
                    Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId, batchId)
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                    "COMMITTED")
                            .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getAttemptCount,
                                    batch.getAttemptCount())
                            .apply("lease_until IS NULL OR lease_until<=clock_timestamp()")
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getQualityStatus,
                                    "UNAVAILABLE")
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseToken,
                                    null)
                            .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseUntil,
                                    null));
            return Optional.empty();
        }
        String token = java.util.UUID.randomUUID().toString();
        int changed = targetBatches.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId, batchId)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                "COMMITTED")
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getAttemptCount,
                                batch.getAttemptCount())
                        .apply("(lease_until IS NULL OR lease_until<=clock_timestamp()) AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp())")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseToken,
                                token)
                        .setSql("lease_until=clock_timestamp()+interval '2 minutes',attempt_count=attempt_count+1"));
        return changed == 1 ? Optional.of(targetBatches.selectById(batchId)) : Optional.empty();
    }

    /**
     * 重试目标批次。
     *
     * @param batchId 批次编号
     * @param recordId 记录编号
     * @param actor 操作人编号
     * @param reason 操作原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void retryTargetBatch(Long batchId, Long recordId, Long actor, String reason) {
        var record = lockRun(recordId);
        if (record == null || actor == null || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        if (Boolean.TRUE.equals(record.getStopRequested())) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        var batch = targetBatches.selectById(batchId);
        if (batch == null || !Objects.equals(batch.getRecordId(), recordId)) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        boolean qualityUnavailable = "COMMITTED".equals(batch.getCommitStatus()) && "UNAVAILABLE".equals(
                batch.getQualityStatus());
        if (!qualityUnavailable && !"FAILED".equals(batch.getCommitStatus())) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
        int changed = targetBatches.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getId, batchId)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                batch.getCommitStatus())
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getQualityStatus,
                                batch.getQualityStatus())
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getCommitStatus,
                                qualityUnavailable ? "COMMITTED" : "UNKNOWN")
                        .set(qualityUnavailable,
                                cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getQualityStatus,
                                batch.getQualityInstanceId() == null ? "PENDING" : "RUNNING")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getAttemptCount, 0)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseToken, null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getLeaseUntil, null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getNextAttemptAt,
                                null));
        if (changed != 1 || targetBatches.insertAction(batchId, actor,
                qualityUnavailable ? "QUALITY_RETRY" : "RECONCILE_RETRY", reason,
                qualityUnavailable ? "UNAVAILABLE" : "FAILED") != 1) {
            throw exception(ETL_TARGET_STATE_CHANGED);
        }
    }

    /** 所有目标端意图仅可在图执行终止后进入 READY；每个 COMMITTED 状态都需要已验证的不可变回执。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean reconcileManagedCommit(Long recordId) {
        var record = lockRun(recordId);
        if (record == null || !Objects.equals(record.getRunStatus(), EtlRecordDO.RUN_STATUS_UNKNOWN)
                || record.getExecutionToken() != null || record.getQualityTaskId() != null) {
            return false;
        }
        var snapshot = JSONUtil.parseObj(record.getConfigJson());
        var nodes = snapshot.getJSONArray("nodes");
        if (nodes == null) {
            return false;
        }
        var definitions = EtlGraphConverter.toNodeDefs(
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(nodes.toString(),
                        new tools.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {
                        }));
        var expected = definitions.stream().filter(n -> "managed-db-output".equals(n.getToolCode())).map(n -> n.getNo())
                .collect(java.util.stream.Collectors.toSet());
        var targets = targetBatches.selectList(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO::getRecordId,
                                recordId));
        if (expected.isEmpty() || expected.size() != targets.size() || targets.stream()
                .anyMatch(b -> !expected.contains(b.getNodeNo())
                        || !"COMMITTED".equals(b.getCommitStatus()) || b.getReceiptJson() == null)) {
            return false;
        }
        int status = Boolean.TRUE.equals(
                record.getStopRequested()) ? EtlRecordDO.RUN_STATUS_STOPPED : EtlRecordDO.RUN_STATUS_SUCCESS;
        int changed = records.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class).eq(EtlRecordDO::getId, recordId)
                .eq(EtlRecordDO::getRowVersion, record.getRowVersion())
                .eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_UNKNOWN)
                .isNull(EtlRecordDO::getExecutionToken)
                .apply("(reconcile_lease_until IS NULL OR reconcile_lease_until<=clock_timestamp())")
                .set(EtlRecordDO::getRunStatus, status)
                .set(EtlRecordDO::getEffectState, RunProtocol.Effect.COMPLETE.name())
                .set(EtlRecordDO::getReviewRequired, false).set(EtlRecordDO::getOccupiesSlot, false)
                .setSql("end_time=clock_timestamp(),row_version=row_version+1")
                .set(EtlRecordDO::getLog, "受管图已退出且全部目标批次提交回执已核实；质量准入单独显示"));
        if (changed == 1) {
            updateSummary(record, status);
            if (status == EtlRecordDO.RUN_STATUS_STOPPED) {
                observeStop(record);
            }
        }
        return changed == 1;
    }

    /** 权限复核由调用方完成；本地事务内仅锁定运行、抢占批次并记录人工操作。 */
    @Transactional(rollbackFor = Exception.class)
    public void retryQualityRegistration(Long batchId, Long recordId, Long actor) {
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        if (lockRun(recordId) == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        var batch = qualityBatches.selectById(batchId);
        if (batch == null || !Objects.equals(batch.getRecordId(), recordId)) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        if (!"FAILED".equals(batch.getRegistrationStatus())) {
            throw exception(ETL_QUALITY_REGISTRATION_CHANGED);
        }
        int changed = qualityBatches.update(null,
                Wrappers.lambdaUpdate(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getId, batchId)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getRegistrationStatus,
                                "FAILED")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getRegistrationStatus,
                                "PENDING")
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getAttemptCount, 0)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getNextAttemptAt,
                                null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getLeaseToken,
                                null)
                        .set(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getLeaseUntil,
                                null));
        if (changed != 1) {
            throw exception(ETL_QUALITY_REGISTRATION_CHANGED);
        }
        if (qualityBatches.insertRetryAudit(batchId, actor, batch.getAttemptCount()) != 1) {
            throw new IllegalStateException("治理登记重试审计未成功持久化");
        }
    }

    /** 质量对账不复用执行令牌；持久版本阻止迟到 RPC 覆盖人工决策。 */
    @Transactional(rollbackFor = Exception.class)
    public Optional<EtlRecordDO> claimQuality(Long recordId) {
        EtlRecordDO current = lockRun(recordId);
        if (current == null) {
            return Optional.empty();
        }
        String token = java.util.UUID.randomUUID().toString();
        int claimed = records.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId)
                .eq(EtlRecordDO::getProtocolVersion, 1).eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_SUCCESS)
                .eq(EtlRecordDO::getOccupiesSlot, true).in(EtlRecordDO::getQualityStatus, "PENDING", "UNKNOWN")
                .lt(EtlRecordDO::getReconcileAttempts, properties.getAutomaticFailureLimit())
                .apply("(next_reconcile_at IS NULL OR next_reconcile_at <= clock_timestamp())")
                .apply("(reconcile_lease_until IS NULL OR reconcile_lease_until <= clock_timestamp())")
                .set(EtlRecordDO::getReconcileToken, token)
                .setSql("reconcile_lease_until=clock_timestamp()+({0} * interval '1 second'),row_version=row_version+1",
                        properties.getLease().toSeconds()));
        return claimed == 1 ? Optional.of(records.selectById(recordId)) : Optional.empty();
    }

    /**
     * 完成质量。
     *
     * @param claim 当前认领凭据
     * @param result 处理结果
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean completeQuality(EtlRecordDO claim,
            cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService.Result result) {
        if (claim.getReconcileToken() == null || claim.getRowVersion() == null || lockRun(claim.getId()) == null) {
            return false;
        }
        String status = result == null ? "UNKNOWN" : result.status();
        if (status == null || !java.util.List.of("PASSED", "FAILED", "PENDING", "UNKNOWN").contains(status)) {
            status = "UNKNOWN";
        }
        boolean terminal = "PASSED".equals(status) || "FAILED".equals(status);
        int failures = "UNKNOWN".equals(status) ? claim.getReconcileAttempts() + 1 : 0;
        var update = Wrappers.lambdaUpdate(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, claim.getId())
                .eq(EtlRecordDO::getRowVersion, claim.getRowVersion())
                .eq(EtlRecordDO::getReconcileToken, claim.getReconcileToken())
                .eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_SUCCESS)
                .eq(EtlRecordDO::getOccupiesSlot, true).apply("reconcile_lease_until > clock_timestamp()")
                .set(EtlRecordDO::getQualityStatus, status)
                .set(EtlRecordDO::getReconcileToken, null).set(EtlRecordDO::getReconcileLeaseUntil, null)
                .set(EtlRecordDO::getReconcileAttempts, failures)
                .set(EtlRecordDO::getReviewRequired, failures >= properties.getAutomaticFailureLimit())
                .setSql("row_version=row_version+1");
        if (result != null && result.instanceId() != null) {
            update.set(EtlRecordDO::getQualityInstanceId, result.instanceId());
        }
        if (terminal) {
            update.set(EtlRecordDO::getOccupiesSlot, false).set(EtlRecordDO::getNextReconcileAt, null);
        } else if (failures >= properties.getAutomaticFailureLimit()) {
            update.set(EtlRecordDO::getNextReconcileAt, null);
        } else {
            long delay = failures == 0 ? properties.getHeartbeat().toSeconds()
                    : RunProtocol.retryDelaySeconds(failures,
                    java.util.concurrent.ThreadLocalRandom.current().nextDouble());
            update.setSql("next_reconcile_at=clock_timestamp()+({0} * interval '1 second')", delay);
        }
        int changed = records.update(null, update);
        if (changed == 1 && "UNKNOWN".equals(status)) {
            metrics.event(ETL, RECONCILE_FAILED);
        }
        return changed == 1;
    }

    /** 调用方须先完成当前操作权限及对象可见性校验，重试不验证可编辑草稿的图。 */
    public Optional<RunReceipt> findManualReceipt(Long taskId, Long actorId, String requestId) {
        return findManualReceipt(taskId, actorId, requestId, null);
    }

    /**
     * 调用方须先完成当前操作权限及对象可见性校验，重试不验证可编辑草稿的图。
     *
     * @param taskId 任务编号
     * @param actorId 操作人编号
     * @param requestId 请求编号
     * @param inputFingerprint 输入指纹
     * @return 运行回执
     */
    public Optional<RunReceipt> findManualReceipt(Long taskId, Long actorId, String requestId,
            String inputFingerprint) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("invalid request identity");
        }
        String key = "manual:" + taskId + ":" + requestId;
        return replay(key, requestHash("manual", taskId, actorId, key, inputFingerprint));
    }

    /** 发布冻结与一次性运行受理同事务，发布失败不能留下半个可运行版本。 */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt publish(EtlPipelineDO observed, String snapshot, Long actor, EtlRecordDO prepared) {
        EtlPipelineDO current = lockTask(observed.getId());
        if (actor == null || snapshot == null) {
            throw new IllegalArgumentException("publication identity and snapshot required");
        }
        if (!Objects.equals(current.getRevision(), observed.getRevision())) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        if (Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(current.getStatus())
                && Objects.equals(current.getPublishedRevision(), current.getRevision())
                && Objects.equals(current.getPublishedExecutionUserId(), actor)
                && current.getPublishedConfigJson() != null
                && JSONUtil.parseObj(current.getPublishedConfigJson()).equals(JSONUtil.parseObj(snapshot))) {
            if (JSONUtil.parseObj(snapshot).getBool("subflowDefinition", false)
                    || !Integer.valueOf(EtlPipelineDO.TASK_TYPE_ONCE).equals(current.getTaskType())) {
                return null;
            }
            String key = "release:" + current.getId() + ":" + current.getScheduleGeneration();
            return replay(key, requestHash("RELEASE", current.getId(), actor, key))
                    .orElseThrow(() -> exception(ETL_RUN_DEFINITION_CHANGED));
        }
        long generation = current.getScheduleGeneration() + 1;
        versionService.capturePublished(current, generation, actor, snapshot);
        pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getStatus, EtlPipelineDO.STATUS_RELEASED)
                .set(EtlPipelineDO::getScheduleStatus, EtlPipelineDO.SCHEDULE_OFF)
                .set(EtlPipelineDO::getNextRunTime, null).set(EtlPipelineDO::getScheduleGeneration, generation)
                .set(EtlPipelineDO::getReleaseUserId, actor).set(EtlPipelineDO::getPublishedExecutionUserId, actor)
                .set(EtlPipelineDO::getPublishedRevision, current.getRevision())
                .set(EtlPipelineDO::getPublishedConfigJson, snapshot,
                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .setSql("release_time=clock_timestamp()")
                .eq(EtlPipelineDO::getId, current.getId()));
        if (JSONUtil.parseObj(snapshot).getBool("subflowDefinition", false)
                || !Integer.valueOf(EtlPipelineDO.TASK_TYPE_ONCE).equals(current.getTaskType())) {
            return null;
        }
        if (pending(current.getId()) >= properties.getPerTaskPending()) {
            throw exception(ETL_RUN_PENDING_LIMIT);
        }
        current.setScheduleGeneration(generation);
        EtlRecordDO accepted = EtlRecordDO.builder().pipelineId(current.getId()).name(current.getName())
                .pipelineRevision(current.getRevision()).qualityTaskId(current.getQualityTaskId())
                .executionUserId(actor).configJson(snapshot).build();
        String key = "release:" + current.getId() + ":" + generation;
        return insert(current, accepted, key, requestHash("RELEASE", current.getId(), actor, key),
                EtlRecordDO.EXECUTE_TYPE_AUTO, null);
    }

    /**
     * 变更调度。
     *
     * @param id 记录编号
     * @param enable 启用
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeSchedule(Long id, boolean enable) {
        EtlPipelineDO current = lockTask(id);
        if (Objects.equals(current.getScheduleStatus(),
                enable ? EtlPipelineDO.SCHEDULE_ON : EtlPipelineDO.SCHEDULE_OFF)) {
            return;
        }
        LocalDateTime next = null;
        if (enable) {
            if (!Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(current.getStatus())) {
                throw exception(ETL_PIPELINE_NOT_RELEASED_FOR_SCHEDULE);
            }
            if (current.getPublishedConfigJson() == null) {
                throw exception(ETL_RUN_DEFINITION_CHANGED);
            }
            var snapshot = JSONUtil.parseObj(current.getPublishedConfigJson());
            if (snapshot.getBool("subflowDefinition", false)) {
                throw exception(ETL_RUN_DEFINITION_CHANGED);
            }
            if (!Integer.valueOf(EtlPipelineDO.TASK_TYPE_PERIODIC).equals(
                    snapshot.getInt("taskType", current.getTaskType()))) {
                throw exception(ETL_PIPELINE_NOT_PERIODIC);
            }
            String cron = snapshot.getStr("cron");
            if (cron == null || !CronExpression.isValidExpression(cron)) {
                throw exception(ETL_PIPELINE_CRON_INVALID);
            }
            next = CronExpression.parse(cron).next(records.databaseNow());
        }
        pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getScheduleStatus, enable ? EtlPipelineDO.SCHEDULE_ON : EtlPipelineDO.SCHEDULE_OFF)
                .set(EtlPipelineDO::getNextRunTime, next).setSql("schedule_generation=schedule_generation+1")
                .eq(EtlPipelineDO::getId, id));
    }

    /**
     * 取消发布ETL运行。
     *
     * @param id 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void unpublish(Long id) {
        EtlPipelineDO current = lockTask(id);
        if (!Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(current.getStatus())) {
            throw exception(ETL_PIPELINE_NOT_RELEASED);
        }
        if (Integer.valueOf(EtlPipelineDO.SCHEDULE_ON).equals(current.getScheduleStatus())) {
            throw exception(ETL_PIPELINE_SCHEDULE_ON);
        }
        pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getStatus, EtlPipelineDO.STATUS_UNRELEASED)
                .set(EtlPipelineDO::getReleaseTime, null).set(EtlPipelineDO::getReleaseUserId, null)
                .set(EtlPipelineDO::getNextRunTime, null).setSql("schedule_generation=schedule_generation+1")
                .eq(EtlPipelineDO::getId, id));
        cancelPendingAutomatic(id);
    }

    /**
     * 取消待处理自动。
     *
     * @param id 记录编号
     */
    private void cancelPendingAutomatic(Long id) {
        records.update(null, pendingAutomatic(id).eq(EtlRecordDO::getOccupiesSlot, false)
                .set(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_STOPPED)
                .set(EtlRecordDO::getStopRequested, true)
                .setSql("stop_requested_at=clock_timestamp(),end_time=clock_timestamp(),row_version=row_version+1"));
        records.update(null, pendingAutomatic(id).eq(EtlRecordDO::getOccupiesSlot, true)
                .set(EtlRecordDO::getStopRequested, true)
                .setSql("stop_requested_at=clock_timestamp(),row_version=row_version+1"));
    }

    /**
     * 获取ETL运行的待处理自动。
     *
     * @param id 记录编号
     * @return Lambda更新条件包装器ETL 运行记录
     */
    private LambdaUpdateWrapper<EtlRecordDO> pendingAutomatic(Long id) {
        return Wrappers.lambdaUpdate(EtlRecordDO.class).eq(EtlRecordDO::getPipelineId, id)
                .eq(EtlRecordDO::getProtocolVersion, 1)
                .eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING)
                .eq(EtlRecordDO::getExecuteType, EtlRecordDO.EXECUTE_TYPE_AUTO);
    }

    /** 所有流程/节点写入必须在同一短事务内通过执行权校验，调用方不得在回调中执行外部 I/O。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean writeOwned(Long recordId, String token, Runnable databaseWrite) {
        if (lockRun(recordId) == null || !owns(recordId, token)) {
            return false;
        }
        databaseWrite.run();
        // 即使数据库写入意外阻塞至租约过期，也回滚这组写入，不留下越期结果。
        if (!owns(recordId, token)) {
            throw exception(ETL_RUN_LEASE_LOST);
        }
        return true;
    }

    /**
     * 保存ETL运行结束状态。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @param status 目标状态
     * @param effect 副作用
     * @param terminated 已终止
     * @param log 日志
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finish(Long recordId, String token, int status, RunProtocol.Effect effect,
            boolean terminated, String log) {
        if (effect == null || status < 3 || status > 6) {
            throw new IllegalArgumentException("invalid completion evidence");
        }
        EtlRecordDO record = lockRun(recordId);
        if (record == null) {
            return false;
        }
        int outcome = terminated ? status : EtlRecordDO.RUN_STATUS_UNKNOWN;
        boolean qualityPending = outcome == EtlRecordDO.RUN_STATUS_SUCCESS && record.getQualityTaskId() != null;
        boolean release = RunProtocol.canRelease(terminated, effect, qualityPending, false);
        int changed = records.update(null, owned(recordId, token)
                .set(EtlRecordDO::getRunStatus, outcome)
                .set(EtlRecordDO::getQualityStatus, qualityPending ? "PENDING" : "NOT_CONFIGURED")
                .set(EtlRecordDO::getEffectState, effect.name()).set(EtlRecordDO::getOccupiesSlot, !release)
                .set(EtlRecordDO::getReviewRequired, !release && !qualityPending)
                .set(EtlRecordDO::getLog, log).set(EtlRecordDO::getExecutionToken, null)
                .set(EtlRecordDO::getLeaseUntil, null).set(EtlRecordDO::getLeaseOwner, null)
                .setSql("end_time=clock_timestamp(),row_version=row_version+1"));
        if (changed == 1) {
            updateSummary(record, outcome);
            if (terminated && outcome != EtlRecordDO.RUN_STATUS_UNKNOWN) {
                observeStop(record);
            }
        }
        return changed == 1;
    }


    /** 当前权限在 Service 入口重新校验；回执与停止意图在同一事务内落库。 */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt stopCommand(Long recordId, String requestId, Long actorId) {
        validateCommandIdentity(requestId, actorId);
        EtlRecordDO before = lockRun(recordId);
        if (before == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        String hash = commandHash("STOP", recordId, actorId);
        Optional<RunReceipt> replay = replayCommand(requestId, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requestStop(recordId); // 已持有父任务和运行锁，调用的数据库逻辑仍处于本事务。
        if (!Boolean.TRUE.equals(before.getStopRequested())) {
            records.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class)
                    .eq(EtlRecordDO::getId, recordId)
                    .eq(EtlRecordDO::getStopRequested, true)
                    .set(EtlRecordDO::getStopRequestId, requestId).set(EtlRecordDO::getStopRequestHash, hash)
                    .set(EtlRecordDO::getStopActorId, actorId));
        }
        EtlRecordDO after = records.selectById(recordId);
        return saveCommand(requestId, "STOP", hash, actorId, null, before, after);
    }

    /** 人工核查只解除已经查明的占位；永不把人工确认伪造为质量 PASSED。 */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt resolve(
            cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlResolveCommandReqVO request, Long actorId) {
        validateCommandIdentity(request.getRequestId(), actorId);
        if (!Boolean.TRUE.equals(request.getConfirmedStopped()) || !Boolean.TRUE.equals(request.getOutputReviewed())
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
                || request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 500
                || request.getEvidenceRef() == null || request.getEvidenceRef().isBlank() || request.getEvidenceRef()
                .length() > 500
                || !java.util.Set.of("SUCCESS", "FAILED", "STOPPED").contains(
                Objects.toString(request.getOutcome(), ""))) {
            throw exception(ETL_RUN_REVIEW_REQUIRED);
        }
        EtlRecordDO before = lockRun(request.getRecordId());
        if (before == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        String hash = commandHash("RESOLVE", request.getRecordId(), actorId, request.getExpectedVersion(),
                request.getConfirmedStopped(), request.getOutputReviewed(), request.getOutcome(),
                request.getReason(), request.getEvidenceRef());
        Optional<RunReceipt> replay = replayCommand(request.getRequestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!Objects.equals(request.getExpectedVersion(), before.getRowVersion())) {
            throw exception(ETL_RUN_VERSION_CONFLICT);
        }
        LocalDateTime now = records.databaseNow();
        boolean qualityUnknown = Integer.valueOf(EtlRecordDO.RUN_STATUS_SUCCESS).equals(before.getRunStatus())
                && "UNKNOWN".equals(before.getQualityStatus());
        boolean reviewable = Integer.valueOf(EtlRecordDO.RUN_STATUS_UNKNOWN).equals(before.getRunStatus())
                || Boolean.TRUE.equals(before.getReviewRequired()) || qualityUnknown;
        if (!Boolean.TRUE.equals(before.getOccupiesSlot()) || !reviewable
                || before.getLeaseUntil() != null && before.getLeaseUntil().isAfter(now)
                || before.getReconcileLeaseUntil() != null && before.getReconcileLeaseUntil().isAfter(now)
                || qualityUnknown && !"FAILED".equals(request.getOutcome())) {
            throw exception(ETL_RUN_REVIEW_REQUIRED);
        }
        int status = qualityUnknown ? EtlRecordDO.RUN_STATUS_SUCCESS : switch (request.getOutcome()) {
            case "SUCCESS" -> EtlRecordDO.RUN_STATUS_SUCCESS;
            case "FAILED" -> EtlRecordDO.RUN_STATUS_FAILED;
            default -> EtlRecordDO.RUN_STATUS_STOPPED;
        };
        boolean qualityPending = !qualityUnknown && status == EtlRecordDO.RUN_STATUS_SUCCESS
                && before.getQualityTaskId() != null;
        String resolution = JSONUtil.createObj().set("actorId", actorId)
                .set("expectedVersion", request.getExpectedVersion())
                .set("requestId", request.getRequestId()).set("confirmedStopped", true).set("outputReviewed", true)
                .set("outcome", request.getOutcome()).set("reason", request.getReason())
                .set("evidenceRef", request.getEvidenceRef()).set("resolvedAt", now.toString()).toString();
        int updated = records.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, before.getId())
                .eq(EtlRecordDO::getRowVersion, request.getExpectedVersion()).eq(EtlRecordDO::getOccupiesSlot, true)
                .apply("(lease_until IS NULL OR lease_until <= clock_timestamp())")
                .apply("(reconcile_lease_until IS NULL OR reconcile_lease_until <= clock_timestamp())")
                .set(EtlRecordDO::getRunStatus, status).set(EtlRecordDO::getReviewRequired, false)
                .set(EtlRecordDO::getOccupiesSlot, qualityPending)
                .set(EtlRecordDO::getQualityStatus,
                        qualityUnknown ? "FAILED" : qualityPending ? "PENDING" : "NOT_CONFIGURED")
                .set(EtlRecordDO::getEffectState, status == EtlRecordDO.RUN_STATUS_SUCCESS
                        ? RunProtocol.Effect.COMPLETE.name() : before.getEffectState())
                .set(EtlRecordDO::getExecutionToken, null).set(EtlRecordDO::getLeaseOwner, null)
                .set(EtlRecordDO::getLeaseUntil, null).set(EtlRecordDO::getReconcileToken, null)
                .set(EtlRecordDO::getReconcileLeaseUntil, null).set(EtlRecordDO::getReconcileAttempts, 0)
                .set(EtlRecordDO::getNextReconcileAt, null).set(EtlRecordDO::getNextAttemptAt, null)
                .set(EtlRecordDO::getResolutionJson, resolution,
                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .setSql("row_version=row_version+1,end_time=clock_timestamp()"));
        if (updated != 1) {
            throw exception(ETL_RUN_VERSION_CONFLICT);
        }
        updateSummary(before, status);
        metrics.event(ETL, RESOLVED);
        observeStop(before);
        return saveCommand(request.getRequestId(), "RESOLVE", hash, actorId, request.getExpectedVersion(),
                before, records.selectById(before.getId()));
    }

    /**
     * 校验命令身份。
     *
     * @param requestId 请求编号
     * @param actorId 操作人编号
     */
    private static void validateCommandIdentity(String requestId, Long actorId) {
        if (actorId == null || requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("actor and stable request identity are required");
        }
    }

    /**
     * 计算命令内容摘要，区分同一请求标识下的不同操作。
     *
     * @param values 字段值集合
     * @return 计算后的摘要或签名字符串
     */
    private static String commandHash(Object... values) {
        return DigestUtil.sha256Hex(JSONUtil.toJsonStr(values));
    }

    /**
     * 重放命令。
     *
     * @param requestId 请求编号
     * @param hash 摘要
     * @return 运行回执
     */
    private Optional<RunReceipt> replayCommand(String requestId, String hash) {
        var existing = records.selectCommand(requestId);
        if (existing == null) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(existing.getDeleted()) || !Objects.equals(hash, existing.getRequestHash())) {
            throw exception(ETL_RUN_REQUEST_CONFLICT);
        }
        var result = JSONUtil.parseObj(existing.getResultJson());
        return Optional.of(new RunReceipt(result.getLong("recordId"), true, result.getInt("runStatus"),
                result.getBool("stopRequested", false)));
    }

    /**
     * 保存命令。
     *
     * @param requestId 请求编号
     * @param action 待执行的操作
     * @param hash 摘要
     * @param actorId 操作人编号
     * @param expectedVersion 预期版本
     * @param before 变更前状态
     * @param after 变更后状态
     * @return 运行回执
     */
    private RunReceipt saveCommand(String requestId, String action, String hash, Long actorId, Long expectedVersion,
            EtlRecordDO before, EtlRecordDO after) {
        var receipt = new RunReceipt(after.getId(), false, after.getRunStatus(),
                Boolean.TRUE.equals(after.getStopRequested()));
        String result = JSONUtil.createObj().set("recordId", receipt.recordId()).set("runStatus", receipt.runStatus())
                .set("stopRequested", receipt.stopRequested()).toString();
        var command = cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunCommandDO.builder()
                .recordId(after.getId()).requestId(requestId).action(action).requestHash(hash).actorId(actorId)
                .expectedVersion(expectedVersion).resultJson(result).beforeJson(auditState(before))
                .afterJson(auditState(after)).build();
        try {
            records.insertCommand(command);
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            // 跨父任务并发复用同一 requestId 时回滚本事务，不能保留已改变的停止/核查状态。
            throw exception(ETL_RUN_REQUEST_CONFLICT);
        }
        return receipt;
    }

    /**
     * 获取ETL运行的审计状态。
     *
     * @param record 运行记录
     * @return ETL运行的审计状态文本
     */
    private static String auditState(EtlRecordDO record) {
        return JSONUtil.createObj().set("recordId", record.getId()).set("runStatus", record.getRunStatus())
                .set("rowVersion", record.getRowVersion()).set("occupiesSlot", record.getOccupiesSlot())
                .set("effectState", record.getEffectState()).set("reviewRequired", record.getReviewRequired())
                .set("stopRequested", record.getStopRequested()).set("qualityStatus", record.getQualityStatus())
                .set("resolution",
                        record.getResolutionJson() == null ? null : JSONUtil.parseObj(record.getResolutionJson()))
                .toString();
    }

    /**
     * 请求停止。
     *
     * @param recordId 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void requestStop(Long recordId) {
        EtlRecordDO record = lockRun(recordId);
        if (record == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        if (Boolean.TRUE.equals(record.getStopRequested())) {
            return;
        }
        if (!Boolean.TRUE.equals(record.getOccupiesSlot()) && !Integer.valueOf(1).equals(record.getRunStatus())) {
            return;
        }
        var update = Wrappers.lambdaUpdate(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId)
                .set(EtlRecordDO::getStopRequested, true)
                .setSql("stop_requested_at=clock_timestamp(),row_version=row_version+1");
        if (!Boolean.TRUE.equals(record.getOccupiesSlot()) && Integer.valueOf(1).equals(record.getProtocolVersion())) {
            update.set(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_STOPPED)
                    .set(EtlRecordDO::getEffectState, RunProtocol.Effect.NONE.name())
                    .setSql("end_time=clock_timestamp()");
        }
        int changed = records.update(null, update);
        if (changed == 1 && !Boolean.TRUE.equals(record.getOccupiesSlot())
                && Integer.valueOf(1).equals(record.getProtocolVersion())) {
            metrics.stopConfirmed(ETL, () -> records.selectStopTiming(recordId, record.getRowVersion() + 1));
        }
    }

    /**
     * 判断是否需要停止条件。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 判断结果
     */
    public boolean shouldStop(Long recordId, String token) {
        if (!owns(recordId, token)) {
            return true;
        }
        return records.selectCount(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId)
                .eq(EtlRecordDO::getStopRequested, true)) != 0;
    }

    /**
     * 更新汇总。
     *
     * @param record 运行记录
     * @param status 目标状态
     */
    private void updateSummary(EtlRecordDO record, int status) {
        pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getRunStatus, status).setSql("end_time=clock_timestamp()")
                .eq(EtlPipelineDO::getId, record.getPipelineId())
                .eq(EtlPipelineDO::getLatestRunId, record.getId()));
    }

    /**
     * 在任务锁及容量限制内认领队首运行，授予有时效的执行令牌。
     *
     * @param recordId 记录编号
     * @param nodeInstanceId 节点实例编号
     * @return 运行租约
     */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Optional<RunLease> claim(Long recordId, String nodeInstanceId) {
        if (nodeInstanceId == null || nodeInstanceId.isBlank() || nodeInstanceId.length() > 128) {
            throw new IllegalArgumentException("node instance identity is required");
        }
        EtlRecordDO record = lockRun(recordId);
        if (record == null || records.countSlots(record.getPipelineId()) != 0) {
            return Optional.empty();
        }
        // 手动请求后的即时派发同样必须遵守队首；不能越过正在退避的已受理运行。
        if (!Objects.equals(recordId, records.selectPendingHead(record.getPipelineId()))) {
            return Optional.empty();
        }
        String resourceIds = record.getResourceIds() == null
                ? RunResourceScope.etl(record.getConfigJson()) : record.getResourceIds();
        if (!records.hasCapacity(properties.getPlatformActive(), properties.getPerDataSourceActive(),
                RunResourceScope.decode(resourceIds))) {
            metrics.event(ETL, CAPACITY_REJECTED);
            return Optional.empty();
        }
        String token = java.util.UUID.randomUUID().toString();
        int updated = records.update(null, Wrappers.lambdaUpdate(EtlRecordDO.class)
                .set(EtlRecordDO::getExecutionToken, token).set(EtlRecordDO::getLeaseOwner, nodeInstanceId)
                .set(EtlRecordDO::getOccupiesSlot, true)
                .set(EtlRecordDO::getResourceIds, resourceIds,
                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .set(EtlRecordDO::getExecutionStage, RunProtocol.Stage.PREPARE.name())
                .setSql("lease_until=clock_timestamp()+({0} * interval '1 second'),attempt_count=attempt_count+1,row_version=row_version+1",
                        properties.getLease().toSeconds())
                .eq(EtlRecordDO::getId, recordId)
                .eq(EtlRecordDO::getProtocolVersion, 1).eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING)
                .eq(EtlRecordDO::getOccupiesSlot, false).eq(EtlRecordDO::getStopRequested, false)
                .eq(EtlRecordDO::getReviewRequired, false).isNull(EtlRecordDO::getExecutionToken)
                .apply("(next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())"));
        if (updated == 0) {
            return Optional.empty();
        }
        pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getLatestRunId, recordId)
                .set(EtlPipelineDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING)
                .eq(EtlPipelineDO::getId, record.getPipelineId()));
        metrics.event(ETL, CLAIMED);
        return Optional.of(new RunLease(recordId, token, records.selectById(recordId).getLeaseUntil()));
    }

    /**
     * 仅为当前仍有效的执行令牌续约。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean renew(Long recordId, String token) {
        if (lockRun(recordId) == null) {
            return false;
        }
        return records.update(null, owned(recordId, token)
                .setSql("lease_until=clock_timestamp()+({0} * interval '1 second')",
                        properties.getLease().toSeconds())) == 1;
    }

    /**
     * 判断执行令牌是否仍拥有未过期的运行占位。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 处理是否成功
     */
    public boolean owns(Long recordId, String token) {
        if (token == null) {
            return false;
        }
        return records.selectCount(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId)
                .eq(EtlRecordDO::getProtocolVersion, 1).eq(EtlRecordDO::getExecutionToken, token)
                .eq(EtlRecordDO::getOccupiesSlot, true).in(EtlRecordDO::getRunStatus, 1, 2)
                .apply("lease_until > clock_timestamp()")) == 1;
    }

    /**
     * 在外部副作用开始前记录执行阶段及未知效果状态。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean startEffects(Long recordId, String token) {
        EtlRecordDO record = lockRun(recordId);
        if (record == null) {
            return false;
        }
        // 外部副作用开始前先持久化 UNKNOWN；节点随后宕机时不能把失联误判为未执行并自动重跑。
        int updated = records.update(null, owned(recordId, token)
                .set(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_RUNNING)
                .set(EtlRecordDO::getExecutionStage, RunProtocol.Stage.EXECUTION_STARTED.name())
                .set(EtlRecordDO::getEffectState, RunProtocol.Effect.UNKNOWN.name())
                .setSql("start_time=clock_timestamp(),row_version=row_version+1")
                .eq(EtlRecordDO::getExecutionStage, RunProtocol.Stage.PREPARE.name())
                .eq(EtlRecordDO::getStopRequested, false));
        if (updated == 0) {
            return false;
        }
        pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getRunStatus, EtlRecordDO.RUN_STATUS_RUNNING)
                .setSql("start_time=clock_timestamp(),end_time=NULL")
                .eq(EtlPipelineDO::getId, record.getPipelineId())
                .eq(EtlPipelineDO::getLatestRunId, recordId));
        return true;
    }

    /**
     * 将尚未开始外部副作用的运行重新排队，并同步任务摘要。
     *
     * @param recordId 记录编号
     * @param token 当前执行或租约令牌
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseBeforeExecution(Long recordId, String token) {
        EtlRecordDO record = lockRun(recordId);
        if (record == null || !RunProtocol.Stage.PREPARE.name().equals(record.getExecutionStage())) {
            return false;
        }
        int changed = records.update(null, requeue(owned(recordId, token), record));
        if (changed == 1) {
            updateRecoveredSummary(record);
        }
        return changed == 1;
    }

    /** 本机背压不是执行失败；撤销本次领取计数并保留已受理意图。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseForCapacity(Long recordId, String token) {
        EtlRecordDO record = lockRun(recordId);
        if (record == null || !RunProtocol.Stage.PREPARE.name().equals(record.getExecutionStage())) {
            return false;
        }
        boolean stopped = Boolean.TRUE.equals(record.getStopRequested());
        var update = owned(recordId, token).eq(EtlRecordDO::getExecutionStage, RunProtocol.Stage.PREPARE.name())
                .set(EtlRecordDO::getExecutionToken, null).set(EtlRecordDO::getLeaseOwner, null)
                .set(EtlRecordDO::getLeaseUntil, null).set(EtlRecordDO::getOccupiesSlot, false)
                .set(EtlRecordDO::getReviewRequired, false)
                .set(EtlRecordDO::getEffectState, RunProtocol.Effect.NONE.name())
                .set(EtlRecordDO::getRunStatus,
                        stopped ? EtlRecordDO.RUN_STATUS_STOPPED : EtlRecordDO.RUN_STATUS_WAITING)
                .setSql("attempt_count=GREATEST(0,attempt_count-1),row_version=row_version+1");
        if (stopped) {
            update.set(EtlRecordDO::getNextAttemptAt, null).setSql("end_time=clock_timestamp()");
        } else {
            update.setSql("next_attempt_at=clock_timestamp()+interval '5 seconds'");
        }
        int changed = records.update(null, update);
        if (changed == 1) {
            updateRecoveredSummary(record);
            metrics.event(ETL, CAPACITY_REJECTED);
        }
        return changed == 1;
    }

    /**
     * 回收过期准备阶段运行，或将已开始执行的失联运行转入待核查状态。
     *
     * @param recordId 记录编号
     * @return 处理是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean recoverExpired(Long recordId) {
        EtlRecordDO record = lockRun(recordId);
        if (record == null || !Integer.valueOf(1).equals(record.getProtocolVersion())) {
            return false;
        }
        var guard = Wrappers.lambdaUpdate(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId)
                .eq(EtlRecordDO::getRowVersion, record.getRowVersion())
                .eq(EtlRecordDO::getOccupiesSlot, true).in(EtlRecordDO::getRunStatus, 1, 2)
                .apply("lease_until <= clock_timestamp()");
        // 仅准备阶段可安全重新排队；执行阶段过期仍保留资源占位，等待核实外部效果后再决定后续动作。
        if (RunProtocol.Stage.PREPARE.name().equals(record.getExecutionStage())) {
            int changed = records.update(null, requeue(guard, record));
            if (changed == 1) {
                updateRecoveredSummary(record);
                metrics.event(ETL, RECOVERED);
            }
            return changed == 1;
        }
        int changed = records.update(null, guard.set(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_UNKNOWN)
                .set(EtlRecordDO::getEffectState, RunProtocol.Effect.UNKNOWN.name())
                .set(EtlRecordDO::getReviewRequired, true)
                .set(EtlRecordDO::getExecutionToken, null).set(EtlRecordDO::getLeaseUntil, null)
                .set(EtlRecordDO::getLeaseOwner, null)
                .setSql("row_version=row_version+1")
                .set(EtlRecordDO::getLog, "执行阶段租约失效，资源退出及外部效果需核查；禁止自动重跑"));
        if (changed == 1) {
            updateSummary(record, EtlRecordDO.RUN_STATUS_UNKNOWN);
            metrics.event(ETL, RECOVERED);
        }
        return changed == 1;
    }

    /**
     * 更新已恢复汇总。
     *
     * @param before 变更前状态
     */
    private void updateRecoveredSummary(EtlRecordDO before) {
        EtlRecordDO after = records.selectById(before.getId());
        if (Integer.valueOf(EtlRecordDO.RUN_STATUS_STOPPED).equals(after.getRunStatus())) {
            metrics.stopConfirmed(ETL, before.getStopRequestedAt(), after.getEndTime());
        }
        if (Integer.valueOf(EtlRecordDO.RUN_STATUS_WAITING).equals(after.getRunStatus())) {
            pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                    .eq(EtlPipelineDO::getId, before.getPipelineId())
                    .eq(EtlPipelineDO::getLatestRunId, before.getId())
                    .set(EtlPipelineDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING)
                    .set(EtlPipelineDO::getStartTime, null).set(EtlPipelineDO::getEndTime, null));
        } else {
            updateSummary(before, after.getRunStatus());
        }
    }

    /** 删除与领取共用相同锁序；绝不先做无锁检查再删除持有占位的记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(Long recordId, Runnable deleteChildren) {
        EtlRecordDO record = lockRun(recordId);
        if (record == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        if (qualityBatches.selectCount(
                Wrappers.lambdaQuery(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO.class)
                        .eq(cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO::getRecordId,
                                recordId)) > 0) {
            throw exception(ETL_QUALITY_EVIDENCE_RETAINED);
        }
        if (Boolean.TRUE.equals(record.getOccupiesSlot()) || Boolean.TRUE.equals(record.getReviewRequired())
                || java.util.List.of(1, 2, 6).contains(record.getRunStatus())
                || "PENDING".equals(record.getQualityStatus()) || "UNKNOWN".equals(record.getQualityStatus())) {
            throw exception(ETL_PIPELINE_RUNNING);
        }
        records.deleteById(recordId);
        deleteChildren.run(); // 仅本地节点记录删除，回执和运行标识仍保留。
    }

    /**
     * 删除流程。
     *
     * @param id 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePipeline(Long id) {
        EtlPipelineDO current = lockTask(id);
        if (qualityBatches.countPipelineEvidence(id) > 0) {
            throw exception(ETL_QUALITY_EVIDENCE_RETAINED);
        }
        if (Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(current.getStatus())) {
            throw exception(ETL_PIPELINE_RELEASED_CANNOT_MODIFY);
        }
        if (records.countSlots(id) != 0 || records.selectCount(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getPipelineId, id)
                .in(EtlRecordDO::getRunStatus, 1, 2, 6)) != 0) {
            throw exception(ETL_PIPELINE_RUNNING);
        }
        pipelines.deleteById(id);
    }

    /**
     * 重新排队ETL运行。
     *
     * @param update 更新
     * @param record 运行记录
     * @return Lambda更新条件包装器ETL 运行记录
     */
    private LambdaUpdateWrapper<EtlRecordDO> requeue(LambdaUpdateWrapper<EtlRecordDO> update, EtlRecordDO record) {
        boolean stopped = Boolean.TRUE.equals(record.getStopRequested());
        boolean exhausted = record.getAttemptCount() >= properties.getAutomaticFailureLimit();
        long delay = RunProtocol.retryDelaySeconds(record.getAttemptCount(),
                java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        update.set(EtlRecordDO::getExecutionToken, null).set(EtlRecordDO::getLeaseOwner, null)
                .set(EtlRecordDO::getLeaseUntil, null).set(EtlRecordDO::getOccupiesSlot, !stopped && exhausted)
                .set(EtlRecordDO::getEffectState, RunProtocol.Effect.NONE.name())
                .set(EtlRecordDO::getReviewRequired, !stopped && exhausted)
                .set(EtlRecordDO::getRunStatus, stopped ? EtlRecordDO.RUN_STATUS_STOPPED
                        : exhausted ? EtlRecordDO.RUN_STATUS_FAILED : EtlRecordDO.RUN_STATUS_WAITING)
                .setSql("row_version=row_version+1")
                .eq(EtlRecordDO::getExecutionStage, RunProtocol.Stage.PREPARE.name());
        if (stopped || exhausted) {
            update.set(EtlRecordDO::getNextAttemptAt, null).setSql("end_time=clock_timestamp()");
        } else {
            update.setSql("next_attempt_at=clock_timestamp()+({0} * interval '1 second')", delay);
        }
        return update;
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return Lambda更新条件包装器ETL 运行记录
     */
    private LambdaUpdateWrapper<EtlRecordDO> owned(Long id, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("execution token is required");
        }
        return Wrappers.lambdaUpdate(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, id)
                .eq(EtlRecordDO::getProtocolVersion, 1).eq(EtlRecordDO::getExecutionToken, token)
                .eq(EtlRecordDO::getOccupiesSlot, true).in(EtlRecordDO::getRunStatus, 1, 2)
                .apply("lease_until > clock_timestamp()");
    }

    /**
     * 锁定运行。
     *
     * @param recordId 记录编号
     * @return ETL 运行记录
     */
    private EtlRecordDO lockRun(Long recordId) {
        EtlRecordDO observed = records.selectOne(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId));
        if (observed == null) {
            return null;
        }
        lockTask(observed.getPipelineId());
        return records.selectOne(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getId, recordId).last("FOR UPDATE"));
    }

    /**
     * 按冻结定义受理当前调度时刻的运行，并推进下一计划时间。
     *
     * @param observed 先前读取的状态
     * @param prepared 已准备的执行配置或记录
     * @return 运行回执
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<RunReceipt> enqueueScheduled(EtlPipelineDO observed, EtlRecordDO prepared) {
        EtlPipelineDO current = lockTask(observed.getId());
        LocalDateTime scheduled = observed.getNextRunTime();
        if (scheduled == null || observed.getScheduleGeneration() == null) {
            return Optional.empty();
        }
        String key = "scheduled:" + current.getId() + ":" + observed.getScheduleGeneration() + ":" + scheduled;
        String hash = requestHash("SCHEDULED", current.getId(), prepared.getExecutionUserId(), key);
        Optional<RunReceipt> replay = replay(key, hash);
        if (replay.isPresent()) {
            return replay;
        }
        if (!Objects.equals(current.getStatus(), EtlPipelineDO.STATUS_RELEASED)
                || !Objects.equals(current.getScheduleStatus(), EtlPipelineDO.SCHEDULE_ON)
                || !Objects.equals(current.getNextRunTime(), scheduled)
                || !Objects.equals(current.getScheduleGeneration(), observed.getScheduleGeneration())
                || !sameDefinition(current, observed)) {
            return Optional.empty();
        }
        if (scheduled.isAfter(records.databaseNow()) || pending(current.getId()) >= properties.getPerTaskPending()) {
            return Optional.empty();
        }
        String cron = JSONUtil.parseObj(current.getPublishedConfigJson()).getStr("cron");
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            throw exception(ETL_PIPELINE_CRON_INVALID);
        }
        // 必须从本次原计划时间推进；不能用当前时间跳过停机期间的周期。
        LocalDateTime next = CronExpression.parse(cron).next(scheduled);
        RunReceipt receipt = insert(current, prepared, key, hash, EtlRecordDO.EXECUTE_TYPE_AUTO, scheduled);
        int advanced = pipelines.update(null, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                .set(EtlPipelineDO::getNextRunTime, next)
                .eq(EtlPipelineDO::getId, current.getId())
                .eq(EtlPipelineDO::getScheduleGeneration, observed.getScheduleGeneration())
                .eq(EtlPipelineDO::getNextRunTime, scheduled));
        if (advanced != 1) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        return Optional.of(receipt);
    }

    /**
     * 入队手工。
     *
     * @param observed 先前读取的状态
     * @param prepared 已准备的执行配置或记录
     * @param requestId 请求编号
     * @return 运行回执
     */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt enqueueManual(EtlPipelineDO observed, EtlRecordDO prepared, String requestId) {
        return enqueueManual(observed, prepared, requestId, null);
    }

    /**
     * 入队手工。
     *
     * @param observed 先前读取的状态
     * @param prepared 已准备的执行配置或记录
     * @param requestId 请求编号
     * @param inputFingerprint 输入指纹
     * @return 运行回执
     */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt enqueueManual(EtlPipelineDO observed, EtlRecordDO prepared, String requestId,
            String inputFingerprint) {
        return enqueueUnscheduled(observed, prepared, requestId, EtlRecordDO.EXECUTE_TYPE_HAND, inputFingerprint);
    }

    /**
     * 入队自动。
     *
     * @param observed 先前读取的状态
     * @param prepared 已准备的执行配置或记录
     * @param requestId 请求编号
     * @return 运行回执
     */
    @Transactional(rollbackFor = Exception.class)
    public RunReceipt enqueueAutomatic(EtlPipelineDO observed, EtlRecordDO prepared, String requestId) {
        return enqueueUnscheduled(observed, prepared, requestId, EtlRecordDO.EXECUTE_TYPE_AUTO, null);
    }

    /**
     * 入队非定时。
     *
     * @param observed 先前读取的状态
     * @param prepared 已准备的执行配置或记录
     * @param requestId 请求编号
     * @param executeType 执行类型
     * @param inputFingerprint 输入指纹
     * @return 运行回执
     */
    private RunReceipt enqueueUnscheduled(EtlPipelineDO observed, EtlRecordDO prepared, String requestId,
            int executeType, String inputFingerprint) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("requestId must contain 1..128 characters");
        }
        EtlPipelineDO current = lockTask(observed.getId());
        String action = executeType == EtlRecordDO.EXECUTE_TYPE_HAND ? "manual" : "automatic";
        String key = action + ":" + current.getId() + ":" + requestId;
        String hash = requestHash(action, current.getId(), prepared.getExecutionUserId(), key, inputFingerprint);
        Optional<RunReceipt> replay = replay(key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!sameDefinition(current, observed)) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        if (pending(current.getId()) >= properties.getPerTaskPending()) {
            throw exception(ETL_RUN_PENDING_LIMIT);
        }
        return insert(current, prepared, key, hash, executeType, null);
    }

    /**
     * 锁定ETL运行的任务。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    private EtlPipelineDO lockTask(Long id) {
        EtlPipelineDO current = pipelines.selectOne(Wrappers.lambdaQuery(EtlPipelineDO.class)
                .eq(EtlPipelineDO::getId, id).last("FOR UPDATE"));
        if (current == null) {
            throw exception(ETL_PIPELINE_NOT_EXISTS);
        }
        return current;
    }

    /**
     * 获取ETL运行的待处理。
     *
     * @param id 记录编号
     * @return 待处理数值
     */
    private long pending(Long id) {
        return records.selectCount(Wrappers.lambdaQuery(EtlRecordDO.class)
                .eq(EtlRecordDO::getPipelineId, id)
                .eq(EtlRecordDO::getProtocolVersion, 1).eq(EtlRecordDO::getRunStatus, EtlRecordDO.RUN_STATUS_WAITING)
                .eq(EtlRecordDO::getOccupiesSlot, false));
    }

    /**
     * 重放ETL运行。
     *
     * @param key 标识键
     * @param hash 摘要
     * @return 运行回执
     */
    private Optional<RunReceipt> replay(String key, String hash) {
        // 查询包括软删除记录，避免因逻辑删除而复用历史请求标识。
        EtlRecordDO existing = records.selectIdentity(key);
        if (existing == null) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(existing.getDeleted()) || !Objects.equals(hash, existing.getRequestHash())) {
            throw exception(ETL_RUN_REQUEST_CONFLICT);
        }
        return Optional.of(new RunReceipt(existing.getId(), true, existing.getRunStatus(),
                Boolean.TRUE.equals(existing.getStopRequested())));
    }

    /**
     * 新增ETL运行记录。
     *
     * @param current 当前
     * @param prepared 已准备的执行配置或记录
     * @param key 标识键
     * @param hash 摘要
     * @param executeType 执行类型
     * @param scheduled 定时
     * @return 运行回执
     */
    private RunReceipt insert(EtlPipelineDO current, EtlRecordDO prepared, String key,
            String hash, int executeType, LocalDateTime scheduled) {
        if (!Objects.equals(current.getId(), prepared.getPipelineId()) || prepared.getExecutionUserId() == null
                || prepared.getConfigJson() == null) {
            throw new IllegalArgumentException("prepared run is incomplete");
        }
        EtlRecordDO record = EtlRecordDO.builder().pipelineId(current.getId()).name(prepared.getName())
                .pipelineRevision(prepared.getPipelineRevision()).executionUserId(prepared.getExecutionUserId())
                .qualityTaskId(prepared.getQualityTaskId()).qualityStatus("NOT_CONFIGURED")
                .configJson(prepared.getConfigJson()).resourceIds(RunResourceScope.etl(prepared.getConfigJson()))
                .orgId(current.getOrgId())
                .protocolVersion(1).runKey(key).requestHash(hash).scheduledTime(scheduled)
                .scheduleGeneration(current.getScheduleGeneration()).runStatus(EtlRecordDO.RUN_STATUS_WAITING)
                .executeType(executeType).executionStage(RunProtocol.Stage.PREPARE.name())
                .effectState(RunProtocol.Effect.NONE.name()).occupiesSlot(false).stopRequested(false)
                .reviewRequired(false).rowVersion(0L).attemptCount(0).reconcileAttempts(0).build();

        records.insert(record);
        return new RunReceipt(record.getId(), false, record.getRunStatus(), false);
    }

    /**
     * 判断是否一致定义条件。
     *
     * @param current 当前
     * @param observed 先前读取的状态
     * @return 判断结果
     */
    private boolean sameDefinition(EtlPipelineDO current, EtlPipelineDO observed) {
        if (!Objects.equals(current.getStatus(), observed.getStatus())) {
            return false;
        }
        if (Objects.equals(current.getStatus(), EtlPipelineDO.STATUS_RELEASED)) {
            return current.getPublishedConfigJson() != null
                    && Objects.equals(current.getPublishedRevision(), observed.getPublishedRevision())
                    && Objects.equals(current.getPublishedExecutionUserId(), observed.getPublishedExecutionUserId())
                    && Objects.equals(current.getPublishedConfigJson(), observed.getPublishedConfigJson());
        }
        return Objects.equals(current.getRevision(), observed.getRevision());
    }

    /**
     * 计算请求内容的稳定摘要，供幂等冲突校验使用。
     *
     * @param action 待执行的操作
     * @param taskId 任务编号
     * @param userId 用户编号
     * @param key 标识键
     * @return 计算后的摘要或签名字符串
     */
    private static String requestHash(String action, Long taskId, Long userId, String key) {
        return requestHash(action, taskId, userId, key, null);
    }

    /**
     * 计算请求内容的稳定摘要，供幂等冲突校验使用。
     *
     * @param action 待执行的操作
     * @param taskId 任务编号
     * @param userId 用户编号
     * @param key 标识键
     * @param inputFingerprint 输入指纹
     * @return 计算后的摘要或签名字符串
     */
    private static String requestHash(String action, Long taskId, Long userId, String key, String inputFingerprint) {
        if (userId == null) {
            throw new IllegalArgumentException("execution user is required");
        }
        String identity = action + "\n" + taskId + "\n" + userId + "\n" + key;
        return DigestUtil.sha256Hex(StrUtil.isBlank(inputFingerprint) ? identity : identity + "\n" + inputFingerprint);
    }

    /** 只对首次退出确认取持久时间；核查已经停止的部分产出不重复计时。 */
    private void observeStop(EtlRecordDO before) {
        if (!Boolean.TRUE.equals(before.getStopRequested())
                || !java.util.List.of(1, 2, EtlRecordDO.RUN_STATUS_UNKNOWN).contains(before.getRunStatus())) {
            return;
        }
        metrics.stopConfirmed(ETL, () -> records.selectStopTiming(before.getId(), before.getRowVersion() + 1));
    }

}
