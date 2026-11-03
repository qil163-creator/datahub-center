package cn.iocoder.yudao.framework.dbswitch.common.entity;

import java.util.List;

/** 水位后接稳定的主键元组，以带版本的数组形式存储。 */
public record TypedCursor(int version, List<TypedCursorValue> values) {

    /**
     * 校验构造参数并初始化 {@code TypedCursor}。
     */
    public TypedCursor {
        if (version != 1 || values == null || values.isEmpty() || values.size() > 33) {
            throw new IllegalArgumentException("Invalid typed cursor tuple");
        }
        values = List.copyOf(values);
    }
}
