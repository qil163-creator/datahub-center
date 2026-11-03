package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据治理-数据质量-质量规则分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 质量规则分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityRulePageReqVO extends PageParam {

    @Schema(description = "规则名称（模糊）", example = "长度")
    private String ruleName;

    @Schema(description = "规则编码（模糊）", example = "LENGTH")
    private String ruleCode;

    @Schema(description = "规则类型", example = "RANGE")
    private String ruleType;

    @Schema(description = "质量维度", example = "COMPLETENESS")
    private String qualityDim;

    @Schema(description = "严重程度", example = "MID")
    private String severity;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "规则类目编号", example = "1")
    private Long categoryId;

    @Schema(description = "来源标准数据元编号", example = "12")
    private Long standardElementId;

}
