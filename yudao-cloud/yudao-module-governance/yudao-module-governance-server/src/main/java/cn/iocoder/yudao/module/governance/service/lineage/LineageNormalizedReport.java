package cn.iocoder.yudao.module.governance.service.lineage;

import java.util.List;
import java.util.Map;

/**
 * 校验并规范化后的血缘报告：节点按 nodeKey 去重，边在报告内合并重复项，字段名已小写。
 *
 * @param nodes       边引用的全部节点（nodeKey → 节点，保持首次出现顺序）
 * @param tableEdges  去重后的表级边
 * @param columnEdges 去重后的字段级边
 * @param issuesJson  问题列表的 JSON 数组文本
 */
public record LineageNormalizedReport(String sourceType, Long sourceId, String sourceName, long sourceRevision,
                                      int analyzerVersion, String contentHash, String status, String issuesJson,
                                      Map<String, NodeSpec> nodes, List<TableEdgeSpec> tableEdges,
                                      List<ColumnEdgeSpec> columnEdges) {

    /**
     * 规范化节点。schema/表名/展示名保留首次上报的写法，nodeKey 才是身份。
     */
    public record NodeSpec(String nodeKey, String nodeType, Long datasourceId, String schemaName, String tableName,
                           String externalKind, String externalKey, String displayName) {

        NodeSpec withDisplayName(String value) {
            return new NodeSpec(nodeKey, nodeType, datasourceId, schemaName, tableName, externalKind, externalKey, value);
        }
    }

    /**
     * 规范化表级边。
     */
    public record TableEdgeSpec(String upstreamKey, String downstreamKey, String origin, Integer statementIndex) {
    }

    /**
     * 规范化字段级边。
     */
    public record ColumnEdgeSpec(String upstreamKey, String upstreamColumn, String downstreamKey,
                                 String downstreamColumn, String transformType, String transformSubtype,
                                 boolean ambiguous, boolean masking, Integer statementIndex) {
    }

}
