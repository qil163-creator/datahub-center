package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-质量规则 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 质量规则 Response VO")
@Data
public class QualityRuleRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则类目编号", example = "1")
    private Long categoryId;

    @Schema(description = "规则名称", example = "字段长度范围校验")
    private String ruleName;

    @Schema(description = "规则编码", example = "LENGTH_VALIDATION")
    private String ruleCode;

    @Schema(description = "质量维度", example = "COMPLETENESS")
    private String qualityDim;

    @Schema(description = "检查级别", example = "FIELD")
    private String checkLevel;

    @Schema(description = "规则类型", example = "RANGE")
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

    @Schema(description = "采集问题明细：0-否 1-是", example = "1")
    private Integer collectIssueDetail;

    @Schema(description = "使用场景", example = "检查字段长度")
    private String useCase;

    @Schema(description = "示例", example = "手机号11位")
    private String example;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "是否内置：1-是 0-否", example = "0")
    private Integer builtin;

    @Schema(description = "说明", example = "检查字段长度")
    private String description;

    @Schema(description = "备注", example = "备注")
    private String remark;

    @Schema(description = "来源标准数据元编号", example = "12")
    private Long standardElementId;

    @Schema(description = "约束内容哈希", example = "ab12...")
    private String constraintHash;

    @Schema(description = "创建人", example = "1")
    private String creator;

    @Schema(description = "创建人名称", example = "张三")
    private String creatorName;

    @Schema(description = "创建时间", example = "2026-01-01 00:00:00")
    private LocalDateTime createTime;

}
