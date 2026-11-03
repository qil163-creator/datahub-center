package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "管理后台 - 人工核查未知交付结果")
public record MarketDeliveryRecheckReqVO(
        @NotBlank @Size(max = 128) String operationId,
        @NotBlank @Size(max = 500) String note) {

}
