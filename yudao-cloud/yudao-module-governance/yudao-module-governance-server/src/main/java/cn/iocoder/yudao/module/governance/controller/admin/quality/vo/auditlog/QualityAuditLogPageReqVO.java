package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 检测结果分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityAuditLogPageReqVO extends PageParam {

    @Schema(description = "任务实例编号")
    private Long instanceId;

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "检测表（模糊）")
    private String tableName;

    @Schema(description = "规则名称（模糊）")
    private String ruleName;

    @Schema(description = "结果类型：PASS/FAIL/ERROR")
    private String resultType;

    @Schema(description = "分组")
    private String groupName;

    @Schema(description = "检测时间范围")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] checkTime;

}
