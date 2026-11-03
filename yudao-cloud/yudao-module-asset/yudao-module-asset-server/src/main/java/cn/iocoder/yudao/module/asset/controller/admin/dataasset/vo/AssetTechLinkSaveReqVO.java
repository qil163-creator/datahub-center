package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssetTechLinkSaveReqVO {

    @NotNull
    private Long assetId;

    @NotNull
    private Long metaTableId;

    private Integer primaryFlag;

}
