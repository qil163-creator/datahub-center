package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import java.util.Objects;

/**
 * DDL 生成的跨产品映射输入。目录为 null 时使用 {@link LogicalTypeMappings#installed()}，
 * 仅在出现跨产品原生列时解析。LOSSY 列未获接受就会被拒绝。
 */
public record TypeMappingOptions(LogicalTypeMappings mappings, LossyColumnAcceptance lossyAcceptance) {

    private static final TypeMappingOptions DEFAULTS = new TypeMappingOptions(null, LossyColumnAcceptance.rejectAll());

    /**
     * 校验构造参数并初始化 {@code TypeMappingOptions}。
     */
    public TypeMappingOptions {
        Objects.requireNonNull(lossyAcceptance, "lossyAcceptance");
    }

    /**
     * 返回默认配置。
     */
    public static TypeMappingOptions defaults() {
        return DEFAULTS;
    }

    /**
     * 根据给定参数创建 {@code TypeMappingOptions} 实例。
     */
    public static TypeMappingOptions of(LogicalTypeMappings mappings) {
        return new TypeMappingOptions(Objects.requireNonNull(mappings, "mappings"), LossyColumnAcceptance.rejectAll());
    }

    /**
     * 根据当前输入构建并返回 {@code TypeMappingOptions}。
     */
    public TypeMappingOptions withLossyAcceptance(LossyColumnAcceptance acceptance) {
        return new TypeMappingOptions(mappings, acceptance);
    }

    /**
     * 返回已经解析完成的字段或类型映射。
     */
    public LogicalTypeMappings resolvedMappings() {
        return mappings != null ? mappings : LogicalTypeMappings.installed();
    }
}
