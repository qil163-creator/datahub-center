package cn.iocoder.yudao.module.supply.controller.admin.apilog.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据服务 数据概览 Response VO")
@Data
public class ApiLogOverviewRespVO {

    @Schema(description = "今日调用次数", example = "1234")
    private Long todayCount;

    @Schema(description = "今日调用成功次数", example = "1200")
    private Long todaySuccessCount;

    @Schema(description = "今日调用失败次数", example = "34")
    private Long todayFailCount;

    @Schema(description = "本月调用总次数", example = "5000")
    private Long monthCount;

    @Schema(description = "API 总数", example = "50")
    private Long apiCount;

    @Schema(description = "API 上线总数", example = "36")
    private Long onlineApiCount;

    @Schema(description = "近 30 天调用趋势（按天，含无调用的日期）")
    private List<TrendItem> trend;

    @Schema(description = "今日 API 调用排行（按调用量倒序）")
    private List<RankItem> todayRank;

    @Schema(description = "本月 API 调用排行（按调用量倒序）")
    private List<RankItem> monthRank;

    @Schema(description = "趋势项")
    @Data
    public static class TrendItem {

        @Schema(description = "日期", example = "2026-09-08")
        private String date;

        @Schema(description = "调用次数", example = "120")
        private Long count;

    }

    @Schema(description = "排行项")
    @Data
    public static class RankItem {

        @Schema(description = "API 编号")
        private Long apiId;

        @Schema(description = "API 名称", example = "站点元数据服务")
        private String name;

        @Schema(description = "调用次数", example = "360")
        private Long value;

    }

}
