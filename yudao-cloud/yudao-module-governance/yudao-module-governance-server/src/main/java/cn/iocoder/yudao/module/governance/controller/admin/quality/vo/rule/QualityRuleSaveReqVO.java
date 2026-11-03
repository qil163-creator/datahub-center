package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 数据治理-数据质量-质量规则新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 质量规则新增/修改 Request VO")
@Data
public class QualityRuleSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则类目编号", example = "1")
    private Long categoryId;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "字段长度范围校验")
    @NotBlank(message = "规则名称不能为空")
    @Size(max = 128, message = "规则名称不能超过 128 个字符")
    private String ruleName;

    @Schema(description = "规则编码", example = "LENGTH_VALIDATION")
    @Size(max = 128, message = "规则编码不能超过 128 个字符")
    private String ruleCode;

    @Schema(description = "质量维度", requiredMode = Schema.RequiredMode.REQUIRED, example = "COMPLETENESS")
    @NotBlank(message = "质量维度不能为空")
    private String qualityDim;

    @Schema(description = "检查级别", example = "FIELD")
    private String checkLevel;

    @Schema(description = "规则类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "RANGE")
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    @Schema(description = "策略标识", example = "LENGTH_VALIDATION")
    private String strategyKey;

    @Schema(description = "参数模板", example = "{}")
    private String paramSchema;

    @Schema(description = "规则内容", example = "select * from ...")
    private String ruleContent;

    @Schema(description = "严重程度", example = "MID")
    private String severity;

    @Schema(description = "适用分层", example = "明细")
    private String applyLayer;

    @Schema(description = "采集问题明细：0-否 1-是，默认开启", example = "1")
    private Integer collectIssueDetail;

    @Schema(description = "使用场景", example = "检查字段长度")
    @Size(max = 512, message = "使用场景不能超过 512 个字符")
    private String useCase;

    @Schema(description = "示例", example = "手机号11位")
    @Size(max = 1024, message = "示例不能超过 1024 个字符")
    private String example;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "是否内置：1-是 0-否", example = "0")
    private Integer builtin;

    @Schema(description = "说明", example = "检查字段长度")
    @Size(max = 1024, message = "说明不能超过 1024 个字符")
    private String description;

    @Schema(description = "备注", example = "备注")
    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;

}
