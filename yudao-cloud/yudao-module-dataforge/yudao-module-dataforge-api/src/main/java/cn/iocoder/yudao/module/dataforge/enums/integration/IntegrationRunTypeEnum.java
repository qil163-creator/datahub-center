package cn.iocoder.yudao.module.dataforge.enums.integration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据集成（消息/日志）- 触发方式枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum IntegrationRunTypeEnum {

    /** 自动：日志为周期调度触发，消息为服务重启自动恢复 */
    AUTO(1, "自动"),
    MANUAL(2, "手动");

    private final Integer type;

    private final String name;

}
