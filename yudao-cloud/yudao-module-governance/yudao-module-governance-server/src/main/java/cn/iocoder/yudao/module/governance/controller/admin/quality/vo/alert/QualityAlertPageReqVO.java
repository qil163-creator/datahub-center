package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 告警记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityAlertPageReqVO extends PageParam {

    @Schema(description = "告警标题（模糊）")
    private String alertTitle;

    @Schema(description = "任务名称（模糊）")
    private String taskName;

    @Schema(description = "告警等级：DANGER/SERIOUS/GENERAL")
    private String alertLevel;

    @Schema(description = "告警状态：UNPROCESSED/PROCESSED")
    private String alertStatus;

}
