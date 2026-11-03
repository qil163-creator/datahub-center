package cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 清洗规则 类目管理新增/修改 Request VO")
@Data
public class CategoryCleanRuleSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "16213")
    private Long id;

    @Schema(description = "父级ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "31808")
    @NotNull(message = "父级ID不能为空")
    private Long parentId;

    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    @NotEmpty(message = "类目名称不能为空")
    private String categoryName;

    @Schema(description = "层级编码（后端自动生成，无需传入）")
    private String categoryCode;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "备注", example = "你猜")
    private String remark;

}