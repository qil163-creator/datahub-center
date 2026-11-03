package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 质量任务新增/修改 Request VO")
@Data
public class QualityTaskSaveReqVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "任务名称")
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称长度不能超过 128")
    private String taskName;

    @Schema(description = "任务编码")
    @Size(max = 128)
    private String taskCode;

    @Schema(description = "任务分类")
    @Size(max = 128)
    private String taskCategory;

    @Schema(description = "执行策略：SERIAL/PARALLEL")
    private String executeStrategy;

    @Schema(description = "调度类型：MANUAL/TIMER")
    private String scheduleType;

    @Schema(description = "cron表达式")
    @Size(max = 128)
    private String cronExpression;

    @Schema(description = "时间窗")
    @Size(max = 64)
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
    @Size(max = 64)
    private String workerGroup;

    @Schema(description = "关联规则集编号")
    private Long ruleSetId;

    @Schema(description = "机构标识字段")
    @Size(max = 64)
    private String orgField;

    @Schema(description = "通用分组字段")
    @Size(max = 64)
    private String groupField;

    @Schema(description = "负责人用户编号")
    private Long ownerUserId;

    @Schema(description = "描述")
    @Size(max = 1024)
    private String description;

    @Schema(description = "备注")
    @Size(max = 512)
    private String remark;

    @Schema(description = "检查对象编号列表")
    private List<Long> objectIds;

    @Schema(description = "显式规则编号列表；为空时采用规则集。关联元数据表的非资产任务另按已确认落标固定标准约束，执行时冻结到实例")
    private List<Long> ruleIds;

    @Valid
    @Size(max = 100)
    private List<QualityTaskObjectSaveReqVO> objects;

    @Valid
    @Size(max = 300)
    private List<QualityTaskEvaluationSaveReqVO> evaluations;

}
