package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

@Schema(description = "管理后台 - 资产标签分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssetTagPageReqVO extends PageParam {

    @Schema(description = "标签名称，模糊匹配名称/近义词/同义词")
    private String name;

    @Schema(description = "标签类目编号，包含其全部下级")
    private Long categoryId;

    @Schema(description = "创建人用户编号")
    private Long creatorId;

    @Schema(description = "状态：0-启用 1-停用")
    private Integer status;

    @Size(max = 128)
    private String creatorName;

    @Pattern(regexp = "id|name|createTime|assetCount")
    private String sortField = "createTime";

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";

}
