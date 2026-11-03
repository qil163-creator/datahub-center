package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import lombok.Data;

@Data
public class StandardElementRuleSaveReqVO {

    private Long id;

    @jakarta.validation.constraints.NotNull
    private Integer elementId;

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Pattern(regexp = "CLEAN|AUDIT")
    private String kind;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 100)
    private String name;

    @jakarta.validation.constraints.NotNull
    private Long templateId;

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(1)
    private Integer status;

    @jakarta.validation.constraints.Size(max = 256)
    private String description;

    @jakarta.validation.constraints.Size(max = 256)
    private String whereCondition;

    private java.util.Map<String, Object> config;

    @jakarta.validation.constraints.Pattern(regexp = "HIGH|MID|LOW")
    private String severity;

    @jakarta.validation.constraints.Size(max = 256)
    private String errorExample;

    @jakarta.validation.constraints.Size(max = 256)
    private String repairSuggestion;

    private Long revision;
}
