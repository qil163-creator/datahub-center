package cn.iocoder.yudao.module.governance.enums.lineage;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 字段血缘变换子类（借鉴 OpenLineage columnLineage 的 transformation subtype）。
 *
 * <p>每个子类只属于一个大类：IDENTITY/TRANSFORMATION/AGGREGATION 属于 DIRECT，
 * JOIN/FILTER/GROUP_BY/SORT/WINDOW/CONDITIONAL 属于 INDIRECT；上报时大类与子类必须匹配。</p>
 */
@Getter
@AllArgsConstructor
public enum LineageTransformSubtypeEnum implements ArrayValuable<String> {

    IDENTITY(LineageTransformTypeEnum.DIRECT, "原值透传"),
    TRANSFORMATION(LineageTransformTypeEnum.DIRECT, "表达式变换"),
    AGGREGATION(LineageTransformTypeEnum.DIRECT, "聚合计算"),
    JOIN(LineageTransformTypeEnum.INDIRECT, "关联条件"),
    FILTER(LineageTransformTypeEnum.INDIRECT, "过滤条件"),
    GROUP_BY(LineageTransformTypeEnum.INDIRECT, "分组条件"),
    SORT(LineageTransformTypeEnum.INDIRECT, "排序条件"),
    WINDOW(LineageTransformTypeEnum.INDIRECT, "窗口分区/排序"),
    CONDITIONAL(LineageTransformTypeEnum.INDIRECT, "条件分支判断");

    public static final String[] ARRAYS = Arrays.stream(values()).map(Enum::name).toArray(String[]::new);

    /**
     * 所属大类。
     */
    private final LineageTransformTypeEnum type;

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

    /**
     * 判断子类与大类是否匹配；任一非法时返回 false。
     */
    public static boolean matches(String type, String subtype) {
        if (!isValid(subtype) || !LineageTransformTypeEnum.isValid(type)) {
            return false;
        }
        return valueOf(subtype).getType().name().equals(type);
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
