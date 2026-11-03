package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据治理-数据质量-规则集规则管理 Request VO（全量覆盖已选规则）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 规则集规则管理 Request VO")
@Data
public class QualityRuleSetItemSaveReqVO {

    @Schema(description = "规则集编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "规则集编号不能为空")
    private Long ruleSetId;

    @Schema(description = "已选规则列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "规则集至少需要一个规则")
    private List<Item> items;

    /**
     * 规则项
     */
    @Schema(description = "规则项")
    @Data
    public static class Item {

        @Schema(description = "规则ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotNull(message = "规则ID不能为空")
        private Long ruleId;

        @Schema(description = "权重", example = "1.00")
        private BigDecimal weight;

    }

}
