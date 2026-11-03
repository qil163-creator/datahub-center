package cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据分类 类目管理列表 Request VO")
@Data
public class CategoryDataListReqVO {

    @Schema(description = "类目名称", example = "李四")
    private String categoryName;

    @Schema(description = "状态", example = "1")
    private Short status;

}