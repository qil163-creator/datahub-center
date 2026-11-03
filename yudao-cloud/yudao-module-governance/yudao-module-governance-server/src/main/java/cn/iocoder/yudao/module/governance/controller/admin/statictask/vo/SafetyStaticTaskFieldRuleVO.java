package cn.iocoder.yudao.module.governance.controller.admin.statictask.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 静态脱敏任务 - 字段脱敏配置项 VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 静态脱敏任务字段脱敏配置项")
@Data
public class SafetyStaticTaskFieldRuleVO {

    @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "mobile")
    @NotBlank(message = "脱敏字段名不能为空")
    private String columnName;

    @Schema(description = "脱敏规则编号（dh_governance_safety_rule.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "脱敏规则不能为空")
    private Long ruleId;

}
