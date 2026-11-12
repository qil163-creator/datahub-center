package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台 - 指标口径变更影响分析 Response VO
 */
@Schema(description = "管理后台 - 指标口径变更影响分析 Response VO")
@Data
public class IndicatorImpactRespVO {

    @Schema(description = "被分析的指标")
    private Item indicator;

    @Schema(description = "直接或间接依赖它的派生、复合指标（按层级）")
    private List<Item> dependents = new ArrayList<>();

    @Schema(description = "使用这些指标的看板")
    private List<Board> boards = new ArrayList<>();

    @Schema(description = "发布了这些指标的数据服务接口")
    private List<Api> apis = new ArrayList<>();

    @Schema(description = "数据服务是否可查询；为 false 时接口清单不完整")
    private Boolean apisAvailable;

    @Schema(description = "依赖是否超过上限而被截断")
    private Boolean truncated;

    /** 指标。 */
    @Data
    public static class Item {
        private Long id;
        private String code;
        private String name;
        private String indicatorType;
        private String status;
        @Schema(description = "依赖层级：1 表示直接依赖")
        private Integer depth;
        @Schema(description = "它直接依赖的上游指标编号")
        private Long upstreamId;
    }

    /** 看板。 */
    @Data
    public static class Board {
        private Long id;
        private String name;
        private Long ownerUserId;
        @Schema(description = "受影响的组件标题")
        private List<String> widgets = new ArrayList<>();
    }

    /** 数据服务接口。 */
    @Data
    public static class Api {
        private Long id;
        private String name;
        private String path;
        private Integer status;
        private List<Long> indicatorIds = new ArrayList<>();
    }
}
