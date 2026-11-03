package cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 标签类目列表 Request VO")
@Data
public class CategoryTagListReqVO {

    @Schema(description = "类目名称", example = "水利行业类目")
    private String categoryName;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
