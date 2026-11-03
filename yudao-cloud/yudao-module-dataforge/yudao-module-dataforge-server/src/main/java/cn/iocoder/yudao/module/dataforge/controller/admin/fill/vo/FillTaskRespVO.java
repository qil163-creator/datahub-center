package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-采集任务 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 采集任务 Response VO")
@Data
public class FillTaskRespVO {

    @Schema(description = "任务编号", example = "1")
    private Long id;

    @Schema(description = "计划编号", example = "1")
    private Long planId;

    @Schema(description = "任务名称", example = "民生项目月报（2026-08）")
    private String name;

    @Schema(description = "填报表编号", example = "1")
    private Long tableId;

    @Schema(description = "模板版本编号", example = "1")
    private Long tableVersionId;

    @Schema(description = "BPM 流程定义 Key", example = "dataforge_fill_approval")
    private String processDefinitionKey;

    @Schema(description = "数据所属期", example = "2026-08")
    private String periodKey;

    @Schema(description = "所属期开始")
    private LocalDateTime periodStart;

    @Schema(description = "所属期结束")
    private LocalDateTime periodEnd;

    @Schema(description = "开放时间")
    private LocalDateTime openTime;

    @Schema(description = "截止时间")
    private LocalDateTime deadlineTime;

    @Schema(description = "逾期策略：1-软逾期 2-硬截止", example = "1")
    private Integer overduePolicy;

    @Schema(description = "状态：0-待开始 1-进行中 2-已完成 3-已取消", example = "1")
    private Integer status;

    @Schema(description = "应填组织总数", example = "10")
    private Integer totalOrgCount;

    @Schema(description = "已合并组织数", example = "7")
    private Integer mergedOrgCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
