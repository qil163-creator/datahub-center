package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 在线填报-任务进度 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 任务进度 Response VO")
@Data
public class FillTaskProgressRespVO {

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "应填组织总数", example = "10")
    private Integer totalOrgCount;

    @Schema(description = "已合并组织数", example = "7")
    private Integer mergedCount;

    @Schema(description = "逾期组织数", example = "2")
    private Integer overdueCount;

    @Schema(description = "完成率（已合并/应填，0~100）", example = "70")
    private Integer completionRate;

    @Schema(description = "各状态数量：key=工作项状态值，value=数量")
    private Map<Integer, Long> statusCounts;

}
