package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 稽查规则新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 稽查规则新增/修改 Request VO")
@Data
public class AuditRuleSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "字段长度范围校验")
    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称不能超过 100 个字符")
    private String name;

    @Schema(description = "稽查规则独立维度编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "稽查维度不能为空")
    private Long dimensionId;

    @Schema(description = "规则级别：1-字段级 2-表级", example = "1")
    private Integer ruleLevel;

    @Schema(description = "规则编码", example = "108")
    @Size(max = 32, message = "规则编码不能超过 32 个字符")
    private String code;

    @Schema(description = "策略标识", example = "LENGTH_VALIDATION")
    @Size(max = 64, message = "策略标识不能超过 64 个字符")
    private String strategyKey;

    @Schema(description = "规则描述", example = "检查字段长度是否在定义范围内")
    @Size(max = 500, message = "规则描述不能超过 500 个字符")
    private String description;

    @Schema(description = "使用场景", example = "检查字段长度是否在定义范围内")
    @Size(max = 500, message = "使用场景不能超过 500 个字符")
    private String useCase;

    @Schema(description = "示例", example = "手机号字段必须为11位")
    @Size(max = 2000, message = "示例不能超过 2000 个字符")
    private String example;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
