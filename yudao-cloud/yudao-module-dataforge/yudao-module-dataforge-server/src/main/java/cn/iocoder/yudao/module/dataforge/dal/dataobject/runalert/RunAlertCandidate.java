package cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 尚未登记通知的失败运行实例（扫描投影）。
 *
 * <p>接收人候选按优先级排列：负责人、发布人、本次执行身份、任务创建人；为空表示该来源没有对应字段。</p>
 */
@Data
public class RunAlertCandidate {

    /** 运行来源：DEVELOPMENT、DATA_ACCESS、ETL、INTEGRATION、JOB */
    private String sourceKind;

    /** 来源运行记录编号 */
    private Long runId;

    /** 来源任务编号 */
    private Long taskId;

    /** 任务名称 */
    private String taskName;

    /** 运行实例名称 */
    private String runName;

    /** 失败时间 */
    private LocalDateTime occurredTime;

    /** 负责人用户编号 */
    private Long ownerId;

    /** 发布人用户编号 */
    private Long releaseUserId;

    /** 本次运行的执行身份 */
    private Long executionUserId;

    /** 任务创建人用户编号（仅当 creator 为数字用户编号时） */
    private Long creatorId;

    /** ETL 运行所属的数据集成任务名称；为空表示独立的可视化 ETL */
    private String integrationName;

}
