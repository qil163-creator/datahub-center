package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 目录受控操作请求")
public class AssetCatalogActionReqVO {

    @NotNull
    @Positive
    @Schema(description = "资产编号")
    private Long assetId;

    @Positive
    @Schema(description = "交付编号，交付处置时必填")
    private Long deliveryId;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "操作原因")
    private String reason;

    @Schema(description = "明确拒绝后生成新版本重投；未知结果仅重查原回执")
    private Boolean retryRejected;
}
