package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 任务执行实例分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 任务执行实例分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DevRecordPageReqVO extends PageParam {

    @Schema(description = "任务名称（模糊）", example = "水位")
    private String taskName;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "执行状态", example = "SUCCESS")
    private String status;

    @Schema(description = "触发方式", example = "MANUAL")
    private String triggerType;

    @Schema(description = "开始时间范围")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] startTime;

}
