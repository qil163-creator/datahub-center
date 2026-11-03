package cn.iocoder.yudao.module.governance.controller.admin.categorydataquality.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据质量类目管理列表 Request VO")
@Data
public class CategoryDataQualityListReqVO {

    @Schema(description = "类目名称", example = "芋艿")
    private String categoryName;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "状态", example = "2")
    private Short status;

}