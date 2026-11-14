package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 数据填报-填报表校验规则新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报表校验规则新增/修改 Request VO")
@Data
public class FillTableRuleSaveReqVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "填报表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "填报表编号不能为空")
    private Long tableId;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "分项之和等于合计")
    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称不能超过 100 个字符")
    private String name;

    @Schema(description = "范围：ROW-行级 TABLE-表级 PERIOD-跨期", requiredMode = Schema.RequiredMode.REQUIRED, example = "ROW")
    @NotBlank(message = "规则范围不能为空")
    @Pattern(regexp = "^(ROW|TABLE|PERIOD)$", message = "规则范围不合法")
    private String scope;

    @Schema(description = "规则表达式（结果为假即违反）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "total = item_a + item_b")
    @NotBlank(message = "规则表达式不能为空")
    @Size(max = 1000, message = "规则表达式不能超过 1000 个字符")
    private String expression;

    @Schema(description = "违反时的提示语", requiredMode = Schema.RequiredMode.REQUIRED, example = "合计应等于各分项之和")
    @NotBlank(message = "提示语不能为空")
    @Size(max = 200, message = "提示语不能超过 200 个字符")
    private String message;

    @Schema(description = "级别：1-错误（阻断提交） 2-警告", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "规则级别不能为空")
    @jakarta.validation.constraints.Min(value = 1, message = "规则级别不合法")
    @jakarta.validation.constraints.Max(value = 2, message = "规则级别不合法")
    private Integer severity;

    @Schema(description = "问题定位字段码（行级规则高亮该单元格）", example = "total")
    @Size(max = 64, message = "定位字段码不能超过 64 个字符")
    private String fieldCode;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "是否启用：0-否 1-是", example = "1")
    private Integer enabled;

}
