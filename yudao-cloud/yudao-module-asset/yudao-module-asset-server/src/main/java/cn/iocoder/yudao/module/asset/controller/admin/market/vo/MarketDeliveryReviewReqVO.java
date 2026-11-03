package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "管理后台 - 记录交付核查结论；不恢复、撤销或重发授权")
public record MarketDeliveryReviewReqVO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotBlank @Size(max = 128) String operationId,
        @NotNull Decision decision,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 500) String basis) {

    /** 均只记录待办结论，不代表已经撤销或重新审批。 */
    public enum Decision {KEEP_PENDING, REAPPLY_REQUIRED}
}
