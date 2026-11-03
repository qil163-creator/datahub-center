package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - API 服务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataApiPageReqVO extends PageParam {

    @Schema(description = "API 服务名称（模糊）", example = "流量")
    private String name;

    @Schema(description = "服务分类编号（含子级）", example = "1")
    private Long categoryId;

    @Schema(description = "状态：0-下线 1-上线", example = "1")
    private Integer status;

    @Schema(description = "创建时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
