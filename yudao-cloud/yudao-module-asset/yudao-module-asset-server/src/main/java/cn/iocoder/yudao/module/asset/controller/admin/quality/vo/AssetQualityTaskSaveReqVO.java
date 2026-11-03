package cn.iocoder.yudao.module.asset.controller.admin.quality.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetEvaluationDTO;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 资产质量任务新增/修改 Request VO")
@Data
public class AssetQualityTaskSaveReqVO {

    private Long id;

    @NotNull(message = "资产编号不能为空")
    private Long assetId;

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 200, message = "任务名称不能超过 200 个字符")
    private String taskName;

    @NotNull(message = "调度类型不能为空")
    @Min(0)
    @Max(1)
    private Integer scheduleType;

    private String cronExpression;

    @NotNull(message = "启用状态不能为空")
    @Min(0)
    @Max(1)
    private Integer enabled;

    private String remark;

    @Valid
    @Size(max = 300)
    private List<Rule> rules;

    @Valid
    @Size(max = 100)
    private List<QualityAssetEvaluationDTO> evaluations;

    /**
     * 判断资产质量任务保存请求是否规则选择有效。
     */
    @AssertTrue(message = "旧质量规则与任务内评测必须选择且仅选择一种")
    public boolean isRuleSelectionValid() {
        return (rules != null && !rules.isEmpty()) != (evaluations != null && !evaluations.isEmpty());
    }

    @Data
    public static class Rule {

        private Long auditRuleId;

        @NotNull(message = "质量规则编号不能为空")
        private Long qualityRuleId;

        @NotBlank(message = "规则名称不能为空")
        private String ruleName;

        private String dimensionName;

        private Long metaColumnId;

        private String columnName;

        private String remark;

    }

}
