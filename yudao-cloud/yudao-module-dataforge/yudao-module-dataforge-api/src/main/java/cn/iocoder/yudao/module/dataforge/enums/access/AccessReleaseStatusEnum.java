package cn.iocoder.yudao.module.dataforge.enums.access;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据同步-发布状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum AccessReleaseStatusEnum {

    UNRELEASED(0, "未发布"),
    RELEASED(1, "已发布");

    private final Integer status;

    private final String name;

}
