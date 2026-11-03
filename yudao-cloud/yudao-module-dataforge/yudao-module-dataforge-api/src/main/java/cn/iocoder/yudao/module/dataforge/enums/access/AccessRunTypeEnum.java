package cn.iocoder.yudao.module.dataforge.enums.access;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据同步-触发方式枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum AccessRunTypeEnum {

    AUTO(1, "自动调度"),
    HAND(2, "手动执行");

    private final Integer type;

    private final String name;

}
