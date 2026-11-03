package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
public class MarketListingRespVO {

    @Schema(description = "集市展示信息版本，尚未发布时为零")
    private Long revision;

    private Long assetId;

    private Long marketCategoryId;

    private String name;

    private String description;

    private String frequency;

    private List<Integer> deliveryTypes;

    private Long expectedRevision;
}
