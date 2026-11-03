package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.iocoder.yudao.framework.etl.core.engine.EtlGovernanceNodeTest.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlPreviewEngineTest {
    /** 单字段常量生成来源：所有行相同，需配合 id-generator 才能得到可区分的行。 */
    private static EtlNodeDef generated(String id, int count, String fieldsJson) {
        return node(id, "generate-records", 1, "{\"count\":" + count + ",\"fields\":[" + fieldsJson + "]}");
    }

    @Test
    void previewRunsPureInMemoryTransformChainWithoutExternalAccess() {
        // generate-records → id-generator → data-filter → field-derive → calculator → column-map
        var source = generated("source", 6, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"},"
                + "{\"name\":\"price\",\"type\":\"INTEGER\",\"value\":\"3\"},{\"name\":\"city\",\"value\":\"sh\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\",\"mode\":\"SEQUENCE\",\"start\":1,\"step\":1}");
        var filter = node("filter", "data-filter", 2, "{\"logicalOperator\":\"AND\",\"filterConditions\":"
                + "[{\"fieldName\":\"seq\",\"operator\":\"GREATER_THAN\",\"value\":\"2\"}]}");
        var derive = node("derive", "field-derive", 2, "{\"operateType\":\"CONCAT\",\"targetField\":\"code\","
                + "\"sourceFields\":[\"id\",\"seq\"],\"separator\":\"-\"}");
        var calculator = node("calculator", "calculator", 2, "{\"rules\":[{\"targetField\":\"total\",\"calcType\":\"MUL\","
                + "\"fieldA\":\"price\",\"fieldB\":\"seq\",\"valueType\":\"INTEGER\"}]}");
        var rename = node("rename", "column-map", 2, "{\"regexColumnMapper\":[{\"fromPattern\":\"^price$\",\"toValue\":\"unit_price\"},"
                + "{\"fromPattern\":\"^city$\",\"toValue\":\"\"}]}");
        var result = new EtlPreviewEngine().preview(List.of(source, sequence, filter, derive, calculator, rename),
                List.of(edge("source", "sequence", null), edge("sequence", "filter", null), edge("filter", "derive", null),
                        edge("derive", "calculator", null), edge("calculator", "rename", null)),
                "rename", 100, Map.of(), null);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(4, result.rows().size()); assertEquals(6, result.inputRows()); assertEquals(4, result.outputRows());
        assertFalse(result.truncated());
        assertEquals(List.of("id", "unit_price", "seq", "code", "total"), result.columns().stream().map(EtlFieldMeta::getName).toList());
        var first = result.rows().getFirst();
        assertEquals(7, first.get("id")); assertEquals(3, first.get("unit_price")); assertEquals(3L, first.get("seq"));
        assertEquals("7-3", first.get("code")); assertEquals(9L, first.get("total"));
        assertFalse(first.containsKey("price")); assertFalse(first.containsKey("city"));
        assertEquals(List.of(3L, 4L, 5L, 6L), result.rows().stream().map(row -> row.get("seq")).toList());
    }

    @Test
    void previewJoinsTwoGeneratedSourcesInMemory() {
        var left = generated("left", 3, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"1\"},{\"name\":\"name\",\"value\":\"left\"}");
        var right = generated("right", 2, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"1\"},{\"name\":\"city\",\"value\":\"sh\"}");
        var join = node("join", "multi-join", 2, "{\"joinType\":\"INNER\",\"joinConditions\":[{\"leftField\":\"id\",\"rightField\":\"id\"}]}");
        var result = new EtlPreviewEngine().preview(List.of(left, right, join),
                List.of(edge("left", "join", null), edge("right", "join", null)), "join", 100, Map.of(), null);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(6, result.rows().size()); assertEquals(5, result.inputRows()); assertEquals(6, result.outputRows());
        assertFalse(result.truncated());
        assertTrue(result.rows().stream().allMatch(row -> row.equals(Map.of("id", 1, "name", "left", "city", "sh"))), result.rows().toString());
        assertTrue(result.budget().peakBufferedBytes() > 0);
    }

    @Test
    void previewDeliversOnlyMatchedBranchRowsToConditionDownstream() {
        var source = generated("source", 5, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\"}");
        var condition = node("condition", "data-condition", 2, "{\"unmatchedPolicy\":\"ERROR\",\"branches\":["
                + "{\"id\":\"high\",\"type\":\"IF\",\"fieldName\":\"seq\",\"operator\":\"GT\",\"value\":\"3\"},"
                + "{\"id\":\"low\",\"type\":\"ELSE\"}]}");
        var high = node("high-target", "add-constant", 2, "{\"fields\":[{\"name\":\"tier\",\"value\":\"high\"}]}");
        var low = node("low-target", "add-constant", 2, "{\"fields\":[{\"name\":\"tier\",\"value\":\"low\"}]}");
        List<EtlNodeDef> nodes = List.of(source, sequence, condition, high, low);
        // 连线 outputPort 为分支 id，目标是分支下游节点
        List<EtlEdgeDef> edges = List.of(edge("source", "sequence", null), edge("sequence", "condition", null),
                edge("condition", "high-target", "high"), edge("condition", "low-target", "low"));
        var highResult = new EtlPreviewEngine().preview(nodes, edges, "high-target", 100, Map.of(), null);
        assertTrue(highResult.success(), highResult.issues().toString());
        assertEquals(List.of(4L, 5L), highResult.rows().stream().map(row -> row.get("seq")).toList());
        assertTrue(highResult.rows().stream().allMatch(row -> "high".equals(row.get("tier"))));
        assertEquals(5, highResult.inputRows()); assertEquals(2, highResult.outputRows());
        var lowResult = new EtlPreviewEngine().preview(nodes, edges, "low-target", 100, Map.of(), null);
        assertTrue(lowResult.success(), lowResult.issues().toString());
        assertEquals(List.of(1L, 2L, 3L), lowResult.rows().stream().map(row -> row.get("seq")).toList());
        assertTrue(lowResult.rows().stream().allMatch(row -> "low".equals(row.get("tier"))));
    }

    @Test
    void previewOfBranchingNodeCountsEveryPortAndMarksDisplayedRows() {
        var source = generated("source", 5, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\"}");
        var condition = node("condition", "data-condition", 2, "{\"unmatchedPolicy\":\"ERROR\",\"branches\":["
                + "{\"id\":\"high\",\"type\":\"IF\",\"fieldName\":\"seq\",\"operator\":\"GT\",\"value\":\"3\"},"
                + "{\"id\":\"low\",\"type\":\"ELSE\"}]}");
        List<EtlNodeDef> nodes = List.of(source, sequence, condition);
        List<EtlEdgeDef> edges = List.of(edge("source", "sequence", null), edge("sequence", "condition", null));
        var result = new EtlPreviewEngine().preview(nodes, edges, "condition", 100, Map.of(), null);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(Map.of("low", 3L, "high", 2L), result.portCounts());
        assertEquals(List.of("low", "high"), List.copyOf(result.portCounts().keySet()));
        assertEquals(List.of("low", "low", "low", "high", "high"), result.rowPorts());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), result.rows().stream().map(row -> row.get("seq")).toList());
        // 截断时端口计数覆盖全部样本输出，逐行端口只对应展示行
        var limited = new EtlPreviewEngine().preview(nodes, edges, "condition", 2, Map.of(), null);
        assertTrue(limited.success(), limited.issues().toString()); assertTrue(limited.truncated());
        assertEquals(3, limited.outputRows()); assertEquals(Map.of("low", 3L), limited.portCounts());
        assertEquals(2, limited.rows().size()); assertEquals(List.of("low", "low"), limited.rowPorts());
    }

    @Test
    void qualityRoutePreviewMarksEachSampleRowWithItsPassWarnOrRejectPort() {
        var source = generated("source", 5, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\"}");
        var quality = node("quality", "quality-check", 2, "{\"disposition\":\"ROUTE\"}");
        var policy = new EtlRowPolicyPort() {
            public List<Violation> evaluate(String node, Map<String, Object> row) {
                long seq = ((Number) row.get("seq")).longValue();
                if (seq == 2) return List.of(new Violation("not-null", "seq", "ERROR", "NULL", "private-raw-value"));
                if (seq == 3) return List.of(new Violation("range", "seq", "WARN", "RANGE", "偏大"));
                return List.of();
            }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> issues) { }
            public void complete(String node, Counts counts) { }
        };
        var result = new EtlPreviewEngine().preview(List.of(source, sequence, quality),
                List.of(edge("source", "sequence", null), edge("sequence", "quality", null)), "quality", 100, Map.of(), policy);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(List.of("pass", "reject", "warn", "pass", "pass"), result.rowPorts());
        assertEquals(Map.of("pass", 3L, "reject", 1L, "warn", 1L), result.portCounts());
        assertEquals(List.of("PASS", "REJECT", "WARN", "PASS", "PASS"),
                result.rows().stream().map(row -> row.get("_quality_status")).toList());
        assertEquals(5, result.rowViolations().size());
        assertEquals(List.of(), result.rowViolations().getFirst());
        assertEquals(List.of(new EtlPreviewEngine.RowViolation("not-null", "seq", "ERROR", "NULL")), result.rowViolations().get(1));
        assertFalse(result.rowViolations().toString().contains("private-raw-value"));
        assertDoesNotThrow(() -> UUID.fromString(result.previewId()));
        assertNotNull(result.budget());
        assertEquals(15, result.budget().usedRows());
        assertTrue(result.budget().responseBytes() > 0);
        assertTrue(result.budget().usedBytes() <= result.budget().maxBytes());
        var limited = new EtlPreviewEngine().preview(List.of(source, sequence, quality),
                List.of(edge("source", "sequence", null), edge("sequence", "quality", null)), "quality", 2, Map.of(), policy);
        assertTrue(limited.success(), limited.issues().toString());
        assertEquals(2, limited.rows().size()); assertEquals(2, limited.rowViolations().size());
        assertEquals(3, limited.outputRows()); assertTrue(limited.truncated());
        assertNotEquals(result.previewId(), limited.previewId());
    }

    @Test
    void previewRejectsUnboundedViolationMetadataAndDoesNotReturnPartialRows() {
        var source = generated("source", 1, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}");
        var quality = node("quality", "quality-check", 2, "{}");
        var policy = new EtlRowPolicyPort() {
            public List<Violation> evaluate(String node, Map<String, Object> row) {
                return List.of(new Violation("x".repeat(257), "id", "ERROR", "NULL", "raw private value"));
            }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> issues) { }
            public void complete(String node, Counts counts) { }
        };
        var result = new EtlPreviewEngine().preview(List.of(source, quality), List.of(edge("source", "quality", null)),
                "quality", 100, Map.of(), policy);
        assertFalse(result.success()); assertTrue(result.rows().isEmpty()); assertTrue(result.rowViolations().isEmpty());
        assertTrue(result.inputSamples().isEmpty()); assertEquals(0, result.targetInputRows());
        assertNotNull(result.budget()); assertEquals(1, result.budget().usedRows());
        assertFalse(result.issues().toString().contains("raw private value"));
    }

    @Test
    void previewStillRejectsEncryptionScriptAndExternalSystemNodes() {
        var source = generated("source", 1, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}");
        for (String code : List.of("data-encrypt", "data-decrypt", "write-csv", "insert-db", "js-script", "sql-script", "rest-api",
                "sub-pipeline", "boundary-input", "boundary-output")) {
            var result = new EtlPreviewEngine().preview(List.of(source, node("unsafe", code, 2, "{}")),
                    List.of(edge("source", "unsafe", null)), "unsafe", 100, Map.of(), null);
            assertFalse(result.success(), code); assertTrue(result.rows().isEmpty(), code);
            assertEquals(0, result.inputRows(), code);
            assertTrue(result.portCounts().isEmpty(), code); assertTrue(result.rowPorts().isEmpty(), code);
            var issue = result.issues().getFirst();
            assertEquals("PREVIEW_UNSUPPORTED", issue.code(), code);
            assertTrue(issue.message().contains("不支持 SQL、脚本、加解密或读写外部系统的节点"), issue.message());
        }
    }

    @Test
    void cartesianProductBeyondRowBudgetFailsExplicitlyInsteadOfTruncating() {
        // 两个来源在预览中各被限制为 limit+1=101 行，101×101 加上来源传输行数超过 maxRows=10000，应有界失败而非静默截断
        var left = generated("left", 101, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"1\"}");
        var right = generated("right", 1000, "{\"name\":\"code\",\"value\":\"x\"}");
        var product = node("product", "cartesian-product", 2, "{}");
        var result = new EtlPreviewEngine().preview(List.of(left, right, product),
                List.of(edge("left", "product", null), edge("right", "product", null)), "product", 100, Map.of(), null);
        assertFalse(result.success()); assertTrue(result.rows().isEmpty());
        assertEquals(202, result.inputRows());
        var issue = result.issues().getFirst();
        assertEquals("PREVIEW_FAILED", issue.code());
        assertTrue(issue.message().contains("中间结果超过行数预算"), issue.message());
        assertEquals(202, result.budget().usedRows());
        assertEquals("{\"count\":1000,\"fields\":[{\"name\":\"code\",\"value\":\"x\"}]}", right.getNodeJson());
    }

    @Test
    void previewRejectsDatabaseAndScriptSourcesBeforeExecution() {
        for (String code : List.of("read-db", "sql-script", "js-script", "read-json", "write-json")) {
            var result = new EtlPreviewEngine().preview(List.of(node("unsafe", code, 1, "{}")),
                    List.of(), "unsafe", 100, Map.of(), null);
            assertFalse(result.success(), code); assertTrue(result.rows().isEmpty());
            assertEquals("PREVIEW_UNSUPPORTED", result.issues().getFirst().code());
        }
        var result = new EtlPreviewEngine().preview(List.of(node("db", "read-db", 1, "{}"),
                        node("target", "sort-records", 2, "{}")), List.of(edge("db", "target", null)),
                "target", 100, Map.of(), null);
        assertFalse(result.success()); assertEquals(0, result.inputRows());
    }

    @Test
    void previewBoundsGeneratedRowsAndDoesNotChangeSavedSourceConfiguration() {
        String config = "{\"count\":1000000,\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"7\"}]}";
        var source = node("source", "generate-records", 1, config);
        var result = new EtlPreviewEngine().preview(List.of(source), List.of(), "source", 100, Map.of(), null);
        assertTrue(result.success(), result.issues().toString()); assertEquals(100, result.rows().size());
        assertEquals(101, result.inputRows()); assertEquals(101, result.outputRows()); assertTrue(result.truncated());
        assertEquals(7, result.rows().getFirst().get("id")); assertEquals(config, source.getNodeJson());
        for (int limit : List.of(0, 101)) assertFalse(new EtlPreviewEngine()
                .preview(List.of(source), List.of(), "source", limit, Map.of(), null).success());
        source.setNodeJson(config.replace("1000000", "100"));
        var exact = new EtlPreviewEngine().preview(List.of(source), List.of(), "source", 100, Map.of(), null);
        assertTrue(exact.success()); assertEquals(100, exact.rows().size()); assertFalse(exact.truncated());
    }

    @Test
    void sameSeedReproducesEveryRandomValueIncludingVersionFourUuid() {
        String config = "{\"count\":20,\"seed\":42,\"fields\":[{\"name\":\"uuid\",\"type\":\"UUID\"},"
                + "{\"name\":\"number\",\"type\":\"INTEGER\",\"min\":-10,\"max\":10},"
                + "{\"name\":\"text\",\"type\":\"STRING\",\"length\":8}]}";
        var source = node("source", "random-records", 1, config);
        var first = new EtlPreviewEngine().preview(List.of(source), List.of(), "source", 100, Map.of(), null);
        var second = new EtlPreviewEngine().preview(List.of(source), List.of(), "source", 100, Map.of(), null);
        assertTrue(first.success(), first.issues().toString()); assertTrue(second.success()); assertEquals(first.rows(), second.rows());
        assertEquals(20, first.rows().stream().map(row -> row.get("uuid")).distinct().count());
        first.rows().forEach(row -> {
            UUID uuid = UUID.fromString((String) row.get("uuid")); assertEquals(4, uuid.version()); assertEquals(2, uuid.variant());
        });
        source.setNodeJson(config.replace("\"seed\":42", "\"seed\":43"));
        var changed = new EtlPreviewEngine().preview(List.of(source), List.of(), "source", 100, Map.of(), null);
        assertTrue(changed.success()); assertNotEquals(first.rows(), changed.rows());
    }
}
