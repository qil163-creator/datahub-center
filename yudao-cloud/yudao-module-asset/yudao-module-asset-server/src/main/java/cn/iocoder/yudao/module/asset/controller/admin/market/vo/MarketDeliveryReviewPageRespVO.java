package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "管理后台 - 原交付核查历史，按编号游标每页最多五十项")
public record MarketDeliveryReviewPageRespVO(List<MarketDeliveryReviewRespVO> items, Long nextId, boolean hasMore) {

}
