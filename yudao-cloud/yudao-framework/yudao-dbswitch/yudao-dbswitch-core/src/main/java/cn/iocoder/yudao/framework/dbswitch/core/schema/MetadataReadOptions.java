package cn.iocoder.yudao.framework.dbswitch.core.schema;

/** 结构变更观测不使用缓存，绝不共享凭据或授权作用域。 */
public record MetadataReadOptions(Freshness freshness, int timeoutSeconds) {

    public enum Freshness {REQUIRE_FRESH}

    /**
     * 校验构造参数并初始化 {@code MetadataReadOptions}。
     */
    public MetadataReadOptions {
        if (freshness != Freshness.REQUIRE_FRESH || timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Fresh metadata reads require a bounded timeout");
        }
    }

    /**
     * 重新观测目标结构并核对其指纹仍与预期一致。
     */
    public static MetadataReadOptions fresh() {
        return new MetadataReadOptions(Freshness.REQUIRE_FRESH, 10);
    }
}
