package cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 敏感清单启停")
public class AssetSensitiveStatusReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Min(0)
    private Integer revision;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
