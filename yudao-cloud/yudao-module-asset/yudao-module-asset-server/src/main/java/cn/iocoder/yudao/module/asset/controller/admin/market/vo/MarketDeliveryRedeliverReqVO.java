package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "管理后台 - 交付受控重投：仅限冻结命令完整匹配且动作是撤销/暂停的 MANUAL/UNKNOWN 原操作，按原 operationId 重发一次")
public record MarketDeliveryRedeliverReqVO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotBlank @Size(max = 128) String operationId,
        @NotBlank @Size(max = 500) String note,
        // 必填绑定此前记录的核查决定；服务端复验其属于同一原操作且未被删除。
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String basisReviewRequestId) {

}
