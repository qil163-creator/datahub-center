package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务执行实例 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 任务执行实例 Response VO")
@Data
public class DevRecordRespVO {

    private String taskType;

    private Boolean nativeProgram;

    private java.util.List<DevDependencyRespVO> dependencies;

    private String waitReason;

    private java.time.LocalDate businessDate;

    private Long versionId;

    private Long executionUserId;

    private String qualityStatus;

    private Long qualityInstanceId;

    private Long resolvedBy;

    private java.time.LocalDateTime resolvedAt;

    private String resolutionReason;

    private String lastError;

    private Boolean cancelRequested;


    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "任务名称", example = "ods_水位日汇总")
    private String taskName;

    @Schema(description = "数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "数据源名称", example = "业务库")
    private String datasourceName;

    @Schema(description = "触发方式：MANUAL/SCHEDULE", example = "MANUAL")
    private String triggerType;

    @Schema(description = "执行状态：RUNNING/SUCCESS/FAILED/STOPPED", example = "SUCCESS")
    private String status;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "执行耗时（毫秒）", example = "1280")
    private Long durationMs;

    @Schema(description = "语句总数", example = "3")
    private Integer statementTotal;

    @Schema(description = "成功语句数", example = "3")
    private Integer statementSuccess;

    @Schema(description = "累计影响行数", example = "120")
    private Long affectedRows;

    @Schema(description = "DolphinScheduler 工作流实例编号", example = "10")
    private Integer dsInstanceId;

    @Schema(description = "执行日志")
    private String log;

    @Schema(description = "执行人", example = "1")
    private String creator;

}
