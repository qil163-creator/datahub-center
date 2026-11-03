package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 稽查规则 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 稽查规则 Response VO")
@Data
public class AuditRuleRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则名称", example = "字段长度范围校验")
    private String name;

    @Schema(description = "稽查规则独立维度编号", example = "2")
    private Long dimensionId;

    @Schema(description = "稽查规则独立维度名称", example = "完整性")
    private String dimensionName;

    @Schema(description = "规则级别：1-字段级 2-表级", example = "1")
    private Integer ruleLevel;

    @Schema(description = "规则编码", example = "108")
    private String code;

    @Schema(description = "策略标识", example = "LENGTH_VALIDATION")
    private String strategyKey;

    @Schema(description = "是否已接入质量引擎", example = "true")
    private Boolean engineSupported;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "使用场景")
    private String useCase;

    @Schema(description = "示例")
    private String example;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "创建人编号", example = "1")
    private String creator;

    @Schema(description = "创建人名称", example = "芋道")
    private String creatorName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
