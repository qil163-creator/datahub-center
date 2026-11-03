package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "管理后台 - 历史交付实际处置：收回授权，或在证据完整时确认原批准已生效")
public record MarketDeliveryDisposalReqVO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotBlank @Size(max = 128) String operationId,
        @NotNull Action action,
        @NotBlank @Size(max = 500) String reason,
        @NotBlank @Size(max = 500) String basis,
        // 实际处置必须绑定此前已记录的核查决定：服务端复验其属于同一原操作、未被删除，
        // 并把决定中的证据快照与当前证据比对；不一致必须先重新记录核查决定。
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String basisReviewRequestId) {

    /**
     * REVOKE：按原收回链路真实撤销申请授权，冻结新的收回命令；不创建新申请。
     * CONFIRM_ORIGINAL：仅当原回执已生效、正式批准凭证匹配且申请仍有效时，把原操作账本确认为成功；不扩大授权、不重发命令。
     */
    public enum Action {REVOKE, CONFIRM_ORIGINAL}
}
