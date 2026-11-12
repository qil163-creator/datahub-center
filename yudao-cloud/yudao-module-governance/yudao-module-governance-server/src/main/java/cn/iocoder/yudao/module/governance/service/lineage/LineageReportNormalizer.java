package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageColumnEdgeDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageIssueDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageTableEdgeDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageEdgeOriginEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageExternalKindEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageNodeTypeEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageReportStatusEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageTransformSubtypeEnum;
import cn.iocoder.yudao.module.governance.service.lineage.LineageNormalizedReport.ColumnEdgeSpec;
import cn.iocoder.yudao.module.governance.service.lineage.LineageNormalizedReport.NodeSpec;
import cn.iocoder.yudao.module.governance.service.lineage.LineageNormalizedReport.TableEdgeSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.LINEAGE_REPORT_INVALID;

/**
 * 血缘报告的服务端校验与规范化。
 *
 * <p>Feign 入口的 Bean Validation 只覆盖静态格式；这里重复校验全部规则并补充条件性规则（节点形状、子类与大类匹配、
 * 外部标识不得含凭据、REMOVED 不得带边），任何违规都抛 {@code LINEAGE_REPORT_INVALID}，整份报告不落库。
 * 与节点类型无关的分量（TABLE 的 externalKind/externalKey、EXTERNAL 的 datasourceId/schemaName/tableName）被忽略。</p>
 */
public final class LineageReportNormalizer {

    private static final Pattern CONTENT_HASH = Pattern.compile(LineageContractConstants.CONTENT_HASH_PATTERN);

    /** URL 用户信息中带口令，如 scheme://user:secret@host */
    private static final Pattern URL_PASSWORD = Pattern.compile("://[^/@\\s:]+:[^/@\\s]*@");

    /** 查询串或键值对中的敏感参数，如 ?token=、;password= */
    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)(?:^|[?&;,\\s])(?:password|passwd|pwd|secret|token|access[_-]?key|secret[_-]?key|api[_-]?key"
                    + "|credential|signature|sig)\\s*=");

    private LineageReportNormalizer() {
    }

    /**
     * 校验并规范化报告；不合法时抛出 LINEAGE_REPORT_INVALID。
     */
    public static LineageNormalizedReport normalize(LineageReportReqDTO req) {
        if (req == null) {
            throw invalid("报告不能为空");
        }
        String sourceType = req.getSourceType();
        require(LineageSourceTypeEnum.isValid(sourceType), "sourceType 取值非法：" + sourceType);
        require(req.getSourceId() != null && req.getSourceId() > 0, "sourceId 必须为正数");
        String sourceName = LineageNodeKeys.blankToNull(req.getSourceName());
        require(sourceName != null, "sourceName 不能为空");
        require(sourceName.length() <= LineageContractConstants.MAX_SOURCE_NAME_LENGTH, "sourceName 不能超过 255 个字符");
        require(req.getSourceRevision() != null && req.getSourceRevision() >= 0, "sourceRevision 必须大于等于 0");
        require(req.getAnalyzerVersion() != null && req.getAnalyzerVersion() >= 1, "analyzerVersion 必须大于等于 1");
        require(req.getContentHash() != null && CONTENT_HASH.matcher(req.getContentHash()).matches(),
                "contentHash 必须是 64 位小写十六进制");
        String status = req.getStatus();
        require(LineageReportStatusEnum.isValid(status), "status 取值非法：" + status);

        List<LineageIssueDTO> issues = nullToEmpty(req.getIssues());
        List<LineageTableEdgeDTO> tableEdges = nullToEmpty(req.getTableEdges());
        List<LineageColumnEdgeDTO> columnEdges = nullToEmpty(req.getColumnEdges());
        require(issues.size() <= LineageContractConstants.MAX_ISSUES, "issues 不能超过 200 条");
        require(tableEdges.size() <= LineageContractConstants.MAX_TABLE_EDGES, "tableEdges 不能超过 2000 条");
        require(columnEdges.size() <= LineageContractConstants.MAX_COLUMN_EDGES, "columnEdges 不能超过 20000 条");
        if (LineageReportStatusEnum.REMOVED.name().equals(status)) {
            require(tableEdges.isEmpty() && columnEdges.isEmpty(), "REMOVED 报告不能携带血缘边");
        }

        String issuesJson = JsonUtils.toJsonString(normalizeIssues(issues));
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        Map<TableEdgeKey, TableEdgeSpec> tables = new LinkedHashMap<>();
        for (int i = 0; i < tableEdges.size(); i++) {
            String at = "tableEdges[" + i + "]";
            LineageTableEdgeDTO edge = tableEdges.get(i);
            require(edge != null, at + " 不能为空");
            String up = registerNode(nodes, edge.getUpstream(), at + ".upstream");
            String down = registerNode(nodes, edge.getDownstream(), at + ".downstream");
            require(LineageEdgeOriginEnum.isValid(edge.getOrigin()), at + ".origin 取值非法：" + edge.getOrigin());
            Integer statementIndex = statementIndex(edge.getStatementIndex(), at);
            tables.merge(new TableEdgeKey(up, down, edge.getOrigin()),
                    new TableEdgeSpec(up, down, edge.getOrigin(), statementIndex),
                    (left, right) -> new TableEdgeSpec(up, down, left.origin(),
                            minIndex(left.statementIndex(), right.statementIndex())));
        }
        Map<ColumnEdgeKey, ColumnEdgeSpec> columns = new LinkedHashMap<>();
        for (int i = 0; i < columnEdges.size(); i++) {
            String at = "columnEdges[" + i + "]";
            LineageColumnEdgeDTO edge = columnEdges.get(i);
            require(edge != null, at + " 不能为空");
            String up = registerNode(nodes, edge.getUpstream(), at + ".upstream");
            String down = registerNode(nodes, edge.getDownstream(), at + ".downstream");
            String upColumn = columnName(edge.getUpstreamColumn(), at + ".upstreamColumn");
            String downColumn = columnName(edge.getDownstreamColumn(), at + ".downstreamColumn");
            require(LineageTransformSubtypeEnum.matches(edge.getTransformType(), edge.getTransformSubtype()),
                    at + " 变换类型非法或子类与大类不匹配：" + edge.getTransformType() + "/" + edge.getTransformSubtype());
            Integer statementIndex = statementIndex(edge.getStatementIndex(), at);
            ColumnEdgeSpec spec = new ColumnEdgeSpec(up, upColumn, down, downColumn, edge.getTransformType(),
                    edge.getTransformSubtype(), Boolean.TRUE.equals(edge.getAmbiguous()),
                    Boolean.TRUE.equals(edge.getMasking()), statementIndex);
            columns.merge(new ColumnEdgeKey(up, upColumn, down, downColumn, spec.transformType(),
                    spec.transformSubtype()), spec, LineageReportNormalizer::mergeColumnEdge);
        }
        return new LineageNormalizedReport(sourceType, req.getSourceId(), sourceName, req.getSourceRevision(),
                req.getAnalyzerVersion(), req.getContentHash(), status, issuesJson, nodes,
                new ArrayList<>(tables.values()), new ArrayList<>(columns.values()));
    }

    /**
     * 判断外部数据集标识是否疑似携带凭据。
     */
    static boolean containsCredential(String externalKey) {
        return URL_PASSWORD.matcher(externalKey).find() || SECRET_PARAMETER.matcher(externalKey).find();
    }

    private static List<Map<String, Object>> normalizeIssues(List<LineageIssueDTO> issues) {
        List<Map<String, Object>> result = new ArrayList<>(issues.size());
        for (int i = 0; i < issues.size(); i++) {
            String at = "issues[" + i + "]";
            LineageIssueDTO issue = issues.get(i);
            require(issue != null, at + " 不能为空");
            String code = LineageNodeKeys.blankToNull(issue.getCode());
            String message = LineageNodeKeys.blankToNull(issue.getMessage());
            require(code != null && code.length() <= LineageContractConstants.MAX_ISSUE_CODE_LENGTH,
                    at + ".code 不能为空且不能超过 64 个字符");
            require(message != null && message.length() <= LineageContractConstants.MAX_ISSUE_MESSAGE_LENGTH,
                    at + ".message 不能为空且不能超过 500 个字符");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("message", message);
            row.put("statementIndex", statementIndex(issue.getStatementIndex(), at));
            result.add(row);
        }
        return result;
    }

    private static String registerNode(Map<String, NodeSpec> nodes, LineageNodeRefDTO ref, String at) {
        NodeSpec spec = normalizeNode(ref, at);
        NodeSpec existing = nodes.get(spec.nodeKey());
        if (existing == null) {
            nodes.put(spec.nodeKey(), spec);
        } else if (existing.displayName() == null && spec.displayName() != null) {
            nodes.put(spec.nodeKey(), existing.withDisplayName(spec.displayName()));
        }
        return spec.nodeKey();
    }

    private static NodeSpec normalizeNode(LineageNodeRefDTO ref, String at) {
        require(ref != null, at + " 不能为空");
        String displayName = LineageNodeKeys.blankToNull(ref.getDisplayName());
        require(displayName == null || displayName.length() <= LineageContractConstants.MAX_DISPLAY_NAME_LENGTH,
                at + ".displayName 不能超过 255 个字符");
        if (LineageNodeTypeEnum.TABLE.name().equals(ref.getNodeType())) {
            require(ref.getDatasourceId() != null && ref.getDatasourceId() > 0, at + " TABLE 节点必须提供正数 datasourceId");
            String tableName = LineageNodeKeys.blankToNull(ref.getTableName());
            require(tableName != null, at + " TABLE 节点必须提供 tableName");
            require(tableName.length() <= LineageContractConstants.MAX_TABLE_NAME_LENGTH, at + ".tableName 不能超过 255 个字符");
            String schemaName = LineageNodeKeys.blankToNull(ref.getSchemaName());
            require(schemaName == null || schemaName.length() <= LineageContractConstants.MAX_SCHEMA_NAME_LENGTH,
                    at + ".schemaName 不能超过 128 个字符");
            return new NodeSpec(LineageNodeKeys.tableKey(ref.getDatasourceId(), schemaName, tableName),
                    LineageNodeTypeEnum.TABLE.name(), ref.getDatasourceId(), schemaName, tableName, null, null,
                    displayName);
        }
        if (LineageNodeTypeEnum.EXTERNAL.name().equals(ref.getNodeType())) {
            require(LineageExternalKindEnum.isValid(ref.getExternalKind()),
                    at + " EXTERNAL 节点的 externalKind 取值非法：" + ref.getExternalKind());
            String externalKey = LineageNodeKeys.blankToNull(ref.getExternalKey());
            require(externalKey != null, at + " EXTERNAL 节点必须提供 externalKey");
            require(externalKey.length() <= LineageContractConstants.MAX_EXTERNAL_KEY_LENGTH,
                    at + ".externalKey 不能超过 512 个字符");
            require(!containsCredential(externalKey), at + ".externalKey 疑似包含凭据，请去掉用户口令与敏感参数");
            return new NodeSpec(LineageNodeKeys.externalKey(ref.getExternalKind(), externalKey),
                    LineageNodeTypeEnum.EXTERNAL.name(), null, null, null, ref.getExternalKind(), externalKey,
                    displayName);
        }
        throw invalid(at + ".nodeType 取值非法：" + ref.getNodeType());
    }

    private static String columnName(String value, String at) {
        String column = LineageNodeKeys.column(value);
        require(column != null, at + " 不能为空");
        require(column.length() <= LineageContractConstants.MAX_COLUMN_NAME_LENGTH, at + " 不能超过 255 个字符");
        return column;
    }

    private static Integer statementIndex(Integer value, String at) {
        require(value == null || value >= 0, at + ".statementIndex 不能小于 0");
        return value;
    }

    private static ColumnEdgeSpec mergeColumnEdge(ColumnEdgeSpec left, ColumnEdgeSpec right) {
        return new ColumnEdgeSpec(left.upstreamKey(), left.upstreamColumn(), left.downstreamKey(),
                left.downstreamColumn(), left.transformType(), left.transformSubtype(),
                left.ambiguous() || right.ambiguous(), left.masking() || right.masking(),
                minIndex(left.statementIndex(), right.statementIndex()));
    }

    private static Integer minIndex(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : Math.min(left, right);
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw invalid(detail);
        }
    }

    private static RuntimeException invalid(String detail) {
        return exception(LINEAGE_REPORT_INVALID, detail);
    }

    private record TableEdgeKey(String upstreamKey, String downstreamKey, String origin) {
    }

    private record ColumnEdgeKey(String upstreamKey, String upstreamColumn, String downstreamKey,
                                 String downstreamColumn, String transformType, String transformSubtype) {
    }

}
