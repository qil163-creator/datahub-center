package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "管理后台 - 历史交付批量处置结果：逐条独立成败，互不影响")
public record MarketDeliveryDisposalBatchRespVO(List<ItemResult> items) {

    @Schema(description = "单条处置结果；失败条目只给出错误码与提示，不影响其他条目")
    public record ItemResult(
            String requestId,
            String operationId,
            boolean success,
            MarketDeliveryReviewRespVO result,
            String errorCode,
            String errorMsg) {

    }
}
