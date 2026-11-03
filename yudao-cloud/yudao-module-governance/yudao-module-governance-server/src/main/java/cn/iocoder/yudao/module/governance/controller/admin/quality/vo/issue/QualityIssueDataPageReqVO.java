package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 问题数据分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityIssueDataPageReqVO extends PageParam {

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "任务实例编号")
    private Long instanceId;

    @Schema(description = "规则编号")
    private Long ruleId;

    @Schema(description = "检测表（模糊）")
    private String tableName;

    @Schema(description = "处理状态：UNPROCESSED/PROCESSED")
    private String issueStatus;

    @Schema(description = "统计口径：ORG/ALL")
    private String statScope;

    @Schema(description = "机构编号")
    private Long orgId;

    @Schema(description = "检出时间范围")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] checkTime;

}
