package cn.iocoder.yudao.framework.dbswitch.common.config;

/** 每次运行的不可变限额。字节数表示保留值载荷的估算大小，并非精确的 JVM 堆内存计量。 */
public record BatchLimits(int maxRows, long maxBatchBytes, long maxCellBytes,
                          int maxBindParameters, long maxBatchAgeMillis) {

    /**
     * 校验构造参数并初始化 {@code BatchLimits}。
     */
    public BatchLimits {
        if (maxRows < 1 || maxBatchBytes < 1 || maxCellBytes < 1 || maxCellBytes > maxBatchBytes
                || maxBindParameters < 1 || maxBatchAgeMillis < 1) {
            throw new IllegalArgumentException("Invalid batch limits");
        }
    }

    /**
     * 创建当前组件的默认配置。
     */
    public static BatchLimits defaults() {
        return new BatchLimits(5000, 16L * 1024 * 1024, 8L * 1024 * 1024, 65535, 1000);
    }

    /** JDBC addBatch 复用单行语句，其参数上限不等于行数乘以列数。 */
    public void requireStatementParameters(int parameters) {
        if (parameters < 1 || parameters > maxBindParameters) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "Statement exceeds the binding parameter limit");
        }
    }

    /** 只有包含多个 VALUES 的 SQL 语句才会让所有行共用同一参数额度。 */
    public int maxMultiValueRows(int parametersPerRow, int fixedParameters) {
        if (parametersPerRow < 1 || fixedParameters < 0 || fixedParameters >= maxBindParameters) {
            throw new IllegalArgumentException("Invalid multi-values parameter layout");
        }
        int rows = (maxBindParameters - fixedParameters) / parametersPerRow;
        if (rows < 1) {
            throw new IllegalArgumentException("One row exceeds the binding parameter limit");
        }
        return Math.min(maxRows, rows);
    }
}
