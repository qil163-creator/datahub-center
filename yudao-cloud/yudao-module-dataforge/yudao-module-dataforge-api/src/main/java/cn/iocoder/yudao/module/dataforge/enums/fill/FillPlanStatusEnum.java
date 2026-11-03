package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-采集计划状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillPlanStatusEnum {

    DRAFT(0, "草稿"),
    ENABLED(1, "启用"),
    DISABLED(2, "停用");

    private final Integer status;

    private final String name;

}
