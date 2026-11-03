package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 清洗规则在线测试 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 清洗规则在线测试 Request VO")
@Data
public class CleanRuleTestReqVO {

    @Schema(description = "规则编号（与 strategyKey 二选一）", example = "9")
    private Long ruleId;

    @Schema(description = "策略标识（与 ruleId 二选一）", example = "REMOVE_WHITESPACE")
    private String strategyKey;

    @Schema(description = "规则配置 JSON", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"columns\": [\"name\"], \"handleType\": 1}")
    @NotBlank(message = "规则配置不能为空")
    private String configJson;

    @Schema(description = "样例数据 JSON 数组", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[{\"name\": \" 张三 \"}]")
    @NotBlank(message = "样例数据不能为空")
    private String rowsJson;

}
