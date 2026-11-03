package cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据资产类目管理列表 Request VO")
@Data
public class CategoryDataAssetListReqVO {

    @Schema(description = "类目名称", example = "张三")
    private String categoryName;

    @Schema(description = "状态", example = "2")
    private Short status;

}