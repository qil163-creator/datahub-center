package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SafetyRuleWorkbenchVersionReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Positive
    private Long categoryRevision;

    @NotNull
    @Positive
    private Long ruleVersionId;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    private String ruleRevisionToken;
}
