package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发任务自动血缘预览。只读展示当前保存内容的解析结果，不代表已上报到血缘中心。
 */
@Schema(description = "管理后台 - 开发任务自动血缘预览 Response VO")
@Data
public class DevTaskLineagePreviewRespVO {

    /** 预览中字段边最多返回条数，完整结果在血缘中心查看。 */
    public static final int MAX_COLUMN_EDGES = 500;

    @Schema(description = "解析状态：SUCCESS/PARTIAL/FAILED/UNSUPPORTED", example = "SUCCESS")
    private String status;

    @Schema(description = "解析的语句数", example = "3")
    private Integer statementCount;

    @Schema(description = "解析器版本", example = "101")
    private Integer analyzerVersion;

    @Schema(description = "读取的表")
    private List<TableRef> readTables = new ArrayList<>();

    @Schema(description = "写入的表")
    private List<TableRef> writeTables = new ArrayList<>();

    @Schema(description = "表级血缘边（含手工维护）")
    private List<TableEdge> tableEdges = new ArrayList<>();

    @Schema(description = "字段级血缘边（最多 500 条）")
    private List<ColumnEdge> columnEdges = new ArrayList<>();

    @Schema(description = "字段级血缘边总数", example = "12")
    private Integer columnEdgeTotal;

    @Schema(description = "解析问题")
    private List<Issue> issues = new ArrayList<>();

    @Schema(description = "表引用")
    @Data
    public static class TableRef {
        @Schema(description = "数据源编号", example = "1")
        private Long datasourceId;
        @Schema(description = "schema 名", example = "ods")
        private String schemaName;
        @Schema(description = "表名", example = "orders")
        private String tableName;
    }

    @Schema(description = "表级血缘边")
    @Data
    public static class TableEdge {
        @Schema(description = "上游表")
        private TableRef upstream;
        @Schema(description = "下游表")
        private TableRef downstream;
        @Schema(description = "来源：PARSED-自动解析 MANUAL-手工维护", example = "PARSED")
        private String origin;
    }

    @Schema(description = "字段级血缘边")
    @Data
    public static class ColumnEdge {
        @Schema(description = "上游表")
        private TableRef upstream;
        @Schema(description = "上游字段；* 表示未展开的全列", example = "price")
        private String upstreamColumn;
        @Schema(description = "下游表")
        private TableRef downstream;
        @Schema(description = "下游字段；* 表示整表级依赖", example = "amount")
        private String downstreamColumn;
        @Schema(description = "变换大类：DIRECT/INDIRECT", example = "DIRECT")
        private String transformType;
        @Schema(description = "变换子类", example = "TRANSFORMATION")
        private String transformSubtype;
        @Schema(description = "字段归属是否不确定", example = "false")
        private Boolean ambiguous;
        @Schema(description = "是否经过脱敏或哈希", example = "false")
        private Boolean masking;
    }

    @Schema(description = "解析问题")
    @Data
    public static class Issue {
        @Schema(description = "原因码", example = "WILDCARD_UNEXPANDED")
        private String code;
        @Schema(description = "说明")
        private String message;
        @Schema(description = "语句序号（从 1 开始）", example = "2")
        private Integer statementIndex;
    }
}
