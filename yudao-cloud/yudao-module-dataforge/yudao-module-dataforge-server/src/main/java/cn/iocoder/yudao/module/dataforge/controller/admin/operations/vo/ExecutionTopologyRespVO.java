package cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 数据任务执行拓扑响应。
 *
 * <p>该响应把页面上的任务类型映射到真实执行边界，避免把所有任务误认为由
 * DolphinScheduler 直接执行。</p>
 */
@Data
@Builder
@Schema(description = "管理后台 - 数据任务执行拓扑")
public class ExecutionTopologyRespVO {

    @Schema(description = "任务类型编码", example = "DATA_ACCESS_OFFLINE")
    private String taskKind;

    @Schema(description = "实际执行引擎", example = "DBSWITCH")
    private String engine;

    @Schema(description = "调度控制面", example = "XXL_JOB")
    private String scheduler;

    @Schema(description = "执行边界", example = "本平台受控执行器")
    private String executionBoundary;

    @Schema(description = "是否支持周期调度")
    private boolean scheduleSupported;

    @Schema(description = "是否支持停止命令")
    private boolean stopSupported;

    @Schema(description = "实现说明")
    private String description;
}
