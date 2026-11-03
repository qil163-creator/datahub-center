package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 任务实例 Response VO")
@Data
public class QualityTaskInstanceRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "实例名称")
    private String instanceName;

    @Schema(description = "运行类型：MANUAL/SCHEDULE")
    private String runType;

    @Schema(description = "实例状态：RUNNING/SUCCESS/FAILED")
    private String instanceStatus;

    @Schema(description = "调度时间")
    private LocalDateTime scheduleTime;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "运行耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "运行次数")
    private Integer runCount;

    @Schema(description = "错误标识")
    private String errorFlag;

    @Schema(description = "执行用户")
    private String execUser;

    @Schema(description = "主机")
    private String host;

    @Schema(description = "检测总条数")
    private Long totalCount;

    @Schema(description = "通过条数")
    private Long passCount;

    @Schema(description = "失败条数")
    private Long failCount;

    @Schema(description = "通过率")
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private BigDecimal passRate;

    @Schema(description = "质量评分")
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private BigDecimal score;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "ETL 批次来源，仅由 ETL 受控批次触发的实例返回")
    private QualityTaskInstanceEtlBatchVO etlBatch;

}
