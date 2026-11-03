package cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 数据资产类目管理新增/修改 Request VO")
@Data
public class CategoryDataAssetSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "7137")
    private Long id;

    @Schema(description = "父级ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "27346")
    @NotNull(message = "父级ID不能为空")
    private Long parentId;

    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    @NotEmpty(message = "类目名称不能为空")
    private String categoryName;

    @Schema(description = "层级编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "层级编码不能为空")
    private String categoryCode;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "备注", example = "你猜")
    private String remark;

}