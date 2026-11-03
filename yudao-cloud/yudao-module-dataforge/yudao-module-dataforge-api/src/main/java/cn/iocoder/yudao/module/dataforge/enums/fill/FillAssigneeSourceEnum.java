package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-填报人授权来源枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillAssigneeSourceEnum {

    PLAN_DEFAULT(1, "计划默认填报人"),
    INITIATOR(2, "发起者指定"),
    TRANSFER(3, "转派接收"),
    ADMIN_REASSIGN(4, "管理员重新分配");

    private final Integer source;

    private final String name;

}
