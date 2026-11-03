package cn.iocoder.yudao.module.governance.enums.master;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 主数据派发调度类型枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum MasterTaskTypeEnum {

    /** 一次性任务：发布后立即执行一次 */
    ONCE(1, "一次性"),
    /** 周期任务：按 Cron 表达式周期执行 */
    PERIODIC(2, "周期性");

    private final Integer type;

    private final String name;

}
