package cn.iocoder.yudao.module.dataforge.enums.push;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据推送源类型
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum PushTypeEnum {

    TABLE(1, "数据表"),
    SQL(2, "自定义 SQL");

    private final Integer type;

    private final String name;

    /**
     * 判断是否满足SQL条件。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    public static boolean isSql(Integer type) {
        return SQL.getType().equals(type);
    }

    /**
     * 判断输入是否满足当前校验规则。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    public static boolean isValid(Integer type) {
        return TABLE.getType().equals(type) || SQL.getType().equals(type);
    }

}
