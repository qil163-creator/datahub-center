package cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 清洗规则 类目管理列表 Request VO")
@Data
public class CategoryCleanRuleListReqVO {

    @Schema(description = "类目名称", example = "张三")
    private String categoryName;

    @Schema(description = "状态", example = "1")
    private Short status;

}