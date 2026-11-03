package cn.iocoder.yudao.module.dataforge.enums.push;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据推送方式：全量 / 增量
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum PushModeEnum {

    FULL(1, "全量"),
    INCREMENT(2, "增量");

    private final Integer mode;

    private final String name;

    /**
     * 判断是否满足增量条件。
     *
     * @param mode 处理模式
     * @return 判断结果
     */
    public static boolean isIncrement(Integer mode) {
        return INCREMENT.getMode().equals(mode);
    }

    /**
     * 判断输入是否满足当前校验规则。
     *
     * @param mode 处理模式
     * @return 判断结果
     */
    public static boolean isValid(Integer mode) {
        return FULL.getMode().equals(mode) || INCREMENT.getMode().equals(mode);
    }

}
