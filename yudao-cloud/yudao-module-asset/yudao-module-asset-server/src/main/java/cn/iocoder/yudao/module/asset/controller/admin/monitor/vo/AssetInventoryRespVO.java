package cn.iocoder.yudao.module.asset.controller.admin.monitor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 资产盘点报告 Response VO")
@Data
public class AssetInventoryRespVO {

    @Schema(description = "统计日期；尚无快照时为空")
    private LocalDate statDate;

    @Schema(description = "统计窗口天数")
    private Integer windowDays;

    @Schema(description = "是否取得了接口调用统计")
    private Boolean apiStatsAvailable;

    @Schema(description = "资产总数")
    private long totalCount;

    @Schema(description = "已发布数")
    private long publishedCount;

    @Schema(description = "未发布数")
    private long unpublishedCount;

    @Schema(description = "已下线数")
    private long offlineCount;

    @Schema(description = "窗口内被使用过的资产数（申请、预览/查询或接口调用）")
    private long usedCount;

    @Schema(description = "窗口内活跃用户数")
    private long activeUserCount;

    @Schema(description = "窗口内浏览总次数")
    private long browseTotal;

    @Schema(description = "窗口内使用总次数")
    private long usageTotal;

    @Schema(description = "窗口内申请总次数")
    private long applyTotal;

    @Schema(description = "窗口内接口调用总次数")
    private long apiCallTotal;

    @Schema(description = "描述覆盖率 %")
    private BigDecimal descriptionRate;

    @Schema(description = "责任人覆盖率 %（技术或业务负责人至少一个）")
    private BigDecimal ownerRate;

    @Schema(description = "质量评估覆盖率 %")
    private BigDecimal qualityAssessedRate;

    @Schema(description = "平均完整度分")
    private BigDecimal avgCompleteness;

    @Schema(description = "平均质量分（仅已评估资产）")
    private BigDecimal avgQuality;

    @Schema(description = "平均价值分")
    private BigDecimal avgValue;

    @Schema(description = "资产类型分布：类型 → 数量")
    private Map<Integer, Long> typeDistribution;

    @Schema(description = "价值等级分布：等级 → 数量")
    private Map<String, Long> levelDistribution;

    @Schema(description = "运营建议分布：建议 → 数量")
    private Map<String, Long> suggestionDistribution;

    @Schema(description = "类目分布（按资产数降序）")
    private List<CategoryItem> categoryDistribution;

    @Schema(description = "热度 Top 10")
    private List<AssetOperationRespVO> topHot;

    @Schema(description = "价值 Top 10")
    private List<AssetOperationRespVO> topValue;

    @Schema(description = "闲置最久的 10 个已发布资产")
    private List<AssetOperationRespVO> longestIdle;

    @Schema(description = "资产盘点类目分布项")
    @Data
    public static class CategoryItem {

        @Schema(description = "类目编号；未归类为空")
        private Long categoryId;

        @Schema(description = "类目名称")
        private String categoryName;

        @Schema(description = "资产数")
        private long count;

        @Schema(description = "已发布数")
        private long publishedCount;

        @Schema(description = "闲置数")
        private long idleCount;

        @Schema(description = "平均价值分")
        private BigDecimal avgValue;

    }

}
