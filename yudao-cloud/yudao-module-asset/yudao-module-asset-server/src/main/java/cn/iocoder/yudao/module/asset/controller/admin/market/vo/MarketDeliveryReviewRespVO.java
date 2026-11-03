package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 核查历史同时包含待办结论（KEEP_PENDING/REAPPLY_REQUIRED）与实际处置（REVOKE/CONFIRM_ORIGINAL）；
 * kind 区分二者，resultingOperationId 仅处置撤销时指向新冻结的收回操作。
 */
@Schema(description = "管理后台 - 已持久化的核查结论或处置；不是当前可访问证明")
public record MarketDeliveryReviewRespVO(
        Long id, String requestId, String operationId, Long applyId, Long sourceRevision,
        String decision, String classification, Long actorUserId, Long actorDeptId,
        String reason, String basis, LocalDateTime occurredAt,
        MarketDeliveryEvidenceRespVO.ApprovalStatus approvalStatus,
        String kind, String resultingOperationId) {

    public static final String KIND_DECISION = "DECISION";

    public static final String KIND_DISPOSAL = "DISPOSAL";
}
