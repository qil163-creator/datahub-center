package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssetCatalogVersionCheckReqVO {

    @NotNull
    private Long id;

    @NotNull
    private Integer status;

    private String checkNote;

}
