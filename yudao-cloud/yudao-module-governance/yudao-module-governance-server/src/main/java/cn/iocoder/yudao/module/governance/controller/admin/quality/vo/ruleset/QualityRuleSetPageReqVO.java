package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据治理-数据质量-规则集分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 规则集分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityRuleSetPageReqVO extends PageParam {

    @Schema(description = "规则集名称（模糊）", example = "采集质量")
    private String setName;

    @Schema(description = "分类", example = "数据采集质量")
    private String category;

    @Schema(description = "评估维度", example = "机构")
    private String assessDim;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
