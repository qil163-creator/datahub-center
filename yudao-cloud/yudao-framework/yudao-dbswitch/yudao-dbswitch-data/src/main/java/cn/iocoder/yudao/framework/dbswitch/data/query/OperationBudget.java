package cn.iocoder.yudao.framework.dbswitch.data.query;

import java.time.Duration;

/** 整个操作使用一个单调截止时间和显式的数据物化限制。 */
public record OperationBudget(Duration total, Duration idleCursor, Duration cleanupWait,
                              long maxRows, long maxBytes, long maxCellBytes, int pageRows) {

    /**
     * 校验构造参数并初始化 {@code OperationBudget}。
     */
    public OperationBudget {
        if (total == null || total.isZero() || total.isNegative() || total.compareTo(Duration.ofHours(1)) > 0
                || idleCursor == null || idleCursor.isZero() || idleCursor.isNegative() || idleCursor.compareTo(
                total) > 0
                || cleanupWait == null || cleanupWait.isNegative() || cleanupWait.isZero()
                || cleanupWait.compareTo(Duration.ofSeconds(30)) > 0
                || maxRows < 1 || maxRows > 1_000_000 || maxBytes < 1 || maxBytes > 256L * 1024 * 1024
                || maxCellBytes < 1 || maxCellBytes > maxBytes || pageRows < 1 || pageRows > 1000) {
            throw new IllegalArgumentException("Invalid query operation budget");
        }
    }

    /**
     * 创建当前组件的默认配置。
     */
    public static OperationBudget defaults() {
        return new OperationBudget(Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(5),
                100_000, 16L * 1024 * 1024, 1024L * 1024, 256);
    }
}
