package cn.iocoder.yudao.module.dataforge.enums.integration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据集成（日志）- 调度类型枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum IntegrationScheduleTypeEnum {

    MANUAL(1, "手动执行"),
    CRON(2, "周期调度");

    private final Integer type;

    private final String name;

}
