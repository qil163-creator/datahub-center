package cn.iocoder.yudao.module.governance.api.quality.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 资产提交的任务内评测；检查对象由服务端真实技术绑定生成。 */
@Data
public class QualityAssetEvaluationDTO {

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
