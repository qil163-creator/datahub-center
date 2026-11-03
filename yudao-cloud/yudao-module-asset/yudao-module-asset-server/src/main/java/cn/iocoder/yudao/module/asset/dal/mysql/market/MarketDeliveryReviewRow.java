package cn.iocoder.yudao.module.asset.dal.mysql.market;

import lombok.Data;

import java.time.LocalDateTime;

/** 领域审计事件的核查投影；摘要与证据 JSON 仅服务端使用，不返回浏览器。 */
@Data
public class MarketDeliveryReviewRow {

    private Long id;

    private String requestId;

    private String operationId;

    private String requestHash;

    private Long applyId;

    private Long sourceRevision;

    private String decision;

    private String classification;

    private String approvalStatus;

    private Long actorUserId;

    private Long actorDeptId;

    private String reason;

    private String basis;

    private LocalDateTime occurredAt;

    private Short deleted;

    /** DELIVERY_REVIEW_DECISION 或 DELIVERY_DISPOSAL。 */
    private String eventType;

    private String resultingOperationId;
}
