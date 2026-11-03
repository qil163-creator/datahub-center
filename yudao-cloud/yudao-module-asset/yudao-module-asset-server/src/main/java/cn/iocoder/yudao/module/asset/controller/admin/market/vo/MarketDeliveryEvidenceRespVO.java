package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 不包含冻结载荷、字段名、摘要、提供方错误详情或当前可访问范围。 */
@Schema(description = "管理后台 - 原交付证据核对；只读、非审批结论，不代表当前可访问")
public record MarketDeliveryEvidenceRespVO(
        Long applyId, String operationId, Long sourceRevision, Classification classification,
        String originalAction, Integer frozenFieldCount, String providerOutcome, ApprovalStatus approvalStatus) {

    /** 与远端执行回执独立；MATCHED 也不表示当前仍有访问权限。 */
    public enum ApprovalStatus {
        MATCHED, MISSING, CONFLICT, UNVERIFIABLE, NOT_APPLICABLE;

        /**
         * 解析已保存。
         */
        public static ApprovalStatus fromStored(String value) {
            if (value == null) {
                return UNVERIFIABLE;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException unknown) {
                return UNVERIFIABLE;
            }
        }
    }

    public enum Classification {
        LEGACY_REVIEW_REQUIRED,
        FROZEN_COMMAND_UNAVAILABLE,
        FROZEN_COMMAND_CONFLICT,
        LOCAL_BINDING_CONFLICT,
        RECEIPT_UNAVAILABLE,
        RECEIPT_CONFLICT,
        RECEIPT_NOT_FOUND,
        RECEIPT_UNKNOWN,
        ORIGINAL_APPLIED,
        ORIGINAL_STALE,
        ORIGINAL_REJECTED,
        LOCAL_STATE_CHANGED
    }
}
