package cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 服务分类列表 Request VO")
@Data
public class ApiCategoryListReqVO {

    @Schema(description = "数据服务类目名称（模糊）", example = "业务数据")
    private String name;

    @Schema(description = "上级类目编号", example = "0")
    private Long parentId;

}
