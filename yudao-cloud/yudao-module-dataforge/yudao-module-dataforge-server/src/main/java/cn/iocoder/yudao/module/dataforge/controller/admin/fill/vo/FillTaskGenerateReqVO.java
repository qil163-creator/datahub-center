package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-任务补生成 Request VO
 *
 * 用于一次性任务的手动生成，以及调度停机后按历史生成时点补生成周期任务
 *
 * @author Li
 */
@Schema(description = "管理后台 - 任务补生成 Request VO")
@Data
public class FillTaskGenerateReqVO {

    @Schema(description = "计划编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "计划编号不能为空")
    private Long planId;

    @Schema(description = "生成时点（缺省为当前时间；补生成历史期次时传对应历史时点，如 2026-07-01T08:00:00）")
    private LocalDateTime generateTime;

}
