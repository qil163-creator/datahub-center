package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class EtlJoinBudgetTest {
    @Test
    void repeatedKeysStopExpansionAtFlowBudgetInsteadOfMaterializingAllMatches() {
        AtomicInteger copies = new AtomicInteger();
        Map<String, Object> right = new LinkedHashMap<>(Map.of("id", 1, "right", "value")) {
            @Override public void forEach(BiConsumer<? super String, ? super Object> consumer) {
                copies.incrementAndGet();
                super.forEach(consumer);
            }
        };
        var result = run("data-merge", "{\"mergeStrategy\":\"JOIN\",\"joinConditions\":[{\"leftField\":\"id\",\"rightField\":\"id\"}]}",
                List.of(repeat(Map.of("id", 1), 30), repeat(right, 30)), 100, 1_000_000);
        assertFalse(result.isSuccess());
        assertTrue(copies.get() <= 41, "must stop before expanding all 900 matches: " + copies);
        assertFalse(result.isTerminationUnconfirmed());
    }

    @Test
    void deduplicationDoesNotHideUnboundedJoinExpansion() {
        var result = run("data-merge", "{\"mergeStrategy\":\"JOIN\",\"removeDuplicates\":true,\"joinConditions\":[{\"leftField\":\"id\",\"rightField\":\"id\"}]}",
                List.of(repeat(Map.of("id", 1), 30), repeat(Map.of("id", 1), 30)), 100, 1_000_000);
        assertFalse(result.isSuccess());
        assertTrue(result.getFirstError().contains("预算"));
    }

    @Test
    void multiJoinRejectsOversizedIntermediateEvenIfLaterJoinWouldDropEveryRow() {
        var result = run("multi-join", "{\"keyField\":\"id\"}",
                List.of(repeat(Map.of("id", 1), 8), repeat(Map.of("id", 1), 8), List.of(Map.of("id", 2))),
                30, 1_000_000);
        assertFalse(result.isSuccess());
        assertTrue(result.getFirstError().contains("预算"));
    }

    @Test
    void multiJoinChecksIntermediateBytesBeforeFinalOutput() {
        var result = run("multi-join", "{\"keyField\":\"id\"}",
                List.of(repeat(Map.of("id", 1, "a", "x".repeat(256)), 8),
                        repeat(Map.of("id", 1, "b", "y".repeat(256)), 8), List.of(Map.of("id", 2))),
                500, 20_000);
        assertFalse(result.isSuccess());
        assertTrue(result.getFirstError().contains("预算"));
    }

    @Test
    void cartesianProductRejectsOversizedIntermediateBeforeEmptyFinalInput() {
        var result = run("cartesian-product", "{}",
                List.of(repeat(Map.of("id", 1), 6), repeat(Map.of("id", 1), 6), List.of()), 20, 1_000_000);
        assertFalse(result.isSuccess());
        assertTrue(result.getFirstError().contains("预算"));
    }

    @Test
    void leftJoinAndDedupKeepExistingOutputSemantics() {
        List<Map<String, Object>> output = new ArrayList<>();
        var result = run("data-merge", "{\"mergeStrategy\":\"JOIN\",\"joinType\":\"LEFT\",\"removeDuplicates\":true,\"joinConditions\":[{\"leftField\":\"id\",\"rightField\":\"rid\"}]}",
                List.of(List.of(Map.of("id", 1), Map.of("id", 2)), repeat(Map.of("rid", 1, "v", "r"), 2)),
                100, 1_000_000, output);
        assertTrue(result.isSuccess(), result.getFirstError());
        assertEquals(2, output.size());
        assertEquals("r", output.get(0).get("v"));
        assertNull(output.get(1).get("v"));
    }

    private List<Map<String, Object>> repeat(Map<String, Object> row, int count) {
        return java.util.Collections.nCopies(count, row);
    }

    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult run(String code, String config,
            List<List<Map<String, Object>>> inputs, long rows, long bytes) {
        return run(code, config, inputs, rows, bytes, new ArrayList<>());
    }

    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult run(String code, String config,
            List<List<Map<String, Object>>> inputs, long rows, long bytes, List<Map<String, Object>> output) {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        List<EtlNodeDef> nodes = new ArrayList<>();
        List<EtlEdgeDef> edges = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            String id = "source" + i;
            List<Map<String, Object>> input = inputs.get(i);
            registry.register(id, (n, c) -> new AbstractEtlNodeExecutor(n, c) {
                protected void execute() throws Exception { for (var row : input) pushRow(row); }
            });
            EtlNodeDef source = node(id, id, "{}");
            source.setToolType(1);
            nodes.add(source);
            EtlEdgeDef edge = new EtlEdgeDef();
            edge.setSourceNo(id); edge.setTargetNo("join"); edge.setEdgeType("DATA"); edges.add(edge);
        }
        nodes.add(node("join", code, config));
        try (var engine = new EtlPipelineEngine(registry)) {
            return engine.execute(EtlDag.build(nodes, edges), EtlRunOptions.builder().maxRows(rows).maxBytes(bytes)
                    .rowObserver((no, port, row) -> { if ("join".equals(no)) output.add(row); }).build());
        }
    }

    private EtlNodeDef node(String id, String code, String config) {
        EtlNodeDef node = new EtlNodeDef();
        node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(2); node.setNodeJson(config);
        return node;
    }
}
