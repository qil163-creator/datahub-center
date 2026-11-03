package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 需求匹配")
@Data
public class MarketDemandMatchSaveReqVO {

    @NotNull
    private Long demandId;

    @NotNull
    private Long assetId;

    @Size(max = 500)
    private String matchNote;

}
