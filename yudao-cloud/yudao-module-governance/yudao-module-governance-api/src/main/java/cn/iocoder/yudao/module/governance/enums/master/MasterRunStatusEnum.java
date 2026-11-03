package cn.iocoder.yudao.module.governance.enums.master;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 主数据派发运行状态枚举（与数据同步任务的状态值保持一致语义）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum MasterRunStatusEnum {

    WAITING(1, "等待派发"),
    RUNNING(2, "运行中"),
    SUCCESS(3, "成功"),
    FAILED(4, "失败"),
    UNKNOWN(5, "结果未知，待核查"),
    RESOLVED(6, "已人工核查处置（非成功）");

    private final Integer status;

    private final String name;

}
