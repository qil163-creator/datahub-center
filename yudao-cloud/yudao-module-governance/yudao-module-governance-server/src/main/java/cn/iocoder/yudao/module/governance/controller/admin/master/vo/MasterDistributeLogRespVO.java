package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 主数据派发执行记录 Response VO")
@Data
public class MasterDistributeLogRespVO {

    private Long totalCount;

    private Long successCount;

    private Long failureCount;

    private Long unknownCount;

    private String pushScope;

    private String pushMode;

    private Integer attempts;

    @Schema(description = "可信登录上下文中的停止操作者")
    private Long stopRequestedBy;

    @Schema(description = "数据库时钟下停止命令受理时间，不代表远端已经停止")
    private LocalDateTime stopRequestedAt;

    @Schema(description = "停止原因")
    private String stopReason;

    /** 人工核查凭证不可覆盖；不等于派发成功。 */
    private String resolutionRequestId;

    private String resolutionOutcome;

    private String resolutionReason;

    private String stoppedEvidenceRef;

    private String outputEvidenceRef;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "派发任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long distributeId;

    @Schema(description = "主数据模型编号", example = "1")
    private Long masterModelId;

    @Schema(description = "触发方式：1-自动调度 2-手动执行", example = "2")
    private Integer runType;

    @Schema(description = "运行状态：1-等待派发 2-运行中 3-成功 4-失败 5-结果未知待核查 6-已人工处置（非成功）", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private Integer runStatus;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "错误信息")
    private String errorInfo;

    @Schema(description = "派发记录数", example = "1000")
    private Long dataCount;

    @Schema(description = "派发数据量", example = "12KB")
    private String byteCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
