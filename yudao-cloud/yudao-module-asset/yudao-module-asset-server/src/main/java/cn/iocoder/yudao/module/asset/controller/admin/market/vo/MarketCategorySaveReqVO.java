package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Schema(description = "管理后台 - 新增或修改集市类目")
@Data
public class MarketCategorySaveReqVO {

    @Positive
    private Long id;

    @NotNull
    @Min(0)
    private Long parentId;

    @NotBlank
    @Size(max = 50)
    private String name;

    @Size(max = 256)
    private String description;

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;

    @NotNull
    @Min(0)
    private Integer sort;
}
