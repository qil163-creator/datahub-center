package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-填报人生效状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillAssigneeStateEnum {

    INVALID(0, "已失效"),
    VALID(1, "有效");

    private final Integer state;

    private final String name;

}
