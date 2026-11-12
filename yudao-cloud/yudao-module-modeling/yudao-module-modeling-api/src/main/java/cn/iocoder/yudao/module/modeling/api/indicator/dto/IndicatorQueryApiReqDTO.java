package cn.iocoder.yudao.module.modeling.api.indicator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * RPC - 指标数据查询请求。供数据服务把指标发布为 API：调用方负责应用授权，并给出结果的查看主体用于动态脱敏。
 */
@Schema(description = "RPC 服务 - 指标数据查询请求")
@Data
public class IndicatorQueryApiReqDTO {

    @Schema(description = "结果查看主体（动态脱敏按此用户判定豁免）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "查看主体不能为空")
    @Positive(message = "查看主体必须为正数")
    private Long viewerUserId;

    @Schema(description = "指标编号，最多 10 个", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "指标不能为空")
    @Size(max = 10, message = "指标最多 10 个")
    private List<@NotNull @Positive Long> indicatorIds;

    @Schema(description = "维度：来源模型字段 UID，按输出顺序")
    @Size(max = 5, message = "维度最多 5 个")
    private List<@NotBlank @Size(max = 64) String> dimensions;

    @Schema(description = "维度等值过滤")
    @Size(max = 10, message = "过滤最多 10 个")
    private List<@Valid @NotNull Filter> filters;

    @Schema(description = "统计开始日期（含）", example = "2026-01-01")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "开始日期格式应为 yyyy-MM-dd")
    private String startDate;

    @Schema(description = "统计结束日期（含）", example = "2026-03-31")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "结束日期格式应为 yyyy-MM-dd")
    private String endDate;

    @Schema(description = "时间粒度：DAY/WEEK/MONTH/QUARTER/YEAR")
    @Pattern(regexp = "DAY|WEEK|MONTH|QUARTER|YEAR", message = "时间粒度不合法")
    private String timeGrain;

    @Schema(description = "最大返回行数（1-1000）")
    @Min(value = 1, message = "返回行数不能小于 1")
    @Max(value = 1000, message = "返回行数不能大于 1000")
    private Integer limit;

    /** 维度等值过滤。 */
    @Data
    public static class Filter {

        @NotBlank
        @Size(max = 64)
        private String fieldUid;

        @NotNull
        @Size(max = 500)
        private String value;
    }
}
