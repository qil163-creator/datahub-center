package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "管理后台 - 指标数据查询结果；指标值为十进制字符串")
public class IndicatorQueryRespVO {

    public static final String KIND_DIMENSION = "DIMENSION";

    public static final String KIND_TIME = "TIME";

    public static final String KIND_INDICATOR = "INDICATOR";

    @Schema(description = "列定义，顺序即展示顺序")
    private List<Column> columns;

    @Schema(description = "行：列 key → 字符串值（空值为 null）")
    private List<Map<String, String>> rows;

    @Schema(description = "结果是否因行数上限被截断")
    private Boolean truncated;

    @Schema(description = "生效时间粒度")
    private String timeGrain;

    @Schema(description = "生效时间范围开始（含）")
    private String timeStart;

    @Schema(description = "生效时间范围结束（含）")
    private String timeEnd;

    @Schema(description = "每个请求指标的生效口径")
    private List<Caliber> calibers;

    @Schema(description = "被动态脱敏的列，name 为结果行中的列 key；被脱敏列的原值不会出现在 rows 中")
    private List<MaskedColumn> maskedColumns;

    @Data
    @Schema(description = "被动态脱敏的列")
    public static class MaskedColumn {

        @Schema(description = "结果行中的列 key")
        private String name;

        @Schema(description = "原因：RULE/UNCLASSIFIED/UNTRACEABLE/UNVERIFIED")
        private String reason;

        @Schema(description = "命中的分类名称")
        private List<String> categoryNames;
    }

    @Data
    @Schema(description = "结果列")
    public static class Column {

        private String key;

        private String title;

        @Schema(description = "DIMENSION / TIME / INDICATOR")
        private String kind;

        @Schema(description = "维度字段 UID")
        private String fieldUid;

        private Long indicatorId;

        private String indicatorCode;

        private String unit;
    }

    @Data
    @Schema(description = "指标生效口径")
    public static class Caliber {

        private Long indicatorId;

        private String code;

        private String name;

        private String indicatorType;

        private String unit;

        @Schema(description = "复合指标公式")
        private String formula;

        @Schema(description = "参与计算的原子/派生指标口径")
        private List<LeafCaliber> leaves;
    }

    @Data
    @Schema(description = "基础指标口径")
    public static class LeafCaliber {

        private Long indicatorId;

        private String code;

        private String name;

        private String indicatorType;

        private String aggregation;

        private String valueField;

        private String timeField;

        @Schema(description = "派生指标统计周期")
        private String timePeriod;

        @Schema(description = "生效业务限定")
        private List<Condition> conditions;

        private Source source;
    }

    @Data
    @Schema(description = "业务限定")
    public static class Condition {

        private Long id;

        private String code;

        private String name;

        private String expression;
    }

    @Data
    @Schema(description = "来源物理表")
    public static class Source {

        private Long modelId;

        private Long materializedId;

        private Long datasourceId;

        private String datasourceName;

        private String schemaName;

        private String tableName;
    }
}
