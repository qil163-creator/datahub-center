package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class QualityWorkOrderNotifyRespVO {

    private Long id;

    private Long workOrderId;

    private String eventType;

    private Long receiverUserId;

    private String payloadSummary;

    private String sendStatus;

    private Integer retryCount;

    private Integer manualRetryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private String resolutionStatus;

    private Integer resolutionVersion;

    private String resolutionReason;

    private Long operatorMessageId;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    private LocalDateTime createTime;

    private List<Review> reviews;

    private Boolean reviewsTruncated;

    @Data
    public static class Review {

        private Long id;

        private String actionType;

        private Long decisionId;

        private String outcome;

        private String reason;

        private Long operatorMessageId;

        private Long actorUserId;

        private Integer resultingResolutionVersion;

        private LocalDateTime createTime;
    }
}
