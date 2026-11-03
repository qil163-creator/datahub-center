package cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 敏感清单创建或修改")
public class AssetSensitiveSaveReqVO {

    @Positive
    private Long id;

    @Min(0)
    private Integer revision;

    @NotNull
    @Positive
    private Long assetId;

    @NotNull
    @Positive
    private Long columnId;

    @NotNull
    @Positive
    private Long categoryId;

    @Size(max = 256)
    private String description;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status = 1;
}
