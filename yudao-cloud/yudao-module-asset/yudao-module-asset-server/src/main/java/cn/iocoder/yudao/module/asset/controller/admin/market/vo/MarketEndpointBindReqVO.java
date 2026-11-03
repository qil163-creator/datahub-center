package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 绑定资源可交付 API")
@Data
public class MarketEndpointBindReqVO {

    @NotNull
    private Long assetId;

    @NotNull
    private Long apiId;
}
