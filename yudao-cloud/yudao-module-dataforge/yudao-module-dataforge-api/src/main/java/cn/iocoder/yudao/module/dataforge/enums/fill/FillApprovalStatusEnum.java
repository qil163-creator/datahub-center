package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-提交快照审批状态枚举（BPM 状态镜像，事实以 BPM 为准）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillApprovalStatusEnum {

    WAIT_START(0, "待启动"),
    APPROVING(1, "审批中"),
    APPROVED(2, "审批通过"),
    REJECTED(3, "审批驳回"),
    WITHDRAWN(4, "已撤回"),
    TERMINATED(5, "已终止");

    private final Integer status;

    private final String name;

}
