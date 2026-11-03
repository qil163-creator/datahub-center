package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-通知类型枚举
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillNotifyTypeEnum {

    ASSIGNED(1, "被指定为填报人"),
    TRANSFERRED(2, "填报职责转派或重新分配"),
    TASK_STARTED(3, "任务开始"),
    DEADLINE_APPROACHING(4, "临近截止"),
    OVERDUE(5, "已逾期"),
    SUBMITTED_BY_OTHER(6, "数据被其他填报人提交"),
    REJECTED(7, "审批驳回"),
    APPROVED(8, "审批通过"),
    MERGE_SUCCEEDED(9, "合并成功"),
    MERGE_FAILED(10, "合并失败"),
    URGE(11, "催办"),
    CORRECTION_CREATED(12, "数据更正开放整改");

    private final Integer type;

    private final String name;

}
