package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyCategoryMaskReqVO extends SafetyCategoryVersionReqVO {

    @NotBlank
    @Size(max = 64)
    private String ruleName;

    @Positive
    private Long ruleVersionId;

    @Pattern(regexp = "[a-f0-9]{64}")
    private String ruleRevisionToken;

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

    @Data
    public static class Interval {

        @NotNull
        @Positive
        private Integer startNum;

        @NotNull
        @Positive
        private Integer endNum;
    }
}
