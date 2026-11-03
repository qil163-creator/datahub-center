package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据集市当前用户统计")
@Data
public class MarketStatisticsRespVO {

    private Long totalAssetCount;

    private Long authorizedAssetCount;

    private Long favoriteAssetCount;

    private Long applyingAssetCount;
}
