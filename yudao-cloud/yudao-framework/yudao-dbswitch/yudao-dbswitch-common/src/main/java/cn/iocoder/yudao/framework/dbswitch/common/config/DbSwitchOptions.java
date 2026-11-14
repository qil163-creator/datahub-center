package cn.iocoder.yudao.framework.dbswitch.common.config;

/** 由应用上下文或独立运行时持有的不可变配置。 */
public record DbSwitchOptions(long maxCacheBytes, boolean pipelineWriteEnabled,
                              boolean olapStreamLoadEnabled, int queryTimeoutSeconds, int poolMinimumIdle,
                              long poolIdleTimeoutMs, long poolValidationTimeoutMs, long poolLeakDetectionThresholdMs,
                              DiffSortLimits diffSort) {

    /**
     * 校验构造参数并初始化 {@code DbSwitchOptions}。
     */
    public DbSwitchOptions {
        if (maxCacheBytes < 1 || queryTimeoutSeconds < 1 || poolMinimumIdle < 0
                || poolIdleTimeoutMs < 0 || poolValidationTimeoutMs < 250
                || poolLeakDetectionThresholdMs < 0 || diffSort == null) {
            throw new IllegalArgumentException("Invalid DB Switch runtime limits");
        }
    }

    /** 兼容构造方法，供使用默认有界比对暂存区的调用方使用。 */
    public DbSwitchOptions(long maxCacheBytes, boolean pipelineWriteEnabled, boolean olapStreamLoadEnabled,
            int queryTimeoutSeconds, int poolMinimumIdle, long poolIdleTimeoutMs,
            long poolValidationTimeoutMs, long poolLeakDetectionThresholdMs) {
        this(maxCacheBytes, pipelineWriteEnabled, olapStreamLoadEnabled, queryTimeoutSeconds, poolMinimumIdle,
                poolIdleTimeoutMs, poolValidationTimeoutMs, poolLeakDetectionThresholdMs, DiffSortLimits.defaults());
    }

    /**
     * 创建当前组件的默认配置。
     */
    public static DbSwitchOptions defaults() {
        return new DbSwitchOptions(128L * 1024 * 1024, true, true, 3600, 0, 300_000, 30_000, 0);
    }

    /** 为单次操作显式选择 OLAP 传输，其余限额保持不变。 */
    public DbSwitchOptions withOlapStreamLoad(boolean enabled) {
        return new DbSwitchOptions(maxCacheBytes, pipelineWriteEnabled, enabled, queryTimeoutSeconds,
                poolMinimumIdle, poolIdleTimeoutMs, poolValidationTimeoutMs, poolLeakDetectionThresholdMs, diffSort);
    }
}
