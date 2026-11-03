package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetRegistrationCandidatePageReqVO extends DataAssetCandidatePageReqVO {

    @NotNull
    @Min(1)
    @Max(3)
    private Integer assetType;
}
