package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 指标模型保存请求")
public class IndicatorModelSaveReqVO {

    private Long id;

    @PositiveOrZero
    private Long expectedRevision;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_]*")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Pattern(regexp = "ATOMIC|DERIVED")
    private String indicatorType;

    @NotNull
    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @Positive
    private Long entityId;

    @Pattern(regexp = "DRAFT|ACTIVE")
    private String status;

    @Positive
    private Long ownerUserId;

    @Size(max = 2000)
    private String description;

    @Size(max = 2000)
    private String definition;

    @Size(max = 50)
    private String unit;

    @Size(max = 100)
    private String dataFreshness;

    @Pattern(regexp = "TINYINT|SMALLINT|INT|INTEGER|BIGINT|INT2|INT4|INT8|DECIMAL|NUMERIC|NUMBER|REAL|FLOAT|FLOAT4|FLOAT8|DOUBLE|DOUBLE PRECISION|MONEY|STRING|DATE|DATETIME")
    private String resultDataType;

    @Positive
    private Long sourceModelId;


    @Size(max = 64)
    private String valueFieldUid;

    @Size(max = 64)
    private String timeFieldUid;

    @Pattern(regexp = "SUM|COUNT|COUNT_DISTINCT|AVG|MIN|MAX")
    private String aggregation;

    @Positive
    private Long baseIndicatorId;

    @Pattern(regexp = "DAY|WEEK|MONTH|QUARTER|YEAR")
    private String timePeriod;

    @Size(max = 2000)
    private String businessCondition;
}

