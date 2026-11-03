package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 报告生成记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityReportLogPageReqVO extends PageParam {

    @Schema(description = "生成状态：RUNNING/SUCCESS/FAILED")
    private String generateStatus;

    @Schema(description = "生成人员（模糊）")
    private String generator;

    @Schema(description = "生成时间范围")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] generateTime;

}
