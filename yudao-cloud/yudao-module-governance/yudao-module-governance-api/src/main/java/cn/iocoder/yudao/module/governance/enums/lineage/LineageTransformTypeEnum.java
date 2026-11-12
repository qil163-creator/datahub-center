package cn.iocoder.yudao.module.governance.enums.lineage;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 字段血缘变换大类（借鉴 OpenLineage）。
 *
 * <p>线协议值为枚举常量名（{@link #name()}），新增常量属于契约变更。</p>
 */
@Getter
@AllArgsConstructor
public enum LineageTransformTypeEnum implements ArrayValuable<String> {

    DIRECT("值直接来源于上游字段"),
    INDIRECT("上游字段只影响结果集合或顺序");

    public static final String[] ARRAYS = Arrays.stream(values()).map(Enum::name).toArray(String[]::new);

    /**
     * 中文说明。
     */
    private final String label;

    /**
     * 判断字符串是否为合法取值（大小写敏感）。
     */
    public static boolean isValid(String value) {
        return value != null && Arrays.asList(ARRAYS).contains(value);
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
