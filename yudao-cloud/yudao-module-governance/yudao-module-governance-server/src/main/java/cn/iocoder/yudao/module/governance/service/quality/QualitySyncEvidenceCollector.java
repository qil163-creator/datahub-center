package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.dataforge.api.migration.SyncBatchEvidenceApi;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchObservationDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderSubmissionDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderRecheckMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderSubmissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskInstanceMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;

/** 每次实际质量读取前后独立采集；远程失败只保留稳定原因码，不保存异常或凭据。 */
@Component
public class QualitySyncEvidenceCollector {

    @Resource
    private SyncBatchEvidenceApi api;

    @Resource
    private QualityTaskInstanceMapper instanceMapper;

    @Resource
    private QualityWorkOrderRecheckMapper recheckMapper;

    @Resource
    private QualityWorkOrderSubmissionMapper submissionMapper;

    @Resource
    private QualityWorkOrderMapper workOrderMapper;

    public record Observation(String reason, Long executionUserId, SyncBatchObservationDTO batch,
                              SyncBoundaryDTO boundary) {

        /**
         * 使用给定原因、执行用户编号、批次初始化质量同步证据采集器。
         */
        public Observation(String reason, Long executionUserId, SyncBatchObservationDTO batch) {
            this(reason, executionUserId, batch, null);
        }
    }

    /** 独立版本；STABLE_BATCH_FACTS 仍不表示到数，不能用于 VERIFY_PASS。 */
    public record Scope(Integer schemaVersion, String reason, Observation before, Observation after) {

    }

    public record CommitBinding(Long runId, Integer attempt, Long rowVersion, Long batch) {

    }

    /** 同次质量规则快照内的诊断；对象/恢复代际及值范围未证明，不改变副本准入。 */
    public record ReadCommits(int schemaVersion, String reason, List<CommitBinding> bindings,
                              ReadSnapshot.CommitVisibility visibility) {

        /**
         * 使用给定模式版本、原因、绑定初始化质量同步证据采集器。
         */
        public ReadCommits {
            bindings = List.copyOf(bindings);
        }

        /**
         * 构造质量同步证据采集器不可用时的返回结果。
         */
        static ReadCommits unavailable(String reason) {
            return new ReadCommits(1, reason, List.of(), null);
        }
    }

    /** 仅映射已冻结的 RPC 事实；本方法不做 RPC，不实现产品 SQL。总共最多核对 16 个顶层事务。 */
    static ReadCommits inspectCommits(Observation before, ReadSnapshot snapshot) {
        if (before == null || "SOURCE_DIRECT".equals(before.reason())) {
            return null;
        }
        if (!"OBSERVED".equals(before.reason()) || before.batch() == null) {
            return ReadCommits.unavailable("BATCH_NOT_OBSERVED");
        }
        if (snapshot == null) {
            return ReadCommits.unavailable("SNAPSHOT_UNAVAILABLE");
        }
        String state = combine(before, before).reason();
        if (!Set.of("STABLE_BATCH_FACTS", "POST_SUBMISSION_BATCH_WRITE_ACKNOWLEDGED").contains(state)) {
            return ReadCommits.unavailable(state);
        }
        var receipts = new ArrayList<TransactionCommitReceipt>();
        var bindings = new ArrayList<CommitBinding>();
        try {
            for (var batch : before.batch().batches()) {
                if (before.boundary() != null && batch.startPosition() <= before.boundary().position()) {
                    continue;
                }
                var transfer = batch.transfer();
                var commits = transfer == null ? null : transfer.commits();
                if (commits == null) {
                    return ReadCommits.unavailable("COMMIT_RECEIPTS_MISSING");
                }
                if (!Integer.valueOf(1).equals(commits.schemaVersion()) || !Boolean.FALSE.equals(commits.truncated())
                        || commits.totalBatches() == null || commits.totalBatches() < 1
                        || !commits.totalBatches().equals(commits.witnessedBatches()) || commits.receipts() == null
                        || commits.totalBatches() != commits.receipts().size()) {
                    return ReadCommits.unavailable("COMMIT_RECEIPTS_INCOMPLETE");
                }
                if (commits.totalBatches() > ReadSnapshot.MAX_COMMIT_OBSERVATIONS - receipts.size()) {
                    return ReadCommits.unavailable("COMMIT_CHECK_LIMIT");
                }
                long ordinal = 0;
                for (var entry : commits.receipts()) {
                    if (entry == null || entry.batch() == null || entry.batch() != ++ordinal || entry.schemaVersion() == null) {
                        return ReadCommits.unavailable("COMMIT_RECEIPTS_INVALID");
                    }
                    receipts.add(new TransactionCommitReceipt(entry.schemaVersion(), entry.protocol(),
                            entry.transactionId(), entry.routeFingerprint(), entry.relationFingerprint()));
                    bindings.add(new CommitBinding(batch.runId(), batch.attempt(), batch.rowVersion(), entry.batch()));
                }
            }
            if (receipts.isEmpty() || new HashSet<>(receipts).size() != receipts.size()) {
                return ReadCommits.unavailable("COMMIT_RECEIPTS_INVALID");
            }
            var result = snapshot.inspectCommits(receipts);
            if (result == null || result.schemaVersion() != 1 || !snapshot.evidence().equals(result.snapshot())
                    || !receipts.equals(
                    result.observations().stream().map(ReadSnapshot.CommitObservation::receipt).toList())
                    || result.observations().stream().anyMatch(o -> !Set.of("VISIBLE", "NOT_VISIBLE", "NOT_COMMITTED",
                    "STATUS_UNAVAILABLE", "ROUTE_MISMATCH").contains(Objects.toString(o.status(), "")))) {
                return ReadCommits.unavailable("COMMIT_RESPONSE_INVALID");
            }
            return new ReadCommits(1, "OBSERVED_NOT_ARRIVAL_PROOF", bindings, result);
        } catch (java.sql.SQLFeatureNotSupportedException unsupported) {
            return ReadCommits.unavailable("COMMIT_CHECK_UNSUPPORTED");
        } catch (java.sql.SQLException unavailable) {
            return ReadCommits.unavailable("COMMIT_CHECK_UNAVAILABLE");
        } catch (RuntimeException invalid) {
            return ReadCommits.unavailable("COMMIT_RECEIPTS_INVALID");
        }
    }

    public record RangeBinding(Long runId, Integer attempt, Long rowVersion) {

    }

    public record ReadRanges(int schemaVersion, String reason, List<RangeBinding> bindings,
                             ReadSnapshot.RangeVisibility visibility) {

        /**
         * 使用给定模式版本、原因、绑定初始化质量同步证据采集器。
         */
        public ReadRanges {
            bindings = List.copyOf(bindings);
        }

        /**
         * 构造质量同步证据采集器不可用时的返回结果。
         */
        static ReadRanges unavailable(String reason) {
            return new ReadRanges(1, reason, List.of(), null);
        }
    }

    /** 独立于旧版逐批诊断；缺少完整摘要不从被截断的 16 条样本推断剩余批次。 */
    static ReadRanges inspectCommitRanges(Observation before, ReadSnapshot snapshot) {
        return inspectCommitRanges(before, snapshot, null);
    }

    /**
     * 检查提交区间。
     */
    static ReadRanges inspectCommitRanges(Observation before, ReadSnapshot snapshot, String relationReference) {
        if (before == null || "SOURCE_DIRECT".equals(before.reason())) {
            return null;
        }
        if (!"OBSERVED".equals(before.reason()) || before.batch() == null) {
            return ReadRanges.unavailable("BATCH_NOT_OBSERVED");
        }
        if (snapshot == null) {
            return ReadRanges.unavailable("SNAPSHOT_UNAVAILABLE");
        }
        try {
            String state = combine(before, before).reason();
            if (!Set.of("STABLE_BATCH_FACTS", "POST_SUBMISSION_BATCH_WRITE_ACKNOWLEDGED").contains(state)) {
                return ReadRanges.unavailable(state);
            }
            var ranges = new ArrayList<CommitRangeReceipt>();
            var bindings = new ArrayList<RangeBinding>();
            for (var batch : before.batch().batches()) {
                if (before.boundary() != null && batch.startPosition() <= before.boundary().position()) {
                    continue;
                }
                if (!fullScanAcknowledged(batch)) {
                    return ReadRanges.unavailable("BATCH_TRANSFER_UNPROVEN");
                }
                var commits = batch.transfer().commits();
                if (commits == null || commits.coverage() == null) {
                    return ReadRanges.unavailable("COMMIT_RANGE_MISSING");
                }
                if (ranges.size() >= ReadSnapshot.MAX_COMMIT_RANGES) {
                    return ReadRanges.unavailable("COMMIT_RANGE_LIMIT");
                }
                if (!Integer.valueOf(1).equals(commits.schemaVersion()) || commits.totalBatches() == null
                        || commits.witnessedBatches() == null || commits.receipts() == null
                        || commits.totalBatches() < 1 || !commits.totalBatches().equals(commits.witnessedBatches())
                        || commits.receipts().size() != Math.min(commits.totalBatches(),
                        BatchCommitSummary.MAX_RECEIPTS)
                        || !Objects.equals(commits.truncated(),
                        commits.totalBatches() > BatchCommitSummary.MAX_RECEIPTS)) {
                    return ReadRanges.unavailable("COMMIT_RANGE_INVALID");
                }
                var c = commits.coverage();
                var range = new CommitRangeReceipt(c.schemaVersion(), c.protocol(), c.batchCount(),
                        c.acknowledgedRows(),
                        c.firstTransactionId(), c.lastTransactionId(), c.routeFingerprint(), c.digest(),
                        c.relationFingerprint());
                if (!Objects.equals(c.acknowledgedRows(), batch.transfer().acknowledgedRows())) {
                    return ReadRanges.unavailable("COMMIT_RANGE_INVALID");
                }
                // 协议、计数、序号、端点与样本一致性由组件契约校验，不在业务层重写数据库规则。
                new BatchCommitSummary(commits.totalBatches(), commits.witnessedBatches(), commits.receipts().stream()
                        .map(e -> new BatchCommitSummary.Entry(e.batch(),
                                new TransactionCommitReceipt(e.schemaVersion(),
                                        e.protocol(), e.transactionId(), e.routeFingerprint(),
                                        e.relationFingerprint()))).toList(), range);
                ranges.add(range);
                bindings.add(new RangeBinding(batch.runId(), batch.attempt(), batch.rowVersion()));
            }
            if (ranges.isEmpty() || new HashSet<>(ranges).size() != ranges.size()) {
                return ReadRanges.unavailable("COMMIT_RANGE_INVALID");
            }
            var result = relationReference == null ? snapshot.inspectCommitRanges(ranges)
                    : snapshot.inspectCommitRanges(ranges, relationReference);
            if (result == null || result.schemaVersion() != 1 || !snapshot.evidence().equals(result.snapshot())
                    || !ranges.equals(
                    result.observations().stream().map(ReadSnapshot.RangeObservation::receipt).toList())
                    || result.observations().stream()
                    .anyMatch(o -> !Set.of("RANGE_VISIBLE", "RANGE_UNPROVEN", "NOT_COMMITTED",
                            "STATUS_UNAVAILABLE", "ROUTE_MISMATCH").contains(Objects.toString(o.status(), "")))
                    || relationReference != null && result.observations().stream().anyMatch(o ->
                    !Set.of("MATCH", "MISMATCH", "UNPROVEN", "ROUTE_MISMATCH")
                            .contains(Objects.toString(o.relationStatus(), ""))
                            || "ROUTE_MISMATCH".equals(o.status()) != "ROUTE_MISMATCH".equals(o.relationStatus())
                            || Set.of("MATCH", "MISMATCH").contains(o.relationStatus()) && o.receipt()
                            .relationFingerprint() == null)) {
                return ReadRanges.unavailable("COMMIT_RANGE_RESPONSE_INVALID");
            }
            return new ReadRanges(1, "OBSERVED_NOT_ARRIVAL_PROOF", bindings, result);
        } catch (java.sql.SQLFeatureNotSupportedException unsupported) {
            return ReadRanges.unavailable("COMMIT_RANGE_UNSUPPORTED");
        } catch (java.sql.SQLException unavailable) {
            return ReadRanges.unavailable("COMMIT_RANGE_UNAVAILABLE");
        } catch (RuntimeException invalid) {
            return ReadRanges.unavailable("COMMIT_RANGE_INVALID");
        }
    }

    /**
     * 采集质量同步证据采集器当前数据或提交范围的观察证据。
     */
    public Observation observe(Long instanceId, Long taskId, MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object) {
        if ("THIRD_PARTY".equals(object.getDatasourceType()) && Integer.valueOf(1).equals(conn.getUsageType())) {
            return new Observation("SOURCE_DIRECT", null, null);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return new Observation("PLATFORM_TRANSACTION_ACTIVE", null, null);
        }
        try {
            var instance = instanceId == null ? null : instanceMapper.selectById(instanceId);
            if (instance == null || !Objects.equals(instance.getTaskId(), taskId)
                    || instance.getExecutionUserId() == null || instance.getExecutionUserId() <= 0) {
                return new Observation("EXECUTION_ACTOR_MISSING", null, null);
            }
            Long actor = instance.getExecutionUserId();
            SyncBoundaryDTO boundary = null;
            if ("WORK_ORDER_RECHECK".equals(instance.getRunType())) {
                String key = committedBoundaryKey(instance, taskId, object);
                if (key == null) {
                    return new Observation("SUBMISSION_BOUNDARY_CONTEXT_INVALID", actor, null);
                }
                try {
                    var receipt = api.ensureBoundary(new SyncBoundaryReqDTO(key,
                            new SyncBatchQueryReqDTO(conn.getDatabaseId(), object.getTableName(), actor)));
                    boundary = receipt == null ? null : receipt.getCheckedData();
                    if (!validBoundary(boundary, key, conn)) {
                        return new Observation("SUBMISSION_BOUNDARY_INVALID", actor, null);
                    }
                } catch (RuntimeException unavailable) {
                    // provider 可能已提交；不产生新 key，下一次同键调用既是对账也是安全重试。
                    return new Observation("SUBMISSION_BOUNDARY_UNAVAILABLE", actor, null);
                }
            }
            var result = api.observe(new SyncBatchQueryReqDTO(conn.getDatabaseId(), object.getTableName(), actor));
            var batch = result == null ? null : result.getCheckedData();
            if (!valid(batch, conn, object) || boundary != null
                    && (!Objects.equals(boundary.schema(), batch.schema()) || !Objects.equals(boundary.table(),
                    batch.table()))) {
                return new Observation("BATCH_RESPONSE_INVALID", actor, null);
            }
            return new Observation("OBSERVED", actor, batch, boundary);
        } catch (RuntimeException unavailable) {
            // 权限撤销、超时、未配置 TLS 等均不能退回旧成功或元数据时间。
            return new Observation("BATCH_QUERY_UNAVAILABLE", null, null);
        }
    }

    /** 仅读取已提交的实例→复检轮次→活动整改→工单关联；不从请求、名称或本机时间拼接身份。 */
    private String committedBoundaryKey(QualityTaskInstanceDO instance, Long taskId, QualityCheckObjectDO object) {
        var attempt = recheckMapper.selectByInstance(instance.getId());
        if (attempt == null || !Objects.equals(instance.getId(), attempt.getInstanceId())
                || !Objects.equals(instance.getExecutionUserId(), attempt.getRequestedBy())
                || !Objects.equals(instance.getRequestKey(), attempt.getRequestKey())
                || instance.getRequestKey() == null || object.getId() == null || object.getId() <= 0) {
            return null;
        }
        var submission = submissionMapper.selectById(attempt.getSubmissionId());
        var order = workOrderMapper.selectById(attempt.getWorkOrderId());
        if (submission == null || order == null || !Objects.equals(order.getTaskId(), taskId)
                || !Objects.equals(order.getCurrentSubmissionId(), submission.getId())
                || !Objects.equals(submission.getWorkOrderId(), order.getId())
                || !Objects.equals(submission.getInstanceId(), instance.getId())
                || !QualityWorkOrderSubmissionDO.STATUS_ACTIVE.equals(submission.getStatus())
                || !Set.of(QualityWorkOrderSubmissionDO.TYPE_SOURCE_FIX,
                        QualityWorkOrderSubmissionDO.TYPE_PLATFORM_REPAIR)
                .contains(Objects.toString(submission.getSubmissionType(), ""))
                || submission.getPayloadHash() == null || !submission.getPayloadHash().matches("[a-f0-9]{64}")) {
            return null;
        }
        return "quality:" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                "QUALITY_SYNC_BOUNDARY_V1\0" + order.getId() + ":" + submission.getId() + ":"
                        + submission.getPayloadHash() + ":" + object.getId());
    }

    /**
     * 判断质量同步证据采集器的边界是否有效。
     */
    private static boolean validBoundary(SyncBoundaryDTO value, String key,
            MasterDatabaseResolver.ConnectionInfo conn) {
        return value != null && Integer.valueOf(1).equals(value.schemaVersion()) && key.equals(value.requestKey())
                && Objects.equals(conn.getDatabaseId(), value.databaseId())
                && conn.getProviderConfigurationRevision() != null
                && conn.getProviderConfigurationRevision().equals(value.configurationRevision())
                && conn.getProductType().name()
                .equals(value.product()) && value.position() != null && value.position() > 0
                && value.schema() != null && value.schema().length() <= 1024
                && value.table() != null && !value.table().isBlank() && value.table().length() <= 1024;
    }

    /**
     * 合并质量同步证据采集器分段读取或提交范围的核验证据。
     */
    static Scope combine(Observation before, Observation after) {
        if (before == null || after == null) {
            return new Scope(1, "COLLECTOR_UNAVAILABLE", before, after);
        }
        if ("SOURCE_DIRECT".equals(before.reason()) && "SOURCE_DIRECT".equals(after.reason())) {
            return null;
        }
        String reason;
        if (!"OBSERVED".equals(before.reason())) {
            reason = before.reason();
        } else if (!"OBSERVED".equals(after.reason())) {
            reason = after.reason();
        } else if (!before.equals(after)) {
            reason = "BATCH_CHANGED_DURING_READ";
        } else if (before.batch().windowTruncated()) {
            reason = "BATCH_WINDOW_TRUNCATED";
        } else if (before.batch().unscopedRunsPresent()) {
            reason = "BATCH_SCOPE_UNPROVEN";
        } else if (before.batch().batches().isEmpty()) {
            reason = "BATCH_NOT_OBSERVED";
        } else if (before.batch().batches().stream().anyMatch(b -> b.occupiesSlot() || b.reviewRequired()
                || b.stopRequested() || !Integer.valueOf(3).equals(b.runStatus()) || !"COMPLETE".equals(
                b.effectState()))) {
            reason = "BATCH_RUN_UNSETTLED";
        } else if (before.batch().batches().stream().anyMatch(b -> !b.manifestComplete() || !b.tableSuccessful()
                || !"CONFIRMED".equals(b.termination()) || !Set.of("TABLE_COPY", "CHANGE_DATA").contains(b.mode())
                || !Objects.equals(b.targetRevision(), before.batch().configurationRevision()))) {
            reason = "BATCH_RESULT_UNPROVEN";
        } else if (before.boundary() != null && before.batch().batches().stream()
                .anyMatch(b -> b.startPosition() == null || b.startPosition() <= 0)) {
            reason = "BATCH_START_ORDER_UNPROVEN";
        } else if (before.boundary() != null && before.batch().batches().stream()
                .noneMatch(b -> b.startPosition() > before.boundary().position())) {
            reason = "POST_SUBMISSION_BATCH_NOT_OBSERVED";
        } else if (before.boundary() != null && before.batch().batches().stream()
                .filter(b -> b.startPosition() > before.boundary().position())
                .anyMatch(b -> !fullScanAcknowledged(b))) {
            reason = "BATCH_TRANSFER_UNPROVEN";
        } else if (before.boundary() != null) {
            // 只确认全表扫描流水线的行数回执，仍缺原生提交位置、值转换及实际读取快照的可见性。
            reason = "POST_SUBMISSION_BATCH_WRITE_ACKNOWLEDGED";
        } else {
            reason = "STABLE_BATCH_FACTS";
        }
        return new Scope(before.boundary() == null && after.boundary() == null ? 1 : 3, reason, before, after);
    }

    /**
     * 判断同步回执是否明确声明完成全量扫描。
     */
    private static boolean fullScanAcknowledged(SyncBatchObservationDTO.Batch batch) {
        var transfer = batch.transfer();
        return transfer != null && Integer.valueOf(1).equals(transfer.schemaVersion())
                && "FULL_SCAN_TABLE".equals(transfer.operation())
                && transfer.readRows() != null && transfer.readRows() >= 0
                && transfer.readRows().equals(transfer.acknowledgedRows()) && transfer.readRows().equals(batch.rows())
                && transfer.sourceColumns() != null && transfer.sourceColumns() > 0
                && transfer.sourceColumns().equals(transfer.copiedColumns())
                && Long.valueOf(0).equals(transfer.substitutedNullValues())
                && Boolean.FALSE.equals(transfer.transformed()) && Boolean.FALSE.equals(transfer.generatedColumns())
                && Boolean.FALSE.equals(transfer.customScripts());
    }

    /**
     * 判断同步读取与提交边界证据是否完整有效。
     */
    private static boolean valid(SyncBatchObservationDTO value, MasterDatabaseResolver.ConnectionInfo conn,
            QualityCheckObjectDO object) {
        if (value == null || !Integer.valueOf(1).equals(value.schemaVersion())
                || !Objects.equals(conn.getDatabaseId(), value.databaseId())
                || conn.getProviderConfigurationRevision() == null
                || !conn.getProviderConfigurationRevision().equals(value.configurationRevision())
                || !conn.getProductType().name().equals(value.product())
                || !Objects.equals(object.getTableName(), value.tableReference())
                || value.schema() == null || value.schema().length() > 1024
                || value.table() == null || value.table().isBlank() || value.table().length() > 1024
                || value.batches().size() > 20) {
            return false;
        }
        Set<Long> runs = new HashSet<>();
        for (var b : value.batches()) {
            if (b == null || b.runId() == null || b.runId() <= 0 || !runs.add(b.runId())
                    || b.attempt() == null || b.attempt() < 1 || b.rowVersion() == null || b.rowVersion() < 0
                    || b.runStatus() == null || b.runStatus() < 1 || b.runStatus() > 6
                    || b.targetRevision() == null || b.targetRevision().length() > 128
                    || b.rows() == null || b.rows() < 0
                    || b.effectState() == null || !Set.of("NONE", "COMPLETE", "PARTIAL", "UNKNOWN")
                    .contains(b.effectState())
                    || b.mode() == null || !Set.of("TABLE_COPY", "CHANGE_DATA", "CUSTOM_SQL", "DDL_ONLY")
                    .contains(b.mode())
                    || b.termination() == null || !Set.of("RUNNING", "CONFIRMED", "UNCONFIRMED")
                    .contains(b.termination())) {
                return false;
            }
        }
        return true;
    }
}
