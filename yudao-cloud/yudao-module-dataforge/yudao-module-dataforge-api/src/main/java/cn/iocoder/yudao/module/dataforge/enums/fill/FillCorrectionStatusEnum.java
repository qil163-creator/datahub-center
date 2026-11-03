package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-数据更正实例状态枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillCorrectionStatusEnum {

    DRAFT(0, "草拟"),
    APPROVING(1, "审批中"),
    MERGED(2, "已合并"),
    CANCELLED(3, "已取消");

    private final Integer status;

    private final String name;

}
