package cn.iocoder.yudao.module.governance.controller.admin.statictask.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 静态脱敏任务执行记录 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 静态脱敏任务执行记录 Response VO")
@Data
public class SafetyStaticTaskLogRespVO {

    @Schema(description = "记录编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long taskId;

    @Schema(description = "触发方式：1-自动调度 2-手动执行", example = "2")
    private Integer runType;

    @Schema(description = "运行状态：2-运行中 3-成功 4-失败", example = "3")
    private Integer runStatus;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "处理行数", example = "10000")
    private Long dataCount;

    @Schema(description = "脱敏字段数", example = "3")
    private Integer maskedColumnCount;

    @Schema(description = "错误信息")
    private String errorInfo;

}
