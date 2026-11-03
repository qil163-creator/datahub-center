package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class MarketCategoryStatusReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;
}
