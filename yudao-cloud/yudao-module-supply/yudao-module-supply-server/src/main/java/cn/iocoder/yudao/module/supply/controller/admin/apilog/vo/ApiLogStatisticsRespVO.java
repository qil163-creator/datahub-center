package cn.iocoder.yudao.module.supply.controller.admin.apilog.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 调用记录 监控统计 Response VO")
@Data
public class ApiLogStatisticsRespVO {

    @Schema(description = "API 服务总数", example = "12")
    private Long apiCount;

    @Schema(description = "上线 API 服务数", example = "8")
    private Long onlineApiCount;

    @Schema(description = "统计区间总调用量", example = "1024")
    private Long totalCount;

    @Schema(description = "今日调用量", example = "79")
    private Long todayCount;

    @Schema(description = "成功次数", example = "1000")
    private Long successCount;

    @Schema(description = "失败次数", example = "24")
    private Long failCount;

    @Schema(description = "成功率（百分比）", example = "97.66")
    private Double successRate;

    @Schema(description = "平均耗时（毫秒）", example = "126")
    private Long avgDurationMs;

    @Schema(description = "按天调用趋势")
    private List<TrendItem> trend;

    @Schema(description = "热门 API 排行（按调用量）")
    private List<RankItem> topApis;

    @Schema(description = "耗时 API 排行（按平均耗时）")
    private List<RankItem> slowApis;

    @Schema(description = "趋势项")
    @Data
    public static class TrendItem {

        @Schema(description = "日期", example = "2026-08-27")
        private String date;

        @Schema(description = "成功次数", example = "100")
        private Long successCount;

        @Schema(description = "失败次数", example = "3")
        private Long failCount;

    }

    @Schema(description = "排行项")
    @Data
    public static class RankItem {

        @Schema(description = "API 编号")
        private Long apiId;

        @Schema(description = "API 名称", example = "站点流量实时数据服务")
        private String name;

        @Schema(description = "数值（调用量或平均耗时）", example = "86")
        private Long value;

    }

}
