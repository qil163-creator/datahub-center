package cn.iocoder.yudao.framework.dbswitch.data.domain;

/**
 * 已结束的单表全量流水线事实；不是事务提交凭证、值相等证明或读副本可见性证明。
 * acknowledgedRows 仅表示写入器按接口约定返回的数量；未覆盖的增量路径/旧结果保持 null。
 * FULL_SCAN_TABLE 也可能带字段裁剪、NULL 降级、转换器、生成列或前后置脚本，消费方必须分别检查。
 */
public record TableTransferSummary(Operation operation, long readRows, long acknowledgedRows,
                                   int sourceColumns, int copiedColumns, long substitutedNullValues,
                                   boolean transformed, boolean generatedColumns, boolean customScripts,
                                   BatchCommitSummary commits) {

    public enum Operation {FULL_SCAN_TABLE, SQL_QUERY, CURSOR_UPSERT, SKIPPED_EXISTING, SKIPPED_NO_COLUMNS, DDL_ONLY}

    /**
     * 使用给定参数初始化 {@code TableTransferSummary}。
     */
    public TableTransferSummary(Operation operation, long readRows, long acknowledgedRows, int sourceColumns,
            int copiedColumns, long substitutedNullValues, boolean transformed,
            boolean generatedColumns, boolean customScripts) {
        this(operation, readRows, acknowledgedRows, sourceColumns, copiedColumns, substitutedNullValues,
                transformed, generatedColumns, customScripts, null);
    }

    /**
     * 校验构造参数并初始化 {@code TableTransferSummary}。
     */
    public TableTransferSummary {
        if (operation == null || readRows < 0 || acknowledgedRows < 0 || sourceColumns < 0
                || copiedColumns < 0 || copiedColumns > sourceColumns || substitutedNullValues < 0) {
            throw new IllegalArgumentException("Invalid table transfer summary");
        }
    }
}
