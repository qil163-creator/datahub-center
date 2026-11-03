package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 资产关联明确 ETL 目标批次请求")
public class AssetEtlBatchBindingReqVO {

    @NotNull
    @Positive
    private Long assetId;

    @NotNull
    @Positive
    private Long batchId;

    @Min(0)
    private Integer expectedRevision;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
