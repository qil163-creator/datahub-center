package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - ETL 执行记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class EtlRecordPageReqVO extends PageParam {

    @Schema(description = "流程名称（模糊）", example = "清洗")
    private String name;

    @Schema(description = "流程编号", example = "1")
    private Long pipelineId;

    @Schema(description = "运行状态：1-等待 2-运行中 3-成功 4-失败 5-停止", example = "3")
    private Integer runStatus;

    @Schema(description = "执行方式：1-手动 2-自动调度", example = "1")
    private Integer executeType;

    @Schema(description = "归档筛选；空为全部，true 仅归档，false 仅未归档")
    private Boolean archived;

    @Schema(description = "开始时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] startTime;

}
