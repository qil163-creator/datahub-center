package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "管理后台 - 历史交付批量处置：逐条独立执行、逐条返回结果（最多二十条）")
public record MarketDeliveryDisposalBatchReqVO(
        @NotNull @Size(min = 1, max = 20) List<@Valid Item> items) {

    /** 单条处置项；每条独立短事务、独立审计与幂等，部分成功不整体回滚。 */
    @Schema(description = "批量处置条目")
    public record Item(
            @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
            @NotBlank @Size(max = 128) String operationId,
            @NotNull MarketDeliveryDisposalReqVO.Action action,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 500) String basis,
            @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String basisReviewRequestId) {

    }
}
