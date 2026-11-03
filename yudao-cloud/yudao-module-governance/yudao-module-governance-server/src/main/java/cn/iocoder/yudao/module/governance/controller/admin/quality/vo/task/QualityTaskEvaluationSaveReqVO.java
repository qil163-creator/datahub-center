package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 任务内独立评测定义，不修改可复用的全局质量规则。 */
@Data
public class QualityTaskEvaluationSaveReqVO {

    private Long id;

    @NotBlank
    @Size(max = 128)
    private String objectKey;

    @NotBlank
    @Size(max = 128)
    private String evaluationName;

    @NotBlank
    @Size(max = 128)
    private String ruleCode;

    @NotBlank
    @Size(max = 128)
    private String strategyKey;

    @NotBlank
    @Size(max = 32)
    private String qualityDim;

    @Pattern(regexp = "LOW|MID|CRITICAL")
    private String severity;

    @Min(0)
    @Max(1)
    private Integer status;

    @Size(max = 256)
    private String description;

    @Size(max = 256)
    private String errorExample;

    @Size(max = 256)
    private String repairAdvice;

    @Size(max = 256)
    private String whereClause;

    @NotNull
    @Size(max = 32)
    private List<@NotBlank @Size(max = 128) String> columnNames;

    @NotNull
    @Size(max = 32)
    private Map<String, Object> config;
}
