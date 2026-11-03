package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncTableTransferDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncCommitReceiptsDTO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** DataForge 拥有执行配置绑定与结果快照；不实现数据库水位或把结束时间当数据版本。 */
public final class AccessSyncEvidence {

    public static final int MAX_TABLES = 2000;

    private static final int MAX_IDENTIFIER_LENGTH = 1024;

    /**
     * 限制访问同步证据工具的实例化，统一通过静态能力使用。
     */
    private AccessSyncEvidence() {
    }

    /**
     * 绑定访问同步证据。
     *
     * @param run 运行
     * @param source 来源数据
     * @param target 目标数据
     * @param sourceOptions 来源选项
     * @param targetOptions 目标选项
     * @return 同步批次证据
     */
    public static SyncBatchEvidenceDTO bind(DataAccessTaskDO run,
            AccessDatabaseResolver.ConnectionInfo source, AccessDatabaseResolver.ConnectionInfo target,
            SourceDataSourceProperties sourceOptions, TargetDataSourceProperties targetOptions) {
        String mode = Boolean.TRUE.equals(targetOptions.getOnlyCreate()) ? "DDL_ONLY"
                : !Integer.valueOf(1).equals(sourceOptions.getSourceType()) ? "CUSTOM_SQL"
                : Boolean.TRUE.equals(targetOptions.getChangeDataSync()) ? "CHANGE_DATA" : "TABLE_COPY";
        return new SyncBatchEvidenceDTO(1, run.getId(), run.getAttemptCount(),
                source.getDatabaseId(), target.getDatabaseId(), source.getConfigurationRevision(),
                target.getConfigurationRevision(),
                source.getProductType().name(), target.getProductType().name(), source.getSchema(), target.getSchema(),
                DigestUtil.sha256Hex(Objects.requireNonNull(run.getConfigJson())), mode, "RUNNING", false,
                null, null, null, List.of());
    }

    /**
     * 保存访问同步证据结束状态。
     *
     * @param binding 绑定
     * @param result 处理结果
     * @return 同步批次证据
     */
    public static SyncBatchEvidenceDTO finish(SyncBatchEvidenceDTO binding, DbSwitchResult result) {
        var tables = new ArrayList<SyncBatchEvidenceDTO.Table>();
        var targets = new HashSet<List<String>>();
        boolean complete = result != null && result.getTableResultList() != null
                && result.getTableResultList().size() <= MAX_TABLES;
        long passed = 0, failed = 0;
        // 自定义 SQL 的源表名可能含 SQL 文本，不复制进证据；DDL 也不能冒充数据同步。
        boolean tableCopy = List.of("TABLE_COPY", "CHANGE_DATA").contains(binding.mode());
        complete &= tableCopy;
        if (tableCopy && result != null && result.getTableResultList() != null) {
            int inspected = 0;
            for (var row : result.getTableResultList()) {
                if (inspected++ == MAX_TABLES) {
                    break;
                }
                if (row == null || !identifier(row.getSourceSchemaName(), true) || !identifier(row.getSourceTableName(),
                        false)
                        || !identifier(row.getTargetSchemaName(), true) || !identifier(row.getTargetTableName(), false)
                        || row.getSyncCount() == null || row.getSyncCount().get() < 0 || row.getIfSuccess() == null) {
                    complete = false;
                    continue;
                }
                if (!targets.add(List.of(row.getTargetSchemaName(), row.getTargetTableName()))) {
                    complete = false;
                }
                boolean success = row.getIfSuccess().get();
                if (success) {
                    passed++;
                } else {
                    failed++;
                }
                tables.add(new SyncBatchEvidenceDTO.Table(row.getSourceSchemaName(), row.getSourceTableName(),
                        row.getTargetSchemaName(), row.getTargetTableName(), row.getSyncCount().get(), success,
                        transfer(row.getTransferSummary())));
            }
        }
        Long total = result == null || result.getTotalTableCount() == null ? null : result.getTotalTableCount().get();
        Long successes = result == null || result.getTotalTableSuccessCount() == null ? null : result.getTotalTableSuccessCount()
                .get();
        Long failures = result == null || result.getTotalTableFailCount() == null ? null : result.getTotalTableFailCount()
                .get();
        complete &= total != null && total > 0 && total == tables.size()
                && Objects.equals(successes, passed) && Objects.equals(failures, failed)
                && result.getIfAllSuccess() != null && result.getIfAllSuccess().get() == (failed == 0)
                && result.getTermination() == MigrationTermination.CONFIRMED;
        return new SyncBatchEvidenceDTO(binding.schemaVersion(), binding.runId(), binding.attempt(),
                binding.sourceDatabaseId(), binding.targetDatabaseId(), binding.sourceRevision(),
                binding.targetRevision(),
                binding.sourceProduct(), binding.targetProduct(), binding.sourceSchema(), binding.targetSchema(),
                binding.planHash(), binding.mode(), result == null || result.getTermination() == null
                ? "UNCONFIRMED" : result.getTermination().name(), complete, total, successes, failures, tables);
    }

    /**
     * 获取访问同步证据的传输。
     *
     * @param summary 汇总
     * @return 同步表传输
     */
    private static SyncTableTransferDTO transfer(TableTransferSummary summary) {
        if (summary == null) {
            return null;
        }
        return new SyncTableTransferDTO(1, summary.operation().name(), summary.readRows(), summary.acknowledgedRows(),
                summary.sourceColumns(), summary.copiedColumns(), summary.substitutedNullValues(),
                summary.transformed(), summary.generatedColumns(), summary.customScripts(), commits(summary.commits()));
    }

    /**
     * 获取访问同步证据的提交。
     *
     * @param summary 汇总
     * @return 同步提交回执
     */
    private static SyncCommitReceiptsDTO commits(BatchCommitSummary summary) {
        if (summary == null) {
            return null;
        }
        var coverage = summary.coverage();
        return new SyncCommitReceiptsDTO(1,
                summary.totalBatches(), summary.witnessedBatches(),
                summary.receipts().size() < summary.witnessedBatches(),
                summary.receipts().stream().map(entry ->
                        new SyncCommitReceiptsDTO.Entry(entry.batch(),
                                entry.receipt().schemaVersion(), entry.receipt().protocol(),
                                entry.receipt().transactionId(),
                                entry.receipt().routeFingerprint(), entry.receipt().relationFingerprint())).toList(),
                coverage == null ? null
                        : new SyncCommitReceiptsDTO.Coverage(coverage.schemaVersion(), coverage.protocol(),
                        coverage.batchCount(),
                        coverage.acknowledgedRows(), coverage.firstTransactionId(), coverage.lastTransactionId(),
                        coverage.routeFingerprint(), coverage.digest(), coverage.relationFingerprint()));
    }

    /** 完成结果只可填充清单，不能重写执行前已持久化的身份与配置绑定。 */
    static boolean sameBinding(SyncBatchEvidenceDTO before, SyncBatchEvidenceDTO after) {
        return before != null && after != null && bindingKey(before).equals(bindingKey(after));
    }

    /**
     * 获取访问同步证据的绑定键。
     *
     * @param value 待处理的值
     * @return 访问同步证据的绑定键结果集合
     */
    private static List<Object> bindingKey(SyncBatchEvidenceDTO value) {
        return Arrays.asList(value.schemaVersion(), value.runId(), value.attempt(), value.sourceDatabaseId(),
                value.targetDatabaseId(), value.sourceRevision(), value.targetRevision(), value.sourceProduct(),
                value.targetProduct(), value.sourceSchema(), value.targetSchema(), value.planHash(), value.mode());
    }

    /**
     * 判断标识符条件是否成立。
     *
     * @param value 待处理的值
     * @param schema 结构
     * @return 处理是否成功
     */
    private static boolean identifier(String value, boolean schema) {
        return value != null && (schema || !value.isBlank()) && value.length() <= MAX_IDENTIFIER_LENGTH;
    }
}
