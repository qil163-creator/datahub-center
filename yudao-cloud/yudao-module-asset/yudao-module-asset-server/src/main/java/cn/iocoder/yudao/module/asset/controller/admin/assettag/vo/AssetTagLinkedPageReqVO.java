package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetTagLinkedPageReqVO extends PageParam {

    @NotNull
    @Positive
    private Long id;

    @Size(max = 200)
    private String keyword;

    @Pattern(regexp = "assetId|createTime")
    private String sortField = "createTime";

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
