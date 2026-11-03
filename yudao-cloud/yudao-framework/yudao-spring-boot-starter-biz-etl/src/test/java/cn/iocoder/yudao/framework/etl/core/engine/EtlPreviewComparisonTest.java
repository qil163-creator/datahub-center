package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.etl.core.engine.EtlGovernanceNodeTest.edge;
import static cn.iocoder.yudao.framework.etl.core.engine.EtlGovernanceNodeTest.node;
import static org.junit.jupiter.api.Assertions.*;

class EtlPreviewComparisonTest {
    @Test void filteringCountsTargetInputsIndependentlyFromSourcesAndOutputs() {
        var source = generated("source", 6, "{\"name\":\"id\",\"value\":\"x\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\",\"start\":1}");
        var first = node("first", "data-filter", 2, "{\"filterConditions\":[{\"fieldName\":\"seq\",\"operator\":\"GREATER_THAN\",\"value\":\"2\"}]}");
        var target = node("target", "data-filter", 2, "{\"filterConditions\":[{\"fieldName\":\"seq\",\"operator\":\"GREATER_THAN\",\"value\":\"4\"}]}");
        var result = preview(List.of(source, sequence, first, target), List.of(edge("source", "sequence", null),
                edge("sequence", "first", null), edge("first", "target", null)), "target", 100);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(6, result.inputRows()); assertEquals(4, result.targetInputRows()); assertEquals(2, result.outputRows());
        assertEquals(List.of(3L, 4L, 5L, 6L), result.inputSamples().getFirst().rows().stream().map(row -> row.get("seq")).toList());
        assertEquals(List.of(5L, 6L), result.rows().stream().map(row -> row.get("seq")).toList());
        assertEquals("INDEPENDENT_SAMPLES", result.comparisonMode());
    }

    @Test void sortingAndExpansionDoNotInventRowIdentityOrOneToOneAlignment() {
        var source = generated("source", 3, "{\"name\":\"parts\",\"value\":\"a,b\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\"}");
        var sorted = node("sorted", "sort-records", 2, "{\"sorts\":[{\"field\":\"seq\",\"order\":\"DESC\"}]}");
        var result = preview(List.of(source, sequence, sorted), List.of(edge("source", "sequence", null), edge("sequence", "sorted", null)), "sorted", 100);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(1L, result.inputSamples().getFirst().rows().getFirst().get("seq"));
        assertEquals(3L, result.rows().getFirst().get("seq"));

        var expanded = node("expanded", "split-to-rows", 2, "{\"sourceField\":\"parts\",\"delimiter\":\",\"}");
        result = preview(List.of(source, expanded), List.of(edge("source", "expanded", null)), "expanded", 100);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(3, result.targetInputRows()); assertEquals(6, result.outputRows());
        assertEquals("a,b", result.inputSamples().getFirst().rows().getFirst().get("parts"));
        assertEquals(List.of("a", "b", "a", "b", "a", "b"), result.rows().stream().map(row -> row.get("parts")).toList());
    }

    @Test void joinsReturnOrderedSeparateInputPortsAndOneAggregateInputRowBudget() {
        var left = generated("left", 5, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"1\"}");
        var right = generated("right", 5, "{\"name\":\"id\",\"type\":\"INTEGER\",\"value\":\"1\"}");
        var join = node("join", "multi-join", 2, "{\"joinType\":\"INNER\",\"joinConditions\":[{\"leftField\":\"id\",\"rightField\":\"id\"}]}");
        var second = edge("right", "join", null); second.setInputPort("input-2");
        var first = edge("left", "join", null); first.setInputPort("input-1");
        var result = preview(List.of(left, right, join), List.of(second, first), "join", 3);
        assertTrue(result.success(), result.issues().toString());
        assertEquals(8, result.targetInputRows()); assertEquals(16, result.outputRows());
        assertEquals(List.of("input-1", "input-2"), result.inputSamples().stream().map(EtlPreviewEngine.InputSample::inputPort).toList());
        assertEquals(List.of("left", "right"), result.inputSamples().stream().map(EtlPreviewEngine.InputSample::sourceNodeNo).toList());
        assertEquals(List.of(2, 1), result.inputSamples().stream().map(sample -> sample.rows().size()).toList());
        assertTrue(result.inputSamples().stream().allMatch(sample -> sample.rowCount() == 4 && sample.truncated()));
        assertEquals(3, result.rows().size()); assertEquals(6, result.budget().maxResponseRows());
        assertEquals(3, result.inputSamples().stream().mapToInt(sample -> sample.rows().size()).sum());
        assertTrue(result.budget().responseBytes() <= result.budget().maxBytes());
    }

    @Test void inputCaptureHonorsOnlyTheConnectedBranchAndLegacyInputPortFallback() {
        var source = generated("source", 5, "{\"name\":\"id\",\"value\":\"x\"}");
        var sequence = node("sequence", "id-generator", 2, "{\"targetField\":\"seq\"}");
        var condition = node("condition", "data-condition", 2, "{\"branches\":[{\"id\":\"high\",\"type\":\"IF\",\"fieldName\":\"seq\",\"operator\":\"GT\",\"value\":\"3\"},{\"id\":\"low\",\"type\":\"ELSE\"}]}");
        var target = node("target", "add-constant", 2, "{\"fields\":[{\"name\":\"added\",\"value\":\"yes\"}]}");
        var result = preview(List.of(source, sequence, condition, target), List.of(edge("source", "sequence", null),
                edge("sequence", "condition", null), edge("condition", "target", "high")), "target", 100);
        assertTrue(result.success(), result.issues().toString());
        var input = result.inputSamples().getFirst();
        assertEquals("high", input.sourceOutputPort()); assertEquals("in", input.inputPort());
        assertEquals(2, input.rowCount()); assertEquals(List.of(4L, 5L), input.rows().stream().map(row -> row.get("seq")).toList());
        assertFalse(input.rows().getFirst().containsKey("added")); assertEquals("yes", result.rows().getFirst().get("added"));
    }

    @Test void cancellationAndExecutionFailureDiscardBothSampleSides() {
        var source = generated("source", 3, "{\"name\":\"secret\",\"value\":\"safe-fixture\"}");
        var quality = node("quality", "quality-check", 2, "{}");
        var control = new EtlExecutionControl();
        var policy = new EtlRowPolicyPort() {
            public List<Violation> evaluate(String node, Map<String, Object> row) { control.requestStop(); return List.of(); }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> issues) { }
            public void complete(String node, Counts counts) { }
        };
        var result = new EtlPreviewEngine().preview(List.of(source, quality), List.of(edge("source", "quality", null)),
                "quality", 100, Map.of(), policy, null, control);
        assertFalse(result.success()); assertTrue(result.rows().isEmpty()); assertTrue(result.inputSamples().isEmpty());
        assertFalse(cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(result).contains("safe-fixture"));

        var invalid = node("invalid", "split-to-rows", 2, "{}");
        result = preview(List.of(source, invalid), List.of(edge("source", "invalid", null)), "invalid", 100);
        assertFalse(result.success()); assertTrue(result.rows().isEmpty()); assertTrue(result.inputSamples().isEmpty());
    }

    @Test void sourceTargetsHaveNoIncomingSamplesAndOldConstructorsRemainAvailable() {
        var result = preview(List.of(generated("source", 1, "{\"name\":\"id\",\"value\":\"x\"}")), List.of(), "source", 10);
        assertTrue(result.success()); assertEquals(0, result.targetInputRows()); assertTrue(result.inputSamples().isEmpty());
        var legacy = new EtlPreviewEngine.Result(true, result.columns(), result.rows(), 1, 1, List.of(), false,
                Map.of(), List.of(), result.previewId(), List.of(), result.budget());
        assertEquals(result.rows(), legacy.rows()); assertTrue(legacy.inputSamples().isEmpty());
    }

    private static EtlPreviewEngine.Result preview(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, String target, int limit) {
        return new EtlPreviewEngine().preview(nodes, edges, target, limit, Map.of(), null);
    }
    private static EtlNodeDef generated(String id, int count, String field) {
        return node(id, "generate-records", 1, "{\"count\":" + count + ",\"fields\":[" + field + "]}");
    }
}
