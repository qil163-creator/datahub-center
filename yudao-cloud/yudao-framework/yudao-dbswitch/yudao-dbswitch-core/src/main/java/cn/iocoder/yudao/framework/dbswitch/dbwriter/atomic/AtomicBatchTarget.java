package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 已有表及调用方配置的技术列；不接受 SQL 表达式，也不隐式 ALTER。 */
public record AtomicBatchTarget(String schema, String table, String batchColumn, String rowColumn,
                                String statusColumn) {

    /**
     * 校验构造参数并初始化 {@code AtomicBatchTarget}。
     */
    public AtomicBatchTarget {
        identifier(schema);
        identifier(table);
        identifier(batchColumn);
        identifier(rowColumn);
        identifier(statusColumn);
        if (Set.of(batchColumn, rowColumn, statusColumn).size() != 3) {
            throw new IllegalArgumentException("Technical columns must be distinct");
        }
        if (Set.of("dbswitch_batch_receipt_v1", "dbswitch_batch_head_v1", "dbswitch_batch_operation_v1")
                .contains(table)) {
            throw new IllegalArgumentException("The receipt ledger cannot be a batch target");
        }
    }

    /**
     * 检查或转换当前数据库标识符。
     */
    public static void identifier(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("A bounded physical identifier is required");
        }
    }
}
