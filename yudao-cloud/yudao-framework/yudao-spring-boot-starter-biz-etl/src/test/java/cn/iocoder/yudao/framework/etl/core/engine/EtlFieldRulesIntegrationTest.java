package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import cn.iocoder.yudao.framework.etl.core.validation.EtlGraphValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EtlFieldRulesIntegrationTest {
    @Test
    void registeredNodesExecuteThroughThePipelineAndRetainInputValues() {
        var original = new LinkedHashMap<String, Object>();
        original.put("text", "  cat  "); original.put("amount", new BigDecimal("9007199254740993.123456789012345678"));
        List<Map<String, Object>> output = Collections.synchronizedList(new ArrayList<>());
        var registry = new EtlExecutorRegistry();
        registry.register("test-source", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception { pushRow(original); }
        });
        registry.register("test-sink", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception {
                Map<String, Object> row;
                while ((row = readNextRow()) != null) output.add(new LinkedHashMap<>(row));
            }
        });
        var nodes = List.of(node("in", "test-source", 1, Map.of()),
                node("trim", "string-operation", 2, Map.of("rules", List.of(Map.of("inputField", "text", "outputField", "clean", "trim", "BOTH", "caseMode", "UPPER")))),
                node("replace", "string-replace", 2, Map.of("rules", List.of(Map.of("inputField", "clean", "search", "CAT", "replacement", "猫")))),
                node("copy", "set-field-value", 2, Map.of("rules", List.of(Map.of("sourceField", "amount", "targetField", "exactCopy")))),
                node("out", "test-sink", 3, Map.of()));
        var edges = chain(nodes);
        assertTrue(EtlGraphValidator.validate(nodes, edges, registry).valid());
        var result = new EtlPipelineEngine(registry).execute(EtlDag.build(nodes, edges), EtlRunOptions.builder().timeoutSeconds(5).build());
        assertTrue(result.isSuccess(), result.getFirstError()); assertEquals(1, output.size());
        assertEquals("猫", output.getFirst().get("clean")); assertSame(original.get("amount"), output.getFirst().get("exactCopy"));
        assertEquals("  cat  ", original.get("text")); assertEquals(2, original.size());
    }

    @Test
    void invalidRulesReportTheirExactPathBeforeExecution() {
        var registry = new EtlExecutorRegistry();
        var source = boundary();
        var invalid = node("replace", "string-replace", 2, Map.of("rules", List.of(Map.of("inputField", "amount", "search", "[", "regex", true))));
        var result = EtlGraphValidator.validate(List.of(source, invalid), List.of(edge("in", "replace")), registry);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> "FIELD_RULE_INVALID".equals(issue.code()) && "rules[0].search".equals(issue.field())));
        invalid.setToolType(3);
        invalid.setNodeJson(JSONUtil.toJsonStr(Map.of("rules", List.of(Map.of("inputField", "amount", "search", "x")))));
        assertTrue(EtlGraphValidator.validate(List.of(source, invalid), List.of(edge("in", "replace")), registry).issues().stream().anyMatch(issue -> "TOOL_TYPE".equals(issue.code())));
    }

    @Test
    void schemaUsesTheOriginalCopySourceAndPreservesExactConstraints() {
        var copy = node("copy", "set-field-value", 2, Map.of("rules", List.of(
                Map.of("sourceField", "amount", "targetField", "other"), Map.of("sourceField", "other", "targetField", "amount"))));
        var result = schema(copy);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var amount = field(result, "copy", "amount"); var other = field(result, "copy", "other");
        assertEquals("Long", amount.getDataType()); assertEquals(Boolean.TRUE, amount.getNullable()); assertNull(amount.getPrecision());
        assertEquals("Decimal", other.getDataType()); assertEquals(38, other.getPrecision()); assertEquals(18, other.getScale()); assertEquals(Boolean.FALSE, other.getNullable());
        assertEquals("Decimal", field(result, "in", "amount").getDataType());
    }

    @Test
    void stringSchemaRemovesNumericConstraintsAndMissingReplacementFieldsAreRejected() {
        for (String code : List.of("string-operation", "string-replace")) {
            var change = node("change", code, 2, Map.of("rules", List.of(Map.of("inputField", "amount", "search", "x"))));
            var result = schema(change);
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            var amount = field(result, "change", "amount");
            assertEquals("String", amount.getDataType()); assertEquals(Boolean.FALSE, amount.getNullable());
            assertNull(amount.getPrecision()); assertNull(amount.getScale()); assertNull(amount.getTimezone());
            assertEquals("Long", field(result, "change", "other").getDataType());
        }
        var invalid = node("replace", "string-replace", 2, Map.of("rules", List.of(Map.of("inputField", "amount", "search", "x", "replacementField", "missing"))));
        assertTrue(schema(invalid).getErrors().stream().anyMatch(error -> error.contains("missing")));
    }

    private static EtlSchemaResult schema(EtlNodeDef change) {
        return new EtlSchemaService(mock(DbSwitchRuntime.class)).resolve(EtlDag.build(List.of(boundary(), change), List.of(edge("in", change.getNo()))), null, null);
    }
    private static EtlNodeDef boundary() {
        return node("in", "boundary-input", 1, Map.of("fields", List.of(
                Map.of("name", "amount", "type", "Decimal", "precision", 38, "scale", 18, "nullable", false),
                Map.of("name", "other", "type", "Long", "nullable", true))));
    }
    private static EtlFieldMeta field(EtlSchemaResult result, String node, String name) {
        return result.getNodes().get(node).getOutputFields().stream().filter(field -> name.equals(field.getName())).findFirst().orElseThrow();
    }
    private static EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String source, String target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setEdgeType("DATA"); return edge;
    }
    private static List<EtlEdgeDef> chain(List<EtlNodeDef> nodes) {
        List<EtlEdgeDef> edges = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) edges.add(edge(nodes.get(i - 1).getNo(), nodes.get(i).getNo()));
        return edges;
    }
}
