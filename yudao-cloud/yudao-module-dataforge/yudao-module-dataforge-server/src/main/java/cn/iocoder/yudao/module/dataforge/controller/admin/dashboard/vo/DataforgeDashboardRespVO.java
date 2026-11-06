package cn.iocoder.yudao.module.dataforge.controller.admin.dashboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 首页看板 - 数据集成 / 数据研发域汇总
 *
 * @author Li
 */
@Schema(description = "管理后台 - 首页看板（数据集成 / 数据研发） Response VO")
@Data
public class DataforgeDashboardRespVO {

    // ==================== 数据源 ====================

    @Schema(description = "数据源总数", example = "12")
    private Long databaseCount;

    @Schema(description = "数据源类型分布（按数量倒序）")
    private List<NameCount> databaseTypeDistribution;

    // ==================== 数据集成 ====================

    @Schema(description = "数据同步任务总数", example = "36")
    private Long accessJobCount;

    @Schema(description = "已发布的数据同步任务数", example = "30")
    private Long accessReleasedCount;

    @Schema(description = "今日数据同步执行统计")
    private RunSummary todayAccessRun;

    // ==================== 数据开发 ====================

    @Schema(description = "开发任务总数", example = "58")
    private Long devTaskCount;

    @Schema(description = "已上线的开发任务数", example = "40")
    private Long devTaskOnlineCount;

    @Schema(description = "今日开发任务执行统计")
    private RunSummary todayDevRun;

    @Schema(description = "执行统计")
    @Data
    public static class RunSummary {

        @Schema(description = "执行总数", example = "20")
        private Long totalCount;

        @Schema(description = "成功数", example = "18")
        private Long successCount;

        @Schema(description = "失败数", example = "1")
        private Long failedCount;

        @Schema(description = "运行中（含等待中）数", example = "1")
        private Long runningCount;

    }

    @Schema(description = "名称计数项")
    @Data
    public static class NameCount {

        @Schema(description = "名称", example = "MySQL")
        private String name;

        @Schema(description = "数量", example = "5")
        private Long count;

    }

}
