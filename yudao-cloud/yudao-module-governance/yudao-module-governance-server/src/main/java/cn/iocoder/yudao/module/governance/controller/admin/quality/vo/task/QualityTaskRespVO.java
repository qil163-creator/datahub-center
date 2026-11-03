package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 质量任务 Response VO")
@Data
public class QualityTaskRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "任务编码")
    private String taskCode;

    @Schema(description = "任务分类")
    private String taskCategory;

    @Schema(description = "执行策略：SERIAL/PARALLEL")
    private String executeStrategy;

    @Schema(description = "调度类型：MANUAL/TIMER")
    private String scheduleType;

    @Schema(description = "cron表达式")
    private String cronExpression;

    @Schema(description = "时间窗")
    private String timeWindow;

    @Schema(description = "任务状态：0-上线 1-下线")
    private Integer taskStatus;

    @Schema(description = "任务优先级：HIGH/MID/LOW")
    private String priority;

    @Schema(description = "失败重试次数")
    private Integer retryCount;

    @Schema(description = "重试间隔（分钟）")
    private Integer retryInterval;

    @Schema(description = "延迟执行时间（分钟）")
    private Integer delayExecTime;

    @Schema(description = "Worker分组")
    private String workerGroup;

    @Schema(description = "关联规则集编号")
    private Long ruleSetId;

    @Schema(description = "关联规则集名称")
    private String ruleSetName;

    @Schema(description = "机构标识字段")
    private String orgField;

    @Schema(description = "通用分组字段")
    private String groupField;

    @Schema(description = "负责人用户编号")
    private Long ownerUserId;

    @Schema(description = "负责人名称")
    private String ownerUserName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "检查对象数")
    private Integer objectCount;

    @Schema(description = "覆盖规则数")
    private Integer ruleCount;

    @Schema(description = "最近运行状态")
    private String lastRunStatus;

    @Schema(description = "最近运行时间")
    private LocalDateTime lastRunTime;

    @Schema(description = "检查对象编号列表")
    private List<Long> objectIds;

    @Schema(description = "规则编号列表")
    private List<Long> ruleIds;

    private List<QualityTaskObjectSaveReqVO> objects;

    private List<QualityTaskEvaluationSaveReqVO> evaluations;

    private LocalDateTime updateTime;

    private String creatorName;

    @Schema(description = "创建者")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
