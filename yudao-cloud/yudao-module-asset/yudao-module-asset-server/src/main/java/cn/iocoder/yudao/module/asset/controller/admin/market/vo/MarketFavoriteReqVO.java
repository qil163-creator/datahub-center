package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Schema(description = "管理后台 - 设置当前用户的资产收藏状态")
@Data
public class MarketFavoriteReqVO {

    @NotNull
    @Positive
    private Long assetId;

    @NotNull
    private Boolean favorite;
}
