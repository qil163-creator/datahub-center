package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 集市资源绑定的交付端点")
@Data
public class MarketEndpointRespVO {

    private Long assetId;

    private Long apiId;

    private String name;
}
