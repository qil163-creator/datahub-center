package cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 业务术语类目管理列表 Request VO")
@Data
public class CategoryTermListReqVO {

    @Schema(description = "类目名称")
    private String categoryName;

    @Schema(description = "状态", example = "0")
    private Short status;

}
