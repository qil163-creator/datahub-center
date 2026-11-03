package cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo;

import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaOverviewRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementStatisticsRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 首页看板 - 数据治理域汇总（元数据 / 数据标准 / 数据质量）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 首页看板（数据治理） Response VO")
@Data
public class GovernanceDashboardRespVO {

    // ==================== 元数据 ====================

    @Schema(description = "元数据概览（库 / 表 / 字段数、覆盖率、采集成功率、近 30 天变更趋势、最近采集失败）")
    private MetaOverviewRespVO meta;

    // ==================== 数据标准 ====================

    @Schema(description = "标准数据元统计（总数 / 各状态数量 / 落标率 / 码表绑定数）")
    private StandardElementStatisticsRespVO standard;

    // ==================== 数据质量 ====================

    @Schema(description = "质量规则总数", example = "120")
    private Long qualityRuleCount;

    @Schema(description = "启用中的质量规则数", example = "110")
    private Long qualityRuleEnabledCount;

    @Schema(description = "质量检核任务总数", example = "18")
    private Long qualityTaskCount;

    @Schema(description = "质量检核汇总（累计）")
    private QualityReport qualityReport;

    @Schema(description = "六维质量通过率")
    private List<QualityDimension> qualityDimensions;

    @Schema(description = "质量告警汇总")
    private QualityAlert qualityAlert;

    @Schema(description = "待处理的质量问题工单数（全平台，不限创建时间）", example = "3")
    private Long pendingWorkOrderCount;

    // ==================== 主数据 ====================

    @Schema(description = "主数据模型总数", example = "12")
    private Long masterModelCount;

    @Schema(description = "已发布的主数据模型数", example = "10")
    private Long masterModelReleasedCount;

    @Schema(description = "主数据派发任务总数", example = "8")
    private Long masterDistributeCount;

    @Schema(description = "已发布的主数据派发任务数", example = "6")
    private Long masterDistributeReleasedCount;

    @Schema(description = "质量检核汇总")
    @Data
    public static class QualityReport {

        @Schema(description = "检测总次数", example = "1000")
        private Long totalCount;

        @Schema(description = "通过次数", example = "960")
        private Long passCount;

        @Schema(description = "未通过次数", example = "40")
        private Long failCount;

        @Schema(description = "平均通过率（百分比，0~100）", example = "96.00")
        private BigDecimal avgPassRate;

    }

    @Schema(description = "质量维度通过率项")
    @Data
    public static class QualityDimension {

        @Schema(description = "质量维度：COMPLETENESS / UNIQUENESS / NORMALIZATION / ACCURACY / TIMELINESS / CONSISTENCY",
                example = "COMPLETENESS")
        private String qualityDim;

        @Schema(description = "检测次数", example = "200")
        private Long checkCount;

        @Schema(description = "通过率（百分比，0~100）", example = "98.50")
        private BigDecimal passRate;

    }

    @Schema(description = "质量告警汇总")
    @Data
    public static class QualityAlert {

        @Schema(description = "危险级告警数", example = "1")
        private Long dangerCount;

        @Schema(description = "严重级告警数", example = "2")
        private Long seriousCount;

        @Schema(description = "一般级告警数", example = "5")
        private Long generalCount;

        @Schema(description = "未处理告警数", example = "3")
        private Long unprocessedCount;

        @Schema(description = "已处理告警数", example = "5")
        private Long processedCount;

    }

}
