package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台 - 密钥中心状态 Response VO。
 */
@Schema(description = "管理后台 - 密钥中心状态 Response VO")
@Data
public class SafetyKeyCenterStatusRespVO {

    @Schema(description = "主密钥是否已配置可用")
    private Boolean available;

    @Schema(description = "当前主密钥版本；未配置时为空", example = "1")
    private String activeVersion;

    @Schema(description = "仍在规则中内联密钥的历史加密规则数量（需改用密钥引用）", example = "0")
    private Long inlineSecretRuleCount;
}
