package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.Objects;

/** 缺少观测结果绝不能证明数据库属性不存在。 */
public record MetadataValue<T>(State state, T value) {

    public enum State {VALUE, ABSENT, UNKNOWN, UNSUPPORTED}

    /**
     * 校验构造参数并初始化 {@code MetadataValue}。
     */
    public MetadataValue {
        Objects.requireNonNull(state, "Metadata state is required");
        if ((state == State.VALUE) != (value != null)) {
            throw new IllegalArgumentException("Only VALUE metadata must contain a value");
        }
    }

    /**
     * 返回值。
     */
    public static <T> MetadataValue<T> value(T value) {
        return new MetadataValue<>(State.VALUE, value);
    }

    /**
     * 返回当前缺失标记。
     */
    public static <T> MetadataValue<T> absent() {
        return new MetadataValue<>(State.ABSENT, null);
    }

    /**
     * 返回当前未知。
     */
    public static <T> MetadataValue<T> unknown() {
        return new MetadataValue<>(State.UNKNOWN, null);
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    public static <T> MetadataValue<T> unsupported() {
        return new MetadataValue<>(State.UNSUPPORTED, null);
    }

    /**
     * 根据已确认事实构造已知状态的元数据值。
     */
    public boolean known() {
        return state == State.VALUE || state == State.ABSENT;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "MetadataValue[state=" + state + "]";
    }
}
