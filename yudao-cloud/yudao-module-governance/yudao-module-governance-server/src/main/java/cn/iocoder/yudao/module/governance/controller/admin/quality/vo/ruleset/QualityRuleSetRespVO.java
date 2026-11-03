package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-规则集 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 规则集 Response VO")
@Data
public class QualityRuleSetRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则集名称", example = "数据采集质量规则集")
    private String setName;

    @Schema(description = "规则集编码", example = "COLLECT_QUALITY")
    private String setCode;

    @Schema(description = "分类", example = "数据采集质量")
    private String category;

    @Schema(description = "评估维度", example = "机构")
    private String assessDim;

    @Schema(description = "评估周期", example = "WEEK")
    private String cycle;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "描述", example = "描述")
    private String description;

    @Schema(description = "规则数量", example = "5")
    private Integer ruleCount;

    @Schema(description = "创建时间", example = "2026-01-01 00:00:00")
    private LocalDateTime createTime;

    /**
     * 规则集明细项 VO
     */
    @Schema(description = "规则集明细项 VO")
    @Data
    public static class Item {

        @Schema(description = "明细编号", example = "1")
        private Long id;

        @Schema(description = "规则ID", example = "1")
        private Long ruleId;

        @Schema(description = "规则名称", example = "非空校验")
        private String ruleName;

        @Schema(description = "规则类型", example = "NOT_NULL")
        private String ruleType;

        @Schema(description = "质量维度", example = "COMPLETENESS")
        private String qualityDim;

        @Schema(description = "权重", example = "1.00")
        private BigDecimal weight;

    }

}
