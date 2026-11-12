package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageIssueDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static cn.iocoder.yudao.module.governance.service.lineage.LineageTestReports.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineageReportNormalizerTest {

    @Test
    void normalizesNodeIdentityColumnsAndMergesDuplicates() {
        LineageReportReqDTO req = report(7, 3, 2, HASH_A);
        var upper = table(1, " ODS ", "Orders");
        upper.setDisplayName("订单原始表");
        var lower = table(1, "ods", "orders");
        var target = table(1, null, "dwd_orders");
        req.getTableEdges().add(tableEdge(lower, target));
        var duplicate = tableEdge(upper, target);
        duplicate.setStatementIndex(null);
        req.getTableEdges().add(duplicate);
        var late = tableEdge(lower, target);
        late.setStatementIndex(4);
        req.getTableEdges().add(late);
        var first = columnEdge(upper, " Amount ", target, "AMOUNT", "DIRECT", "TRANSFORMATION");
        first.setStatementIndex(3);
        var second = columnEdge(lower, "amount", target, "amount", "DIRECT", "TRANSFORMATION");
        second.setMasking(true);
        second.setStatementIndex(1);
        req.getColumnEdges().add(first);
        req.getColumnEdges().add(second);
        req.getColumnEdges().add(columnEdge(lower, "*", target, "cnt", "DIRECT", "AGGREGATION"));

        LineageNormalizedReport result = LineageReportNormalizer.normalize(req);

        assertThat(result.nodes()).containsOnlyKeys("TABLE|1|ods|orders", "TABLE|1||dwd_orders");
        var ods = result.nodes().get("TABLE|1|ods|orders");
        assertThat(ods.schemaName()).isEqualTo("ods");
        assertThat(ods.tableName()).isEqualTo("orders");
        assertThat(ods.displayName()).isEqualTo("订单原始表");
        assertThat(result.tableEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.origin()).isEqualTo("PARSED");
            assertThat(edge.statementIndex()).isZero();
        });
        assertThat(result.columnEdges()).hasSize(2);
        assertThat(result.columnEdges().get(0)).satisfies(edge -> {
            assertThat(edge.upstreamColumn()).isEqualTo("amount");
            assertThat(edge.downstreamColumn()).isEqualTo("amount");
            assertThat(edge.masking()).isTrue();
            assertThat(edge.ambiguous()).isFalse();
            assertThat(edge.statementIndex()).isEqualTo(1);
        });
        assertThat(result.columnEdges().get(1).upstreamColumn()).isEqualTo("*");
        List<?> issues = JsonUtils.parseArray(result.issuesJson(), Map.class);
        assertThat(issues).hasSize(1);
        assertThat(result.issuesJson()).contains("UNSUPPORTED_STATEMENT", "\"statementIndex\":1");
    }

    @Test
    void externalNodesIgnoreTableComponentsAndKeepKeyCase() {
        LineageReportReqDTO req = report(1, 0, 1, HASH_A);
        var topic = LineageNodeRefDTO.external("KAFKA", " Orders.Events ");
        topic.setDatasourceId(9L);
        topic.setTableName("ignored");
        req.getTableEdges().add(tableEdge(topic, table(2, "dw", "events")));

        var result = LineageReportNormalizer.normalize(req);

        var node = result.nodes().get("EXTERNAL|KAFKA|Orders.Events");
        assertThat(node).isNotNull();
        assertThat(node.datasourceId()).isNull();
        assertThat(node.tableName()).isNull();
        assertThat(node.externalKey()).isEqualTo("Orders.Events");
        assertThat(LineageNodeKeys.externalKey("KAFKA", "orders.events")).isNotEqualTo(node.nodeKey());
    }

    @Test
    void separatorsAreEscapedAndLongKeysAreHashed() {
        assertThat(LineageNodeKeys.tableKey(1L, "a|b", "c")).isNotEqualTo(LineageNodeKeys.tableKey(1L, "a", "b|c"));
        String key = LineageNodeKeys.externalKey("OTHER", "x".repeat(LineageContractConstants.MAX_EXTERNAL_KEY_LENGTH));
        assertThat(key).startsWith("SHA256:").hasSize(7 + 64);
        assertThat(LineageNodeKeys.externalKey("OTHER", "x".repeat(512))).isEqualTo(key);
        assertThat(LineageNodeKeys.tableKey(1L, null, "t")).isEqualTo(LineageNodeKeys.tableKey(1L, " ", "T"));
    }

    @Test
    void rejectsOverLimitReports() {
        assertInvalid(req -> {
            for (int i = 0; i <= LineageContractConstants.MAX_ISSUES; i++) {
                req.getIssues().add(new LineageIssueDTO("C", "m", null));
            }
        }, "issues");
        assertInvalid(req -> {
            for (int i = 0; i <= LineageContractConstants.MAX_TABLE_EDGES; i++) {
                req.getTableEdges().add(tableEdge(table(1, "s", "a" + i), table(1, "s", "b")));
            }
        }, "tableEdges");
        assertInvalid(req -> {
            var up = table(1, "s", "a");
            var down = table(1, "s", "b");
            for (int i = 0; i <= LineageContractConstants.MAX_COLUMN_EDGES; i++) {
                req.getColumnEdges().add(columnEdge(up, "c" + i, down, "c", "DIRECT", "IDENTITY"));
            }
        }, "columnEdges");
    }

    @Test
    void rejectsIllegalNodesAndFields() {
        assertInvalid(req -> req.getTableEdges().add(tableEdge(table(1, "s", "a"),
                LineageNodeRefDTO.table(null, "s", "b"))), "datasourceId");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(table(1, "s", " "), table(1, "s", "b"))), "tableName");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(table(1, "s", "t".repeat(256)), table(1, "s", "b"))),
                "tableName");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(table(1, "s".repeat(129), "a"), table(1, "s", "b"))),
                "schemaName");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(LineageNodeRefDTO.external("KAFKA", " "),
                table(1, "s", "b"))), "externalKey");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(LineageNodeRefDTO.external("S3", "bucket/x"),
                table(1, "s", "b"))), "externalKind");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(LineageNodeRefDTO.external("FILE", "x".repeat(513)),
                table(1, "s", "b"))), "externalKey");
        assertInvalid(req -> {
            var ref = table(1, "s", "a");
            ref.setNodeType("VIEW");
            req.getTableEdges().add(tableEdge(ref, table(1, "s", "b")));
        }, "nodeType");
        assertInvalid(req -> req.getTableEdges().add(tableEdge(table(1, "s", "a"), null)), "downstream");
        assertInvalid(req -> {
            var edge = tableEdge(table(1, "s", "a"), table(1, "s", "b"));
            edge.setOrigin("GUESS");
            req.getTableEdges().add(edge);
        }, "origin");
        assertInvalid(req -> req.getColumnEdges().add(columnEdge(table(1, "s", "a"), "c", table(1, "s", "b"), "c",
                "DIRECT", "JOIN")), "子类与大类不匹配");
        assertInvalid(req -> req.getColumnEdges().add(columnEdge(table(1, "s", "a"), "c".repeat(256),
                table(1, "s", "b"), "c", "DIRECT", "IDENTITY")), "upstreamColumn");
        assertInvalid(req -> req.getColumnEdges().add(columnEdge(table(1, "s", "a"), "c", table(1, "s", "b"), " ",
                "INDIRECT", "FILTER")), "downstreamColumn");
        assertInvalid(req -> {
            var edge = tableEdge(table(1, "s", "a"), table(1, "s", "b"));
            edge.setStatementIndex(-1);
            req.getTableEdges().add(edge);
        }, "statementIndex");
    }

    @Test
    void rejectsCredentialsInExternalKeys() {
        for (String key : List.of("hdfs://etl:s3cret@nn:8020/warehouse", "https://api.example.com/x?token=abc",
                "jdbc:mongodb://h/db;password=x", "s3://bucket/a?AccessKey=AK")) {
            assertInvalid(req -> req.getTableEdges().add(tableEdge(LineageNodeRefDTO.external("OTHER", key),
                    table(1, "s", "b"))), "凭据");
        }
        assertThat(LineageReportNormalizer.containsCredential("user_token_events")).isFalse();
        assertThat(LineageReportNormalizer.containsCredential("hdfs://nn:8020/user/etl@team/path")).isFalse();
        assertThat(LineageReportNormalizer.containsCredential("/api/auth/login")).isFalse();
    }

    @Test
    void rejectsIllegalHeaderFieldsAndRemovedWithEdges() {
        assertInvalid(req -> req.setSourceType("JOB"), "sourceType");
        assertInvalid(req -> req.setSourceId(0L), "sourceId");
        assertInvalid(req -> req.setSourceName(" "), "sourceName");
        assertInvalid(req -> req.setSourceName("n".repeat(256)), "sourceName");
        assertInvalid(req -> req.setSourceRevision(-1L), "sourceRevision");
        assertInvalid(req -> req.setAnalyzerVersion(0), "analyzerVersion");
        assertInvalid(req -> req.setContentHash("A".repeat(64)), "contentHash");
        assertInvalid(req -> req.setStatus("DONE"), "status");
        assertInvalid(req -> req.getIssues().add(new LineageIssueDTO("C".repeat(65), "m", null)), "code");
        assertInvalid(req -> req.getIssues().add(new LineageIssueDTO("C", "m".repeat(501), null)), "message");
        assertInvalid(req -> {
            req.setStatus("REMOVED");
            req.getTableEdges().add(tableEdge(table(1, "s", "a"), table(1, "s", "b")));
        }, "REMOVED");
        assertThatThrownBy(() -> LineageReportNormalizer.normalize(null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void acceptsNullCollectionsAsEmpty() {
        LineageReportReqDTO req = report(1, 0, 1, HASH_A);
        req.setIssues(null);
        req.setTableEdges(null);
        req.setColumnEdges(null);
        var result = LineageReportNormalizer.normalize(req);
        assertThat(result.nodes()).isEmpty();
        assertThat(result.issuesJson()).isEqualTo("[]");
    }

    private static void assertInvalid(Consumer<LineageReportReqDTO> mutation, String detail) {
        LineageReportReqDTO req = report(1, 1, 1, HASH_A);
        mutation.accept(req);
        assertThatThrownBy(() -> LineageReportNormalizer.normalize(req))
                .isInstanceOfSatisfying(ServiceException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.LINEAGE_REPORT_INVALID.getCode());
                    assertThat(error.getMessage()).contains(detail);
                });
    }

}
