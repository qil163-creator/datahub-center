package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 任务实例分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskInstancePageReqVO extends PageParam {

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "实例名称（模糊）")
    private String instanceName;

    @Schema(description = "实例状态：RUNNING/SUCCESS/FAILED")
    private String instanceStatus;

    @Schema(description = "运行类型：MANUAL/SCHEDULE")
    private String runType;

    @Schema(description = "创建时间范围")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
