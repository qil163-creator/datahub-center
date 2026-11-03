package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.Objects;

/** 与现有 JDBC/逻辑字段一同保留的原生列事实。标识生成方式为 ALWAYS/BY DEFAULT/AUTO_INCREMENT。 */
public record ColumnMetadataEvidence(MetadataProvenance provenance,
                                     MetadataValue<String> nativeType, MetadataValue<String> defaultExpression,
                                     MetadataValue<Boolean> nullable, MetadataValue<Integer> numericPrecision,
                                     MetadataValue<Integer> numericScale, MetadataValue<Long> characterLength,
                                     MetadataValue<String> identity, MetadataValue<String> generatedExpression,
                                     MetadataValue<String> charset, MetadataValue<String> collation,
                                     MetadataValue<ColumnSequenceMetadata> sequence) {

    /** 现有提供方没有序列依赖及位置的验证依据；绝不根据 AUTO_INCREMENT 推断。 */
    public ColumnMetadataEvidence(MetadataProvenance provenance,
            MetadataValue<String> nativeType, MetadataValue<String> defaultExpression,
            MetadataValue<Boolean> nullable, MetadataValue<Integer> numericPrecision,
            MetadataValue<Integer> numericScale, MetadataValue<Long> characterLength,
            MetadataValue<String> identity, MetadataValue<String> generatedExpression,
            MetadataValue<String> charset, MetadataValue<String> collation) {
        this(provenance, nativeType, defaultExpression, nullable, numericPrecision, numericScale, characterLength,
                identity, generatedExpression, charset, collation, MetadataValue.unknown());
    }

    /**
     * 校验构造参数并初始化 {@code ColumnMetadataEvidence}。
     */
    public ColumnMetadataEvidence {
        Objects.requireNonNull(provenance);
        Objects.requireNonNull(nativeType);
        Objects.requireNonNull(defaultExpression);
        Objects.requireNonNull(nullable);
        Objects.requireNonNull(numericPrecision);
        Objects.requireNonNull(numericScale);
        Objects.requireNonNull(characterLength);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(generatedExpression);
        Objects.requireNonNull(charset);
        Objects.requireNonNull(collation);
        Objects.requireNonNull(sequence);
    }

    /**
     * 返回当前未知。
     */
    public static ColumnMetadataEvidence unknown() {
        return new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown());
    }
}
