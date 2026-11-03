package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.math.BigInteger;
import java.util.Objects;

/** 原生自有序列定义及单独观测的位置；二者均不授予目标所有权。 */
public record ColumnSequenceMetadata(Kind kind, String sourceSchema, String sourceName, String dataType,
                                     long start, long increment, long minimum, long maximum, long cache, boolean cycle,
                                     MetadataValue<Position> position) {

    public enum Kind {IDENTITY_ALWAYS, IDENTITY_BY_DEFAULT, OWNED_DEFAULT}

    public record Position(long lastValue, boolean called) {

    }

    /**
     * 校验构造参数并初始化 {@code ColumnSequenceMetadata}。
     */
    public ColumnSequenceMetadata {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(position);
        if (sourceSchema == null || sourceSchema.isBlank() || sourceName == null || sourceName.isBlank()
                || dataType == null || !java.util.Set.of("smallint", "integer", "bigint").contains(dataType)
                || increment == 0 || minimum >= maximum || start < minimum || start > maximum || cache < 1) {
            throw new IllegalArgumentException("Invalid native sequence definition");
        }
        long typeMinimum = "smallint".equals(dataType) ? Short.MIN_VALUE : "integer".equals(
                dataType) ? Integer.MIN_VALUE : Long.MIN_VALUE;
        long typeMaximum = "smallint".equals(dataType) ? Short.MAX_VALUE : "integer".equals(
                dataType) ? Integer.MAX_VALUE : Long.MAX_VALUE;
        if (minimum < typeMinimum || maximum > typeMaximum) {
            throw new IllegalArgumentException("Sequence bounds exceed its declared data type");
        }
        if (position.state() == MetadataValue.State.VALUE
                && (position.value().lastValue() < minimum || position.value().lastValue() > maximum)) {
            throw new IllegalArgumentException("Invalid native sequence position");
        }
    }

    /**
     * 根据对象属性生成当前产品的选项 SQL。
     */
    public String optionsSql() {
        return "START WITH " + start + " INCREMENT BY " + increment + " MINVALUE " + minimum
                + " MAXVALUE " + maximum + " CACHE " + cache + (cycle ? " CYCLE" : " NO CYCLE");
    }

    /**
     * 根据对象结构生成稳定的身份描述。
     */
    public String structuralIdentity() {
        return kind + ":" + dataType + ":" + optionsSql();
    }

    /**
     * 根据当前序列事实推导下一次可观测值。
     */
    public BigInteger nextObservedValue() {
        if (position.state() != MetadataValue.State.VALUE) {
            throw new IllegalArgumentException("Source sequence position was not observed");
        }
        return BigInteger.valueOf(position.value().lastValue())
                .add(position.value().called() ? BigInteger.valueOf(increment) : BigInteger.ZERO);
    }
}
