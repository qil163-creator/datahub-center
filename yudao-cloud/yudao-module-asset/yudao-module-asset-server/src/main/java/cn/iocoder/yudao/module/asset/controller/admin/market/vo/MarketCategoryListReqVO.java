package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class MarketCategoryListReqVO {

    @Size(max = 128)
    private String name;

    @Min(0)
    private Long parentId;

    @Min(0)
    @Max(1)
    private Short status;
}
