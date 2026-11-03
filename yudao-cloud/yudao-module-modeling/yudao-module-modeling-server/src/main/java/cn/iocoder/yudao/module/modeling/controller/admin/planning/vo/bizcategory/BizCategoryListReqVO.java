package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 业务分类列表 Request VO")
@Data
public class BizCategoryListReqVO {

    @Schema(description = "业务分类名称（模糊）", example = "水利")
    private String name;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
