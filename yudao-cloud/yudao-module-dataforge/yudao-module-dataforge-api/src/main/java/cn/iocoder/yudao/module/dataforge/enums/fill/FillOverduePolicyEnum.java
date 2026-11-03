package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-逾期策略枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillOverduePolicyEnum {

    SOFT(1, "软逾期（截止后仍可提交并标记逾期）"),
    HARD(2, "硬截止（截止后锁定，需发起者延期）");

    private final Integer policy;

    private final String name;

}
