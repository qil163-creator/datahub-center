package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 调用方对单个目标表及列、单次映射预览的显式决策。它是数据契约，
 * 不等于授权、恢复令牌、结构锁，也不允许接受 UNSUPPORTED 事实。
 */
public record LossyMappingAcknowledgement(ProductTypeEnum targetProduct, String targetSchema, String targetTable,
                                          String column, String mappingFingerprint, Set<String> lossCodes) {

    /**
     * 校验构造参数并初始化 {@code LossyMappingAcknowledgement}。
     */
    public LossyMappingAcknowledgement {
        Objects.requireNonNull(targetProduct, "targetProduct");
        if (targetProduct == ProductTypeEnum.UNKNOWN) {
            throw new IllegalArgumentException("Unknown target product");
        }
        requireName(targetSchema);
        requireName(targetTable);
        requireName(column);
        if (mappingFingerprint == null || !mappingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A mapping preview fingerprint is required");
        }
        lossCodes = Set.copyOf(lossCodes);
        if (lossCodes.isEmpty() || lossCodes.stream().anyMatch(code -> !code.matches("[A-Z][A-Z0-9_]{0,95}"))) {
            throw new IllegalArgumentException("Explicit loss codes are required");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code LossyMappingAcknowledgement}。
     */
    public static LossyMappingAcknowledgement forColumn(String schema, String table, ColumnTypeMapping mapping) {
        if (mapping.fidelity() != TypeFidelity.LOSSY) {
            throw new IllegalArgumentException("Only LOSSY mappings can be acknowledged");
        }
        return new LossyMappingAcknowledgement(mapping.targetProduct(), schema, table, mapping.column(),
                mapping.fingerprint(), mapping.lossCodes());
    }

    /** 拒绝重复决策，不合并；名称按精确的物理标识符比较。 */
    public static void requireUnique(Collection<LossyMappingAcknowledgement> decisions) {
        var scopes = new HashSet<TargetColumn>();
        for (var decision : decisions) {
            Objects.requireNonNull(decision, "decision");
            if (!scopes.add(new TargetColumn(decision.targetProduct, decision.targetSchema, decision.targetTable,
                    decision.column))) {
                throw new IllegalArgumentException("Duplicate type mapping acknowledgement");
            }
        }
    }

    /**
     * 读取指定操作对应的准入结果。
     */
    public static LossyColumnAcceptance acceptanceFor(Collection<LossyMappingAcknowledgement> decisions,
            ProductTypeEnum product, String schema, String table) {
        requireUnique(decisions);
        var snapshot = decisions.stream().filter(decision -> decision.targetProduct == product
                && decision.targetSchema.equals(schema) && decision.targetTable.equals(table)).toList();
        return mapping -> mapping.fidelity() == TypeFidelity.LOSSY && mapping.targetProduct() == product
                && snapshot.stream().anyMatch(decision -> decision.column.equals(mapping.column())
                && decision.mappingFingerprint.equals(mapping.fingerprint())
                && decision.lossCodes.equals(mapping.lossCodes()));
    }

    /**
     * 校验并取得名称。
     */
    private static void requireName(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A physical target identifier is required");
        }
    }

    private record TargetColumn(ProductTypeEnum product, String schema, String table, String column) {

    }
}
