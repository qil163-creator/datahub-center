package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 指标数据查询请求")
public class IndicatorQueryReqVO {

    @Schema(description = "指标编号，最多 10 个", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 10)
    private List<@NotNull @Positive Long> indicatorIds;

    @Schema(description = "维度：来源模型字段 UID，按输出顺序")
    @Size(max = 5)
    private List<@NotBlank @Size(max = 64) String> dimensions;

    @Schema(description = "维度等值过滤，值以参数绑定")
    @Size(max = 10)
    private List<@Valid @NotNull Filter> filters;

    @Schema(description = "统计时间范围（含起止日期）")
    @Valid
    private TimeRange timeRange;

    @Schema(description = "时间粒度")
    @Pattern(regexp = "DAY|WEEK|MONTH|QUARTER|YEAR")
    private String timeGrain;

    @Schema(description = "最大返回行数，默认 200，最大 1000")
    @Min(1)
    @Max(1000)
    private Integer limit;

    @Data
    @Schema(description = "维度等值过滤")
    public static class Filter {

        @NotBlank
        @Size(max = 64)
        private String fieldUid;

        @NotNull
        @Size(max = 500)
        private String value;
    }

    @Data
    @Schema(description = "统计时间范围")
    public static class TimeRange {

        @Schema(description = "开始日期（含）", example = "2026-01-01")
        @NotBlank
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
        private String start;

        @Schema(description = "结束日期（含）", example = "2026-03-31")
        @NotBlank
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
        private String end;
    }
}
