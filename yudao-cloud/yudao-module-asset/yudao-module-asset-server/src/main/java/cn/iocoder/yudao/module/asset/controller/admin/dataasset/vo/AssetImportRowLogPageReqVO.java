package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetImportRowLogPageReqVO extends PageParam {

    @NotNull
    private Long id;

    private Long rowId;

    @Size(min = 2, max = 2)
    private List<@NotNull @Min(0) @Max(253402300799999L) Long> createTime;

    @Size(max = 128)
    private String creatorName;
}
