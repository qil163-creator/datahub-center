package cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据治理总览安全统计 Response VO")
@Data
public class GovernanceSafetySummaryRespVO {

    // ==================== 打标覆盖 ====================

    @Schema(description = "元数据字段总数")
    private Long metaColumnCount;

    @Schema(description = "已标记敏感的元数据字段数")
    private Long sensitiveColumnCount;

    @Schema(description = "待确认打标数")
    private Long pendingTagCount;

    @Schema(description = "已确认打标数")
    private Long confirmedTagCount;

    // ==================== 资产概况 ====================

    @Schema(description = "启用中的识别规则数")
    private Long identifyRuleCount;

    @Schema(description = "生效中的白名单数（已审批+启用+有效期内）")
    private Long activeWhitelistCount;

    // ==================== 近 30 天 ====================

    @Schema(description = "近 30 天敏感访问次数")
    private Long accessCount30d;

    @Schema(description = "近 30 天白名单豁免次数")
    private Long exemptCount30d;

    @Schema(description = "近 30 天静态脱敏执行次数")
    private Long staticRunCount30d;

    @Schema(description = "近 30 天静态脱敏执行成功率（百分比，无执行时为 null）")
    private Double staticRunSuccessRate;

    // ==================== 图表 ====================

    @Schema(description = "已确认打标的分级分布")
    private List<LevelItem> levelDistribution;

    @Schema(description = "近 30 天访问趋势（按天）")
    private List<TrendItem> accessTrend;

    @Schema(description = "分级分布项")
    @Data
    public static class LevelItem {

        @Schema(description = "分级编号")
        private Long levelId;

        @Schema(description = "分级名称")
        private String levelName;

        @Schema(description = "分级颜色")
        private String color;

        @Schema(description = "字段数")
        private Long count;
    }

    @Schema(description = "访问趋势项")
    @Data
    public static class TrendItem {

        @Schema(description = "日期（YYYY-MM-DD）")
        private String date;

        @Schema(description = "访问次数")
        private Long total;

        @Schema(description = "豁免次数")
        private Long exempt;
    }

}
