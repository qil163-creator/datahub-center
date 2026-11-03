package cn.iocoder.yudao.module.supply.controller.admin.apilog.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - API 调用记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ApiLogPageReqVO extends PageParam {

    @Schema(description = "API 服务名称（模糊）", example = "流量")
    private String apiName;

    @Schema(description = "服务分类编号（含子级）", example = "1")
    private Long categoryId;

    @Schema(description = "状态：1-成功 0-失败", example = "1")
    private Integer status;

    @Schema(description = "拒绝阶段")
    @jakarta.validation.constraints.Pattern(regexp = "AUTH|GRANT|CONTRACT|IP|RATE|BREAKER|EXECUTE")
    private String rejectStage;

    @Schema(description = "请求追踪编号")
    @jakarta.validation.constraints.Size(max = 64)
    private String traceId;

    @Schema(description = "调用时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] callTime;

}
