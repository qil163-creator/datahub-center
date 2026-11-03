package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 元数据简版概览
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据简版概览")
@Data
public class MetaOverviewRespVO {

    @Schema(description = "库数量（不含已删除）")
    private Long dbCount;

    @Schema(description = "表数量（不含已删除）")
    private Long tableCount;

    @Schema(description = "字段数量（不含已删除）")
    private Long columnCount;

    @Schema(description = "已发布表数量")
    private Long publishedTableCount;

    @Schema(description = "未发布表数量（不含已删除）")
    private Long unpublishedTableCount;

    @Schema(description = "待确认删除表数量")
    private Long pendingDeleteTableCount;

    @Schema(description = "数据源数（库上 datasourceId 去重）")
    private Long datasourceCount;

    @Schema(description = "来源系统数（库上 sourceSystemId 去重）")
    private Long sourceSystemCount;

    @Schema(description = "表注释覆盖率 0-100")
    private BigDecimal commentCoverage;

    @Schema(description = "表业务认责覆盖率 0-100")
    private BigDecimal ownerCoverage;

    @Schema(description = "近 30 天采集成功率 0-100（无实例则为空）")
    private BigDecimal collectSuccessRate;

    @Schema(description = "近 30 天采集实例数")
    private Long collectInstanceCount;

    @Schema(description = "近 30 天变更趋势")
    private List<TrendPoint> changeTrend = new ArrayList<>();

    @Schema(description = "最近失败采集实例")
    private List<FailItem> recentFails = new ArrayList<>();

    @Schema(description = "变更趋势点")
    @Data
    public static class TrendPoint {

        @Schema(description = "日期 yyyy-MM-dd")
        private String day;

        @Schema(description = "新增")
        private Long addCount;

        @Schema(description = "修改")
        private Long modifyCount;

        @Schema(description = "删除")
        private Long deleteCount;
    }

    @Schema(description = "失败采集项")
    @Data
    public static class FailItem {

        @Schema(description = "实例编号")
        private Long instanceId;

        @Schema(description = "任务编号")
        private Long taskId;

        @Schema(description = "任务名称")
        private String taskName;

        @Schema(description = "错误分类")
        private String errorType;

        @Schema(description = "失败阶段")
        private String stage;

        @Schema(description = "开始时间")
        private LocalDateTime startTime;
    }

}
