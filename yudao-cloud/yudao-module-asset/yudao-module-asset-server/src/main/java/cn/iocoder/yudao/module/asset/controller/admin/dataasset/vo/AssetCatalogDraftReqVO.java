package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "未挂接技术资源的政务目录草稿")
@Data
public class AssetCatalogDraftReqVO {

    @NotBlank
    @Size(max = 256)
    private String assetName;

    private Long categoryId;

    @Size(max = 2000)
    private String description;

}
