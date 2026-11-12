package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 启用指标轻量选项")
public class IndicatorOptionRespVO {

    private Long id;

    private String code;

    private String name;

    @Schema(description = "ATOMIC / DERIVED / COMPOSITE")
    private String indicatorType;

    private Long dataDomainId;

    private String unit;

    private String resultDataType;

    @Schema(description = "原子指标是否配置了统计时间字段")
    private Boolean timeConfigured;

    @Schema(description = "派生指标统计周期")
    private String timePeriod;

    @Schema(description = "复合指标公式")
    private String formula;
}
