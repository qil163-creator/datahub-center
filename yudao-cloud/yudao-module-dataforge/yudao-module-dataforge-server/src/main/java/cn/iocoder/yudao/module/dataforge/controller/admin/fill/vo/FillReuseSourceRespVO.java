package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-可复用的历史数据 Response VO（同一计划、同一报送组织、审批通过的历史期次）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 可复用的历史填报数据 Response VO")
@Data
public class FillReuseSourceRespVO {

    @Schema(description = "提交快照编号（复用时回传）", example = "71")
    private Long submissionId;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "任务名称", example = "月报（2026-09）")
    private String taskName;

    @Schema(description = "所属期", example = "2026-09")
    private String periodKey;

    @Schema(description = "数据行数", example = "12")
    private Integer rowCount;

    @Schema(description = "提交时间")
    private LocalDateTime submitTime;

}
