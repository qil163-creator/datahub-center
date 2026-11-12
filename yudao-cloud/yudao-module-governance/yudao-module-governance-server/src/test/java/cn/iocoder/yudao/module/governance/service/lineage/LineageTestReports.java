package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageColumnEdgeDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageIssueDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageTableEdgeDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 血缘测试报告构造器。
 */
public final class LineageTestReports {

    public static final String HASH_A = "a".repeat(64);
    public static final String HASH_B = "b".repeat(64);

    private LineageTestReports() {
    }

    public static LineageReportReqDTO report(long sourceId, long revision, int analyzerVersion, String hash) {
        LineageReportReqDTO req = new LineageReportReqDTO();
        req.setSourceType("DEV_TASK");
        req.setSourceId(sourceId);
        req.setSourceName("task-" + sourceId);
        req.setSourceRevision(revision);
        req.setAnalyzerVersion(analyzerVersion);
        req.setContentHash(hash);
        req.setStatus("SUCCESS");
        req.setIssues(new ArrayList<>(List.of(new LineageIssueDTO("UNSUPPORTED_STATEMENT", "跳过 DDL", 1))));
        return req;
    }

    public static LineageReportReqDTO removed(long sourceId, long revision, int analyzerVersion) {
        LineageReportReqDTO req = report(sourceId, revision, analyzerVersion, HASH_A);
        req.setStatus("REMOVED");
        req.setIssues(new ArrayList<>());
        return req;
    }

    public static LineageNodeRefDTO table(long datasourceId, String schema, String table) {
        return LineageNodeRefDTO.table(datasourceId, schema, table);
    }

    public static LineageTableEdgeDTO tableEdge(LineageNodeRefDTO up, LineageNodeRefDTO down) {
        LineageTableEdgeDTO edge = new LineageTableEdgeDTO();
        edge.setUpstream(up);
        edge.setDownstream(down);
        edge.setOrigin("PARSED");
        edge.setStatementIndex(0);
        return edge;
    }

    public static LineageColumnEdgeDTO columnEdge(LineageNodeRefDTO up, String upColumn, LineageNodeRefDTO down,
            String downColumn, String type, String subtype) {
        LineageColumnEdgeDTO edge = new LineageColumnEdgeDTO();
        edge.setUpstream(up);
        edge.setUpstreamColumn(upColumn);
        edge.setDownstream(down);
        edge.setDownstreamColumn(downColumn);
        edge.setTransformType(type);
        edge.setTransformSubtype(subtype);
        edge.setStatementIndex(0);
        return edge;
    }

    /** ods.orders → dwd.orders，两条字段边 */
    public static LineageReportReqDTO simpleReport(long sourceId, long revision, String hash) {
        LineageReportReqDTO req = report(sourceId, revision, 1, hash);
        var ods = table(1, "ods", "orders");
        var dwd = table(1, "dwd", "orders");
        req.getTableEdges().add(tableEdge(ods, dwd));
        req.getColumnEdges().add(columnEdge(ods, "id", dwd, "id", "DIRECT", "IDENTITY"));
        req.getColumnEdges().add(columnEdge(ods, "amount", dwd, "amount_yuan", "DIRECT", "TRANSFORMATION"));
        return req;
    }

}
