package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class MarketPublishReqVO {

    @NotNull
    @Min(0)
    @Schema(description = "预期集市展示信息版本，首次发布为零")
    private Long expectedListingRevision;

    @NotNull
    @Positive
    private Long assetId;

    @NotNull
    @Positive
    private Long marketCategoryId;

    @NotBlank
    @Size(max = 256)
    private String name;

    @Size(max = 256)
    private String description;

    @NotBlank
    @Size(max = 64)
    private String frequency;

    @NotEmpty
    @Size(max = 3)
    private List<@NotNull @Min(1) @Max(3) Integer> deliveryTypes;

    @NotNull
    @Min(0)
    private Long expectedRevision;
}
