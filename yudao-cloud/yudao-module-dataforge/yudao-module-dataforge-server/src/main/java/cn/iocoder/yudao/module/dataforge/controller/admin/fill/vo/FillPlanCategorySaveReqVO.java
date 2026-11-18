package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Schema(description = "管理后台 - 采集计划分类保存 Request VO")
@Data
public class FillPlanCategorySaveReqVO {

    @Schema(description = "分类编号，更新时必填", example = "1")
    @Positive(message = "分类编号必须为正整数")
    private Long id;

    @Schema(description = "父分类编号，根目录为 0", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "父分类不能为空")
    @Min(value = 0, message = "父分类编号不能为负数")
    private Long parentId;

    @Schema(description = "分类名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "基础信息")
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 100, message = "分类名称不能超过 100 个字符")
    private String name;

    @Schema(description = "同级显示顺序", example = "0")
    @Min(value = 0, message = "显示顺序不能为负数")
    @Max(value = 9999, message = "显示顺序不能超过 9999")
    private Integer sort;

    @Schema(description = "分类状态，0 启用，1 停用。创建时默认启用，更新时省略则保留原值", example = "0")
    @Min(value = 0, message = "分类状态只能为 0 或 1")
    @Max(value = 1, message = "分类状态只能为 0 或 1")
    private Integer status;

    @Schema(description = "备注。更新时省略则保留原值，传空字符串则清空")
    @Size(max = 256, message = "备注不能超过 256 个字符")
    private String remark;

}
