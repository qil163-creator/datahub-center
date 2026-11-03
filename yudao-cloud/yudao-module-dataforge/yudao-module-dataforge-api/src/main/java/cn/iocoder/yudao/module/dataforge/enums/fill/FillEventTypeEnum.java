package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-可靠事件类型枚举（Outbox 出站动作 / Inbox 入站结果）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillEventTypeEnum {

    // ========== Outbox：出站动作 ==========
    FILL_SUBMISSION_CREATED("FILL_SUBMISSION_CREATED", "提交快照已生成"),
    FILL_BPM_START_REQUESTED("FILL_BPM_START_REQUESTED", "请求启动 BPM 审批"),
    FILL_MERGE_REQUESTED("FILL_MERGE_REQUESTED", "请求执行合并"),
    FILL_NOTIFICATION_REQUESTED("FILL_NOTIFICATION_REQUESTED", "请求发送通知"),

    // ========== Inbox：入站结果 ==========
    FILL_BPM_STARTED("FILL_BPM_STARTED", "BPM 已启动"),
    FILL_BPM_APPROVED("FILL_BPM_APPROVED", "BPM 审批通过"),
    FILL_BPM_REJECTED("FILL_BPM_REJECTED", "BPM 审批驳回"),
    FILL_BPM_WITHDRAWN("FILL_BPM_WITHDRAWN", "BPM 已撤回"),
    FILL_BPM_CANCELLED("FILL_BPM_CANCELLED", "BPM 已终止/取消"),
    FILL_MERGE_SUCCEEDED("FILL_MERGE_SUCCEEDED", "合并成功"),
    FILL_MERGE_FAILED("FILL_MERGE_FAILED", "合并失败");

    private final String type;

    private final String name;

}
