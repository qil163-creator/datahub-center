package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryMaskReqVO.Interval;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class SafetyRuleWorkbenchSaveReqVO {

    @Positive
    private Long id;

    @NotNull
    @Positive
    private Long categoryId;

    @NotNull
    @Positive
    private Long categoryRevision;

    @Positive
    private Long ruleVersionId;

    @Pattern(regexp = "[a-f0-9]{64}")
    private String ruleRevisionToken;

    @NotBlank
    @Size(max = 64)
    private String ruleName;

    @NotEmpty
    @Size(max = 2)
    private List<@NotNull @Pattern(regexp = "1|2") String> applicationScene;

    @NotBlank
    @Pattern(regexp = "2")
    private String maskType;

    @NotBlank
    @Pattern(regexp = "1")
    private String replaceRule;

    @NotBlank
    @Size(max = 256)
    private String replaceContent;

    @Valid
    @NotEmpty
    @Size(max = 100)
    private List<@NotNull Interval> intervalList;

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;

    @Size(max = 256)
    private String description;
}
