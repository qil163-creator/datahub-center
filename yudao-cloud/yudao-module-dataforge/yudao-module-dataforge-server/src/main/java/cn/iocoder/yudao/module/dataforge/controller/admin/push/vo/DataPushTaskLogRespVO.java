package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据推送执行记录 Response VO")
@Data
public class DataPushTaskLogRespVO {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "推送任务编号")
    private Long pushTaskId;

    @Schema(description = "运行状态")
    private Integer runStatus;

    @Schema(description = "触发方式")
    private Integer runType;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "推出行数")
    private Long dataCount;

    @Schema(description = "过程日志")
    private String realTimeLog;

    @Schema(description = "失败信息")
    private String errorInfo;

    @Schema(description = "手动执行编号")
    private String executeNo;

    @Schema(description = "人工处置请求编号")
    private String resolutionRequestId;

    private String resolutionOutcome;

    private String resolutionReason;

    private String stoppedEvidenceRef;

    private String outputEvidenceRef;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

}
