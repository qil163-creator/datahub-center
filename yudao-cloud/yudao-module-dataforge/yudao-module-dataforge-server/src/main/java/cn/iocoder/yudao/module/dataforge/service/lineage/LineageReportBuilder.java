package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageColumnEdge;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageIssue;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTableEdge;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageResult;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageColumnEdgeDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageIssueDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageTableEdgeDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageEdgeOriginEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageReportStatusEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组装一个来源对象的血缘上报：去重、校验长度、执行契约上限并计算内容摘要。
 * <p>
 * 摘要只覆盖规范化后的状态、问题码与边集合，与边的收集顺序无关；相同内容重复上报得到 NOOP。
 */
public final class LineageReportBuilder {

    private final String sourceType;
    private final Long sourceId;
    private final String sourceName;
    private final long sourceRevision;
    private final int analyzerVersion;
    private SqlLineageStatus status = SqlLineageStatus.SUCCESS;
    private final Map<String, LineageTableEdgeDTO> tableEdges = new LinkedHashMap<>();
    private final Map<String, LineageColumnEdgeDTO> columnEdges = new LinkedHashMap<>();
    private final Map<String, LineageIssueDTO> issues = new LinkedHashMap<>();

    /**
     * 创建构建器。
     */
    public LineageReportBuilder(String sourceType, Long sourceId, String sourceName, long sourceRevision,
                                int analyzerVersion) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceName = StrUtil.maxLength(StrUtil.blankToDefault(sourceName, sourceType + "#" + sourceId),
                LineageContractConstants.MAX_SOURCE_NAME_LENGTH - 3);
        this.sourceRevision = sourceRevision;
        this.analyzerVersion = analyzerVersion;
    }

    /**
     * 合并状态：UNSUPPORTED 与 FAILED 只在尚无更具体结果时生效，有降级问题时为 PARTIAL。
     */
    public LineageReportBuilder status(SqlLineageStatus next) {
        status = merge(status, next);
        return this;
    }

    /**
     * 返回当前状态。
     */
    public SqlLineageStatus status() {
        return status;
    }

    /**
     * 合并 SQL 分析结果：同一数据源内的表。
     */
    public LineageReportBuilder addSql(SqlLineageResult result, Long datasourceId, String origin) {
        status(result.status());
        for (LineageTableEdge edge : result.tableEdges()) {
            tableEdge(table(datasourceId, edge.upstream()), table(datasourceId, edge.downstream()), origin,
                    edge.statementIndex());
        }
        for (LineageColumnEdge edge : result.columnEdges()) {
            columnEdge(table(datasourceId, edge.upstream()), edge.upstreamColumn(),
                    table(datasourceId, edge.downstream()), edge.downstreamColumn(), edge.transform(),
                    edge.ambiguous(), edge.masking(), edge.statementIndex());
        }
        for (LineageIssue issue : result.issues()) {
            issue(issue.code(), issue.message(), issue.statementIndex());
        }
        return this;
    }

    /**
     * 构造表节点引用。
     */
    public static LineageNodeRefDTO table(Long datasourceId, LineageTable table) {
        return LineageNodeRefDTO.table(datasourceId, table.schema(), table.name());
    }

    /**
     * 添加表级边；名称超长的边被丢弃并降级。
     */
    public LineageReportBuilder tableEdge(LineageNodeRefDTO upstream, LineageNodeRefDTO downstream, String origin,
                                          Integer statementIndex) {
        if (!valid(upstream) || !valid(downstream)) {
            return this;
        }
        String key = nodeKey(upstream) + "\u0001" + nodeKey(downstream) + "\u0001" + origin;
        if (tableEdges.containsKey(key)) {
            return this;
        }
        if (tableEdges.size() >= LineageContractConstants.MAX_TABLE_EDGES) {
            truncated();
            return this;
        }
        LineageTableEdgeDTO edge = new LineageTableEdgeDTO();
        edge.setUpstream(upstream);
        edge.setDownstream(downstream);
        edge.setOrigin(origin);
        edge.setStatementIndex(statementIndex);
        tableEdges.put(key, edge);
        return this;
    }

    /**
     * 添加字段级边；名称超长的边被丢弃并降级。
     */
    public LineageReportBuilder columnEdge(LineageNodeRefDTO upstream, String upstreamColumn,
                                           LineageNodeRefDTO downstream, String downstreamColumn,
                                           LineageTransform transform, boolean ambiguous, boolean masking,
                                           Integer statementIndex) {
        if (!valid(upstream) || !valid(downstream) || !validColumn(upstreamColumn) || !validColumn(downstreamColumn)) {
            return this;
        }
        String key = nodeKey(upstream) + "\u0001" + upstreamColumn + "\u0001" + nodeKey(downstream) + "\u0001"
                + downstreamColumn + "\u0001" + transform + "\u0001" + ambiguous + "\u0001" + masking;
        if (columnEdges.containsKey(key)) {
            return this;
        }
        if (columnEdges.size() >= LineageContractConstants.MAX_COLUMN_EDGES) {
            truncated();
            return this;
        }
        LineageColumnEdgeDTO edge = new LineageColumnEdgeDTO();
        edge.setUpstream(upstream);
        edge.setUpstreamColumn(upstreamColumn);
        edge.setDownstream(downstream);
        edge.setDownstreamColumn(downstreamColumn);
        edge.setTransformType(transform.type());
        edge.setTransformSubtype(transform.name());
        edge.setAmbiguous(ambiguous);
        edge.setMasking(masking);
        edge.setStatementIndex(statementIndex);
        columnEdges.put(key, edge);
        return this;
    }

    /**
     * 记录问题；降级类问题使状态至少为 PARTIAL。
     */
    public LineageReportBuilder issue(String code, String message, Integer statementIndex) {
        if (issues.size() >= LineageContractConstants.MAX_ISSUES) {
            return this;
        }
        String safeCode = StrUtil.maxLength(code, LineageContractConstants.MAX_ISSUE_CODE_LENGTH - 3);
        String safeMessage = StrUtil.maxLength(StrUtil.blankToDefault(message, safeCode),
                LineageContractConstants.MAX_ISSUE_MESSAGE_LENGTH - 3);
        issues.putIfAbsent(safeCode + "\u0001" + statementIndex + "\u0001" + safeMessage,
                newIssue(safeCode, safeMessage, statementIndex));
        return this;
    }

    /**
     * 记录降级问题。
     */
    public LineageReportBuilder degrade(String code, String message) {
        issue(code, message, null);
        return status(SqlLineageStatus.PARTIAL);
    }

    /**
     * 当前表级边数（含手工边）。
     */
    public int tableEdgeCount() {
        return tableEdges.size();
    }

    /**
     * 生成上报请求。
     */
    public LineageReportReqDTO build() {
        LineageReportReqDTO req = base(sourceType, sourceId, sourceName, sourceRevision, analyzerVersion);
        req.setStatus(status.name());
        req.setIssues(new ArrayList<>(issues.values()));
        req.setTableEdges(new ArrayList<>(tableEdges.values()));
        req.setColumnEdges(new ArrayList<>(columnEdges.values()));
        req.setContentHash(hash(req.getStatus(), issues.keySet(), tableEdges.keySet(), columnEdges.keySet()));
        return req;
    }

    /**
     * 来源已删除时的上报：不含任何边。
     */
    public static LineageReportReqDTO removed(String sourceType, Long sourceId, String sourceName,
                                              long sourceRevision, int analyzerVersion) {
        LineageReportReqDTO req = base(sourceType, sourceId,
                StrUtil.maxLength(StrUtil.blankToDefault(sourceName, sourceType + "#" + sourceId),
                        LineageContractConstants.MAX_SOURCE_NAME_LENGTH - 3), sourceRevision, analyzerVersion);
        req.setStatus(LineageReportStatusEnum.REMOVED.name());
        req.setContentHash(hash(req.getStatus(), List.of(), List.of(), List.of()));
        return req;
    }

    /**
     * 上报被 governance 拒绝时的替代上报：只保留失败状态与原因，避免同一内容无限重试。
     */
    public static LineageReportReqDTO rejected(LineageReportReqDTO original, String reason) {
        LineageReportReqDTO req = base(original.getSourceType(), original.getSourceId(), original.getSourceName(),
                original.getSourceRevision(), original.getAnalyzerVersion());
        req.setStatus(SqlLineageStatus.FAILED.name());
        String message = "血缘上报内容被拒绝：" + StrUtil.blankToDefault(reason, "未知原因");
        req.setIssues(new ArrayList<>(List.of(newIssue("REPORT_REJECTED",
                StrUtil.maxLength(message, LineageContractConstants.MAX_ISSUE_MESSAGE_LENGTH - 3), null))));
        req.setContentHash(hash(req.getStatus(), List.of("REPORT_REJECTED"), List.of(), List.of()));
        return req;
    }

    /**
     * 手工维护血缘的来源标记。
     */
    public static String manualOrigin() {
        return LineageEdgeOriginEnum.MANUAL.name();
    }

    /**
     * 解析得到的血缘标记。
     */
    public static String parsedOrigin() {
        return LineageEdgeOriginEnum.PARSED.name();
    }

    /**
     * 运行事实得到的血缘标记。
     */
    public static String runtimeOrigin() {
        return LineageEdgeOriginEnum.RUNTIME.name();
    }

    // ==================== 内部 ====================

    /**
     * 构造请求头。
     */
    private static LineageReportReqDTO base(String sourceType, Long sourceId, String sourceName, long sourceRevision,
                                            int analyzerVersion) {
        LineageReportReqDTO req = new LineageReportReqDTO();
        req.setSourceType(sourceType);
        req.setSourceId(sourceId);
        req.setSourceName(sourceName);
        req.setSourceRevision(sourceRevision);
        req.setAnalyzerVersion(analyzerVersion);
        return req;
    }

    /**
     * 构造问题。
     */
    private static LineageIssueDTO newIssue(String code, String message, Integer statementIndex) {
        LineageIssueDTO issue = new LineageIssueDTO();
        issue.setCode(code);
        issue.setMessage(message);
        issue.setStatementIndex(statementIndex);
        return issue;
    }

    /**
     * 计算规范化内容摘要。
     */
    private static String hash(String status, Iterable<String> issues, Iterable<String> tableEdges,
                               Iterable<String> columnEdges) {
        StringBuilder canonical = new StringBuilder(status).append('\u0002');
        appendSorted(canonical, issues);
        appendSorted(canonical, tableEdges);
        appendSorted(canonical, columnEdges);
        return DigestUtil.sha256Hex(canonical.toString());
    }

    /**
     * 按字典序追加，使摘要与收集顺序无关。
     */
    private static void appendSorted(StringBuilder out, Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(null);
        sorted.forEach(value -> out.append(value).append('\u0003'));
        out.append('\u0002');
    }

    /**
     * 节点去重键。
     */
    private static String nodeKey(LineageNodeRefDTO node) {
        return node.getNodeType() + "|" + node.getDatasourceId() + "|" + node.getSchemaName() + "|"
                + node.getTableName() + "|" + node.getExternalKind() + "|" + node.getExternalKey();
    }

    /**
     * 校验节点名称长度。
     */
    private boolean valid(LineageNodeRefDTO node) {
        boolean ok = node != null
                && (node.getSchemaName() == null
                || node.getSchemaName().length() <= LineageContractConstants.MAX_SCHEMA_NAME_LENGTH)
                && (node.getTableName() == null
                || node.getTableName().length() <= LineageContractConstants.MAX_TABLE_NAME_LENGTH)
                && (node.getExternalKey() == null
                || node.getExternalKey().length() <= LineageContractConstants.MAX_EXTERNAL_KEY_LENGTH)
                && (Objects.equals(node.getNodeType(), "EXTERNAL") || node.getDatasourceId() != null);
        if (!ok) {
            degrade("NAME_TOO_LONG", "存在名称超长或缺少数据源的节点，相关血缘边已忽略");
        }
        return ok;
    }

    /**
     * 校验字段名长度。
     */
    private boolean validColumn(String column) {
        boolean ok = StrUtil.isNotBlank(column) && column.length() <= LineageContractConstants.MAX_COLUMN_NAME_LENGTH;
        if (!ok) {
            degrade("NAME_TOO_LONG", "存在名称超长的字段，相关字段血缘已忽略");
        }
        return ok;
    }

    /**
     * 报告截断。
     */
    private void truncated() {
        degrade("TRUNCATED", "血缘边数量超过上报上限，已截断");
    }

    /**
     * 合并状态。
     */
    static SqlLineageStatus merge(SqlLineageStatus current, SqlLineageStatus next) {
        if (next == null || next == current) {
            return current;
        }
        return switch (next) {
            case PARTIAL -> current == SqlLineageStatus.SUCCESS ? SqlLineageStatus.PARTIAL : current;
            case FAILED, UNSUPPORTED -> current == SqlLineageStatus.SUCCESS ? next : current;
            case SUCCESS -> current;
        };
    }
}
