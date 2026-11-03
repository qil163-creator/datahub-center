package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-采集周期类型枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillPeriodTypeEnum {

    ONCE(1, "一次性"),
    DAILY(2, "每日"),
    WEEKLY(3, "每周"),
    MONTHLY(4, "每月"),
    QUARTERLY(5, "每季度");

    private final Integer type;

    private final String name;

}
