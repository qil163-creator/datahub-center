package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-填报人变更动作枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillAssignmentActionEnum {

    ADD(1, "新增填报人"),
    REMOVE(2, "移除填报人"),
    TRANSFER(3, "本人转派"),
    ADMIN_REASSIGN(4, "强制重新分配");

    private final Integer action;

    private final String name;

}
