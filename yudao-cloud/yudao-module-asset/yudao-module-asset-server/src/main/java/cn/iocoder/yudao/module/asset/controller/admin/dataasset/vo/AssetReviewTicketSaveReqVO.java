package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AssetReviewTicketSaveReqVO {

    @NotNull
    private Long assetId;

    private Long catalogVersionId;

    @NotNull
    private Integer issueType;

    @Size(max = 2000)
    private String evidence;

}
