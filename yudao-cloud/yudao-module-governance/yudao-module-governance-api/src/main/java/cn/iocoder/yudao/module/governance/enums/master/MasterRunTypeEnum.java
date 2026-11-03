package cn.iocoder.yudao.module.governance.enums.master;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 主数据派发触发方式枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum MasterRunTypeEnum {

    AUTO(1, "自动调度"),
    HAND(2, "手动执行");

    private final Integer type;

    private final String name;

}
