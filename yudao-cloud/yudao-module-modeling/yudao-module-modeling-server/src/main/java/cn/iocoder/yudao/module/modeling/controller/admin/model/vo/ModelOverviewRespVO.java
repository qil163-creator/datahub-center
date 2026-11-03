package cn.iocoder.yudao.module.modeling.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台 - 数据建模总览统计 Response VO
 *
 * <p>用于数据建模首页「数据概览」面板。
 *
 * @author Li
 */
@Schema(description = "管理后台 - 数据建模总览统计 Response VO")
@Data
public class ModelOverviewRespVO {

    @Schema(description = "数据域数量（数据分域总数）", requiredMode = Schema.RequiredMode.REQUIRED, example = "8")
    private Long dataDomainCount = 0L;

    @Schema(description = "业务域数量（业务分类总数）", requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
    private Long businessDomainCount = 0L;

    @Schema(description = "逻辑模型总数（不含已废弃）", requiredMode = Schema.RequiredMode.REQUIRED, example = "36")
    private Long modelCount = 0L;

    @Schema(description = "已发布逻辑模型数", requiredMode = Schema.RequiredMode.REQUIRED, example = "20")
    private Long publishedCount = 0L;

    @Schema(description = "已物化默认目标数（已有成功部署记录）", requiredMode = Schema.RequiredMode.REQUIRED, example = "15")
    private Long materializedCount = 0L;

    @Schema(description = "已物化事实表数（DWD/DWS/ADS 层）", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    private Long factCount = 0L;

    @Schema(description = "已物化维度表数（DIM 层）", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    private Long dimensionCount = 0L;

    @Schema(description = "指标卡片；不可用时 count 和 recentCount 为空，以 description 说明原因")
    private List<Metric> metrics = List.of();

    @Schema(description = "非废弃逻辑模型按当前所属数仓分层的分布，包含自定义分层和未分层")
    private List<Distribution> layerDistribution = List.of();

    @Schema(description = "拥有成功部署记录的目标，按当前模型所属数仓分层统计")
    private List<Distribution> materializedDistribution = List.of();

    @Schema(description = "模型创建及当前设计发布事实，最多八条；并非完整操作审计")
    private List<Activity> recentActivities = List.of();

    private boolean layerDistributionAvailable;

    private boolean materializedDistributionAvailable;

    private boolean recentActivitiesAvailable;

    @Schema(description = "本次统计时间，默认序列化为毫秒时间戳")
    private LocalDateTime generatedAt;

    @Data
    @Schema(description = "建模概览指标")
    public static class Metric {

        private String key;

        private String label;

        @JsonInclude(JsonInclude.Include.ALWAYS)
        private Long count;

        private boolean available;

        @Schema(description = "最近七日新增的当前有效记录数；发布指标为最近七日发布过的非废弃模型去重数，非存量同比")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private Long recentCount;

        private String description;
    }

    @Data
    @Schema(description = "建模分层分布")
    public static class Distribution {

        private String key;

        private String name;

        @JsonInclude(JsonInclude.Include.ALWAYS)
        private String code;

        private Long count;
    }

    @Data
    @Schema(description = "建模最近动态")
    public static class Activity {

        private String id;

        private Long modelId;

        @Schema(description = "CREATE 或 PUBLISH")
        private String type;

        private String title;

        @Schema(description = "发生时间，默认序列化为毫秒时间戳")
        private LocalDateTime occurredAt;
    }

}
