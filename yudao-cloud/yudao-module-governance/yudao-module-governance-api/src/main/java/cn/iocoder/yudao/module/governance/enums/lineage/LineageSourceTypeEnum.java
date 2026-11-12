package cn.iocoder.yudao.module.governance.enums.lineage;

import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 血缘来源类型：dataforge 中产生血缘的四类对象。
 *
 * <p>线协议值为枚举常量名（{@link #name()}），新增常量属于契约变更。</p>
 */
@Getter
@AllArgsConstructor
public enum LineageSourceTypeEnum implements ArrayValuable<String> {

    DEV_TASK("开发任务 SQL"),
    DATA_ACCESS("数据同步"),
    INTEGRATION_TASK("集成任务"),
    ETL_PIPELINE("可视化 ETL");

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
