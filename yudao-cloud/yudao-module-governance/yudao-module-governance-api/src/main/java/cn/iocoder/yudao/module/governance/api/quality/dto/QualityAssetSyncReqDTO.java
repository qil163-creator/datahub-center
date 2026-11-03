package cn.iocoder.yudao.module.governance.api.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 资产质量任务同步到治理质量引擎
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 资产质量任务同步请求")
@Data
public class QualityAssetSyncReqDTO {

    @Schema(description = "资产质量任务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "资产质量任务编号不能为空")
    private Long assetTaskId;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "数据源编号不能为空")
    private Long datasourceId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "表名不能为空")
    private String tableName;

    @Schema(description = "元数据表编号")
    private Long tableId;

    @Schema(description = "Cron 表达式；有值且启用时按定时任务上线")
    private String cronExpression;

    @Schema(description = "是否启用：1-启用 0-停用")
    private Integer enabled;

    @Schema(description = "负责人用户编号")
    private Long ownerUserId;

    @Schema(description = "质量规则编号列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> ruleIds;

    @Valid
    @Size(max = 100)
    private List<QualityAssetEvaluationDTO> evaluations;

    /**
     * 判断质量资产同步请求是否规则选择有效。
     */
    @AssertTrue(message = "旧质量规则与任务内评测必须选择且仅选择一种")
    public boolean isRuleSelectionValid() {
        return (ruleIds != null && !ruleIds.isEmpty()) != (evaluations != null && !evaluations.isEmpty());
    }

    @Schema(description = "同一模板多字段绑定时的稳定后缀，避免任务码去重")
    private String bindingKey;

    @Schema(description = "绑定字段名")
    private String columnName;

}
