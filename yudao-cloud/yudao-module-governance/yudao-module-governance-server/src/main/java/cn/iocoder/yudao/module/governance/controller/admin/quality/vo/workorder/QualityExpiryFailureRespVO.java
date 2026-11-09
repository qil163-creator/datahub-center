package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 例外到期处理失败账本 Response VO")
@Data
public class QualityExpiryFailureRespVO {

    @Schema(description = "账本编号", example = "1")
    private Long id;

    @Schema(description = "例外提交编号", example = "11")
    private Long submissionId;

    @Schema(description = "原工单编号", example = "8")
    private Long workOrderId;

    @Schema(description = "原工单名称", example = "客户表空值整改")
    private String workOrderName;

    @Schema(description = "状态：RETRYING / EXHAUSTED / RESOLVED", example = "EXHAUSTED")
    private String status;

    @Schema(description = "已自动处理失败次数", example = "5")
    private Integer attemptCount;

    @Schema(description = "最大自动处理次数", example = "5")
    private Integer maxAttempts;

    @Schema(description = "最近一次失败的安全摘要")
    private String lastError;

    private LocalDateTime firstFailedAt;

    private LocalDateTime lastFailedAt;

    @Schema(description = "下次允许自动处理的时间")
    private LocalDateTime nextAttemptAt;

    @Schema(description = "处置动作：SUCCEEDED 自动成功 / RETRY_AUTHORIZED 人工授权重试")
    private String resolutionAction;

    private String resolutionReason;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    @Schema(description = "CAS 版本，授权重试时原样回传", example = "3")
    private Integer version;
}
