package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 由探查结果直接映射出的质量规则建议；只展示，不自动创建任何规则。
 */
@Schema(description = "管理后台 - 数据探查规则建议 Response VO")
@Data
public class ProfileRuleSuggestionRespVO {

    @Schema(description = "字段名")
    private String columnName;

    @Schema(description = "规则类型：NOT_NULL/UNIQUE/ENUM/LENGTH")
    private String ruleType;

    @Schema(description = "稽查策略标识，如 NOT_NULL_VALIDATION")
    private String strategyKey;

    @Schema(description = "质量维度：COMPLETENESS/UNIQUENESS/NORMALIZATION")
    private String qualityDim;

    @Schema(description = "建议规则名称")
    private String ruleName;

    @Schema(description = "推荐理由（基于抽样）")
    private String reason;

    @Schema(description = "建议的策略参数（与稽查策略配置示例同形）")
    private Map<String, Object> params;

}
