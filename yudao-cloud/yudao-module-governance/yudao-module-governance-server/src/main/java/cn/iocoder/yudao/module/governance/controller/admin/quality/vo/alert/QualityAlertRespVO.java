package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 告警记录 Response VO")
@Data
public class QualityAlertRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "告警标题")
    private String alertTitle;

    @Schema(description = "任务类型")
    private String taskType;

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "告警等级：DANGER/SERIOUS/GENERAL")
    private String alertLevel;

    @Schema(description = "问题记录数")
    private Long issueCount;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "告警状态：UNPROCESSED/PROCESSED")
    private String alertStatus;

    @Schema(description = "告警时间")
    private LocalDateTime alertTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
