package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 开发任务调度设置 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发任务调度设置 Request VO")
@Data
public class DevTaskScheduleReqVO {

    @Schema(description = "任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "任务编号不能为空")
    private Long id;

    @Schema(description = "调度状态：0-关闭 1-开启", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "调度状态不能为空")
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(1)
    private Integer scheduleStatus;

    @Schema(description = "调度 Cron 表达式", example = "0 0 2 * * ?")
    private String cronExpression;

}
