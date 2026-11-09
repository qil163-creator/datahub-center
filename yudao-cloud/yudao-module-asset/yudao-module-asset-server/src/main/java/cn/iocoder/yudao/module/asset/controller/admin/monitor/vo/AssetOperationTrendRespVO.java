package cn.iocoder.yudao.module.asset.controller.admin.monitor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "管理后台 - 资产运营趋势 Response VO")
@Data
public class AssetOperationTrendRespVO {

    @Schema(description = "资产编号")
    private Long assetId;

    @Schema(description = "资产名称")
    private String assetName;

    @Schema(description = "逐日数据点（无快照的日期评分为空）")
    private List<Point> points;

    @Schema(description = "资产运营趋势数据点")
    @Data
    public static class Point {

        @Schema(description = "日期")
        private LocalDate date;

        @Schema(description = "当日浏览次数")
        private long browseCount;

        @Schema(description = "当日申请次数")
        private long applyCount;

        @Schema(description = "当日使用次数")
        private long usageCount;

        @Schema(description = "当日快照热度分")
        private BigDecimal heatScore;

        @Schema(description = "当日快照价值分")
        private BigDecimal valueScore;

    }

}
