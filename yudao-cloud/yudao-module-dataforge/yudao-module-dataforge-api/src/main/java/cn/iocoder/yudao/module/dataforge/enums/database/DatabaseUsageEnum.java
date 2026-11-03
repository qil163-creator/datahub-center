package cn.iocoder.yudao.module.dataforge.enums.database;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据源用途枚举：区分业务库（来源系统库）与中台库（数仓落地库）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum DatabaseUsageEnum {

    BUSINESS(1, "业务库"),
    PLATFORM(2, "中台库");

    private final Integer type;

    private final String name;

}
