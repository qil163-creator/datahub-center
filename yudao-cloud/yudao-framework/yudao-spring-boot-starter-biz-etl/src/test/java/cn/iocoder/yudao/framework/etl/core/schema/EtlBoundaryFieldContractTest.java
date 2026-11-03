package cn.iocoder.yudao.framework.etl.core.schema;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander;
import cn.iocoder.yudao.framework.etl.core.engine.*;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlBoundaryFieldContractTest {
    @Test void exactLargeDecimalCrossesTheBoundaryWithoutRoundingOrChangingRepresentation() {
        var value = new BigDecimal("12345678901234567890.123456789012345678");
        var result = run(Map.of("amount", value), field(false, 38, 18));
        assertTrue(result.result().isSuccess(), result.result().getFirstError());
        assertEquals(1, result.rows().size()); assertSame(value, result.rows().getFirst().get("amount"));
    }

    @Test void invalidPrecisionScaleAndApproximateValuesNeverReachTheConsumer() {
        for (Object value : List.of(new BigDecimal("1234.56"), new BigDecimal("1.234"), 1.23d, Float.valueOf("1.23"))) {
            var result = run(Map.of("amount", value), field(false, 5, 2));
            assertFalse(result.result().isSuccess()); assertTrue(result.rows().isEmpty());
            assertNotNull(result.result().getFirstError());
        }
    }

    @Test void negativeScaleAndScaleGreaterThanPrecisionValidateExactRepresentability() {
        var valid = run(Map.of("amount", new BigInteger("12300")), field(false, 3, -2));
        assertTrue(valid.result().isSuccess(), valid.result().getFirstError());
        assertEquals(new BigInteger("12300"), valid.rows().getFirst().get("amount"));
        var invalid = run(Map.of("amount", new BigInteger("12301")), field(false, 3, -2));
        assertFalse(invalid.result().isSuccess()); assertTrue(invalid.rows().isEmpty());
        assertTrue(run(Map.of("amount", new BigDecimal("0.0012")), field(false, 2, 4)).result().isSuccess());
        assertFalse(run(Map.of("amount", new BigDecimal("0.0123")), field(false, 2, 4)).result().isSuccess());
    }

    @Test void individualDecimalConstraintsAreExecutableAndLegacyNullabilityStaysOptional() {
        assertTrue(run(Map.of("amount", new BigDecimal("1.23")), Map.of("name", "amount", "type", "Decimal", "precision", 3)).result().isSuccess());
        assertFalse(run(Map.of("amount", new BigDecimal("12.34")), Map.of("name", "amount", "type", "Decimal", "precision", 3)).result().isSuccess());
        assertTrue(run(Map.of("amount", new BigDecimal("123456.12")), Map.of("name", "amount", "type", "Decimal", "scale", 2)).result().isSuccess());
        assertFalse(run(Map.of("amount", new BigDecimal("0.001")), Map.of("name", "amount", "type", "Decimal", "scale", 2)).result().isSuccess());
        Map<String, Object> nullRow = new LinkedHashMap<>(); nullRow.put("amount", null);
        var legacy = Map.<String, Object>of("name", "amount", "type", "Decimal");
        assertTrue(run(nullRow, legacy).result().isSuccess());
        assertNull(EtlFieldContract.parse(legacy).metadata().getNullable());
        var nonNull = run(nullRow, field(false, 5, 2));
        assertFalse(nonNull.result().isSuccess()); assertTrue(nonNull.rows().isEmpty());
        var missing = run(Map.of(), Map.of("name", "amount", "type", "Decimal", "nullable", true));
        assertFalse(missing.result().isSuccess()); assertTrue(missing.rows().isEmpty());
    }

    @Test void invalidDeclarationsAreRejectedByCompilationBeforeRowsCanRun() {
        for (Map<String, Object> declaration : List.of(
                Map.<String, Object>of("name", "amount", "type", "Decimal", "nullable", "false"),
                Map.<String, Object>of("name", "amount", "type", "Decimal", "precision", "38"),
                Map.<String, Object>of("name", "amount", "type", "Decimal", "precision", 0),
                Map.<String, Object>of("name", "amount", "type", "Decimal", "precision", 1001),
                Map.<String, Object>of("name", "amount", "type", "Decimal", "scale", 1001),
                Map.<String, Object>of("name", "amount", "type", "Decimal", "scale", -1001),
                Map.<String, Object>of("name", "amount", "type", "String", "precision", 38),
                Map.<String, Object>of("name", "amount", "type", "Date", "timezone", "Asia/Shanghai"))) {
            var input = node("input", "boundary-input", 1, Map.of("portName", "input", "fields", List.of(declaration)));
            var output = node("output", "boundary-output", 3, Map.of("portName", "output", "fields", List.of(Map.of("name", "amount"))));
            assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.validateDefinition(
                    List.of(input, output), List.of(edge("input", "output"))), declaration.toString());
        }
        var contract = EtlFieldContract.parse(JSONUtil.parseObj("{\"name\":\"amount\",\"type\":\"Decimal\",\"nullable\":null,\"timezone\":null}"));
        assertNull(contract.nullable()); assertNull(contract.timezone());
    }

    private Result run(Map<String, Object> row, Map<String, Object> field) {
        var registry = new EtlExecutorRegistry(); var rows = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        registry.register("contract-source", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception { pushRow(new LinkedHashMap<>(row)); }
        });
        registry.register("contract-target", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception { Map<String, Object> value; while ((value = readNextRow()) != null) rows.add(value); }
        });
        var source = node("source", "contract-source", 1, Map.of());
        var boundary = node("boundary", "boundary-bridge", 2, Map.of("fields", List.of(field)));
        var target = node("target", "contract-target", 3, Map.of());
        try (var engine = new EtlPipelineEngine(registry)) {
            var result = engine.execute(EtlDag.build(List.of(source, boundary, target),
                    List.of(edge("source", "boundary"), edge("boundary", "target"))), EtlRunOptions.builder().timeoutSeconds(5).build());
            return new Result(result, List.copyOf(rows));
        }
    }
    private record Result(cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult result, List<Map<String, Object>> rows) { }
    private static Map<String, Object> field(boolean nullable, int precision, int scale) {
        return Map.of("name", "amount", "type", "Decimal", "nullable", nullable, "precision", precision, "scale", scale);
    }
    private static EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type);
        node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String source, String target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setEdgeType("DATA"); return edge;
    }
}
