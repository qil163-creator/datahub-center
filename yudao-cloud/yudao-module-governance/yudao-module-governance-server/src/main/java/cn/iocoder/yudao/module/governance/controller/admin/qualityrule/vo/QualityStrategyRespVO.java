package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 质量引擎策略 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 质量引擎策略 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityStrategyRespVO {

    @Schema(description = "策略标识", example = "LENGTH_VALIDATION")
    private String strategyKey;

    @Schema(description = "策略名称", example = "字段长度范围校验")
    private String strategyName;

    @Schema(description = "规则配置 JSON 示例", example = "{\"minLength\": 11, \"maxLength\": 11}")
    private String configExample;

}
