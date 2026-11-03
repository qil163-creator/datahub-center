package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportDTO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 问题工单 Response VO")
@Data
public class QualityWorkOrderRespVO {

    private Long id;

    private String orderName;

    private String orderCode;

    private Long rootOrderId;

    private Long taskId;

    private String taskName;

    private Long issueCount;

    private Long requestDeptId;

    private Long providerDeptId;

    private Long handlingDeptId;

    private Long ownerUserId;

    private String ownerUserName;

    private Long creatorUserId;

    private String creatorUserName;

    private Long confirmerUserId;

    private LocalDateTime deadline;

    private String orderStatus;

    private String responsibilityStatus;

    private Integer lockVersion;

    private Long currentSubmissionId;

    private String closeType;

    private String closeReason;

    private String confirmationPendingType;

    private String lastError;

    private String evidenceCompleteness;

    private Integer everOverdue;

    private String auditOpinion;

    private String description;

    private String sourceType;

    private InlineQualityReportDTO etlReport;

    private List<Long> issueIds;

    private List<String> allowedActions;

    private LocalDateTime createTime;

    private QualityWorkOrderSubmissionRespVO currentSubmission;

    private List<QualityWorkOrderActionRespVO> history;

    @Schema(description = "本次复检命令的固定轮次回执；重放旧命令时不随工单当前轮次改变，旧版本命令可能为空")
    private QualityWorkOrderRecheckRespVO recheckAttempt;

    @Data
    public static class QualityWorkOrderSubmissionRespVO {

        private Long id;

        private Integer revision;

        private String submissionType;

        private String content;

        private String evidenceRef;

        private Long repairOrderId;

        private String exceptionScope;

        private LocalDateTime exceptionExpireAt;

        private String status;

        private String recheckStatus;

        private Long instanceId;

        private Integer comparableFlag;

        private Long submittedBy;

        private LocalDateTime submittedAt;

        private String lastError;

        @Schema(description = "当前整改提交的复检轮次，按轮次倒序")
        private List<QualityWorkOrderRecheckRespVO> rechecks;
    }

    @Data
    public static class QualityWorkOrderRecheckRespVO {

        private Long id;

        private Integer attemptNo;

        private Long instanceId;

        private Long requestedBy;

        private LocalDateTime requestedAt;

        private String status;

        private Integer comparableFlag;

        @Schema(description = "本轮可比性判定凭证 JSON，仅包含关联标识、摘要和原因码")
        private String evidenceJson;

        private String lastError;

        private LocalDateTime finishedAt;
    }

    @Data
    public static class QualityWorkOrderActionRespVO {

        private Long id;

        private String actionType;

        private String fromStatus;

        private String toStatus;

        private Long actorUserId;

        private String reason;

        private LocalDateTime createTime;
    }

}
