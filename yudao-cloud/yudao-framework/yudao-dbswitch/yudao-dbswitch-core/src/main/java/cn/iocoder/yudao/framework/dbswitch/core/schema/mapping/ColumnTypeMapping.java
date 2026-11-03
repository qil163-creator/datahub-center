package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单次跨产品映射的列级保真度。无法确定对应步骤时，{@code logicalType}/{@code targetNativeType}/{@code definition}
 * 为 null；{@code definition} 是渲染后的列子句。
 */
public record ColumnTypeMapping(String column, ProductTypeEnum sourceProduct, String sourceNativeType,
                                String logicalType,
                                ProductTypeEnum targetProduct, String targetNativeType, TypeFidelity fidelity,
                                List<TypeMappingIssue> issues, String definition) {

    /**
     * 校验构造参数并初始化 {@code ColumnTypeMapping}。
     */
    public ColumnTypeMapping {
        Objects.requireNonNull(column);
        Objects.requireNonNull(sourceProduct);
        Objects.requireNonNull(targetProduct);
        issues = List.copyOf(issues);
        if (fidelity != TypeMappingResult.fidelityOf(issues)) {
            throw new IllegalArgumentException("Fidelity disagrees with mapping issues");
        }
        if ((fidelity == TypeFidelity.UNSUPPORTED) != (definition == null)) {
            throw new IllegalArgumentException("Only mapped columns have a definition");
        }
    }

    /** UNSUPPORTED 的首个阻断项、LOSSY 的首个损失项；其他情况为 null。 */
    public String reasonCode() {
        var wanted = fidelity == TypeFidelity.UNSUPPORTED ? TypeMappingIssue.Severity.BLOCKER : TypeMappingIssue.Severity.LOSS;
        return fidelity == TypeFidelity.EXACT ? null
                : issues.stream().filter(issue -> issue.severity() == wanted).findFirst().orElseThrow().code();
    }

    /**
     * 读取或整理当前对象的编码集合。
     */
    public Set<String> codes(TypeMappingIssue.Severity severity) {
        var codes = new LinkedHashSet<String>();
        issues.stream().filter(issue -> issue.severity() == severity).forEach(issue -> codes.add(issue.code()));
        return Set.copyOf(codes);
    }

    /**
     * 返回当前类型映射包含的值域损失编码。
     */
    public Set<String> lossCodes() {
        return codes(TypeMappingIssue.Severity.LOSS);
    }

    /** 决策的带版本、长度分隔标识，包含其渲染后的约束和诊断信息。 */
    public String fingerprint() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            fingerprintPart(digest, "COLUMN_TYPE_MAPPING_V1");
            for (String part : new String[]{column, sourceProduct.name(), sourceNativeType, logicalType,
                    targetProduct.name(), targetNativeType, fidelity.name(), definition}) {
                fingerprintPart(digest, part);
            }
            issues.stream().sorted(java.util.Comparator.comparing((TypeMappingIssue issue) -> issue.severity().name())
                    .thenComparing(TypeMappingIssue::code).thenComparing(TypeMappingIssue::detail)).forEach(issue -> {
                fingerprintPart(digest, issue.severity().name());
                fingerprintPart(digest, issue.code());
                fingerprintPart(digest, issue.detail());
            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /**
     * 将当前组成部分追加到指纹计算输入。
     */
    private static void fingerprintPart(java.security.MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    /** 仅描述元数据，绝不包含行值。 */
    public String describe() {
        return column + " [" + sourceProduct + " " + sourceNativeType + " -> " + targetProduct + " "
                + (targetNativeType == null ? "<none>" : targetNativeType) + "] " + fidelity + " " + issues;
    }
}
