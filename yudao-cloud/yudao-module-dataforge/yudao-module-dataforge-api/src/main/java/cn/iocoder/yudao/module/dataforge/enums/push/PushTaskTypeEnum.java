package cn.iocoder.yudao.module.dataforge.enums.push;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据推送调度类型
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum PushTaskTypeEnum {

    ONCE(1, "一次性"),
    PERIODIC(2, "周期性");

    private final Integer type;

    private final String name;

    /**
     * 判断是否满足周期条件。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    public static boolean isPeriodic(Integer type) {
        return PERIODIC.getType().equals(type);
    }

    /**
     * 判断输入是否满足当前校验规则。
     *
     * @param type 类型标识
     * @return 判断结果
     */
    public static boolean isValid(Integer type) {
        return ONCE.getType().equals(type) || PERIODIC.getType().equals(type);
    }

}
