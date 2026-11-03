package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EtlGovernanceRowSetCleanTest {

    @Test
    void seesCompleteInputOnceAndDeduplicatesAcrossChannelWindows() {
        List<Map<String, Object>> input = IntStream.range(0, 1500)
                .mapToObj(i -> Map.<String, Object>of("key", i % 500, "revision", i)).toList();
        AtomicInteger calls = new AtomicInteger();
        Run run = run(input, rows -> {
            calls.incrementAndGet();
            assertEquals(1500, rows.size());
            assertThrows(UnsupportedOperationException.class, () -> rows.clear());
            assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().put("key", -1));
            Map<Object, Map<String, Object>> latest = new LinkedHashMap<>();
            rows.forEach(row -> latest.put(row.get("key"), row));
            return new ArrayList<>(latest.values());
        }, 10_000_000);
        assertTrue(run.result.isSuccess(), run.result.getFirstError());
        assertEquals(1, calls.get());
        assertEquals(500, run.output.size());
        assertEquals(1000, run.output.getFirst().get("revision"));
        assertEquals(1499, run.output.getLast().get("revision"));
        assertEquals(0, input.getFirst().get("revision"));
    }

    @Test
    void validatesWholeResultBeforeSendingAnyOutput() {
        Run run = run(List.of(Map.of("id", 1), Map.of("id", 2)),
                rows -> List.of(rows.getFirst(), Map.of("other", 2)), 1_000_000);
        assertFalse(run.result.isSuccess());
        assertTrue(run.result.getFirstError().contains("字段集合"));
        assertTrue(run.output.isEmpty());
    }

    @Test
    void rejectsRowAmplificationAndAllowsDeletingEveryRow() {
        Run invalid = run(List.of(Map.of("id", 1)),
                rows -> List.of(rows.getFirst(), rows.getFirst()), 1_000_000);
        assertFalse(invalid.result.isSuccess());
        assertTrue(invalid.output.isEmpty());
        Run deleted = run(List.of(Map.of("id", 1)), rows -> List.of(), 1_000_000);
        assertTrue(deleted.result.isSuccess(), deleted.result.getFirstError());
        assertTrue(deleted.output.isEmpty());
    }

    @Test
    void enforcesCombinedInputAndOutputBufferBudget() {
        List<Map<String, Object>> input = IntStream.range(0, 8)
                .mapToObj(i -> Map.<String, Object>of("id", i, "value", "x".repeat(256))).toList();
        Run run = run(input, rows -> rows, 9_000);
        assertFalse(run.result.isSuccess());
        assertTrue(run.result.getFirstError().contains("缓存超过字节预算"), run.result.getFirstError());
        assertTrue(run.output.isEmpty());
    }

    @Test
    void rejectsMixedInputSchemasBeforeApplyingRules() {
        AtomicInteger calls = new AtomicInteger();
        Run run = run(List.of(Map.of("id", 1), Map.of("other", 2)), rows -> {
            calls.incrementAndGet();
            return rows;
        }, 1_000_000);
        assertFalse(run.result.isSuccess());
        assertEquals(0, calls.get());
        assertTrue(run.output.isEmpty());
    }

    @Test
    void intermediateBudgetStopsGenerationBeforeTheRuleReturns() {
        List<Map<String, Object>> input = IntStream.range(0, 8)
                .mapToObj(i -> Map.<String, Object>of("id", i, "value", "x".repeat(256))).toList();
        AtomicInteger generated = new AtomicInteger();
        Run run = run(input, (rows, control) -> {
            control.beforeRule(rows);
            List<Map<String, Object>> output = new ArrayList<>();
            for (var row : rows) {
                control.checkpoint();
                control.retainOutput(row);
                generated.incrementAndGet();
                output.add(row);
            }
            return output;
        }, 15_000);
        assertFalse(run.result.isSuccess());
        assertTrue(generated.get() < input.size());
        assertTrue(run.output.isEmpty());
    }

    private record Run(EtlRunResult result, List<Map<String, Object>> output) { }

    private Run run(List<Map<String, Object>> input,
                    Function<List<Map<String, Object>>, List<Map<String, Object>>> cleaner, long maxBytes) {
        return run(input, (rows, control) -> {
            control.beforeRule(rows);
            List<Map<String, Object>> result = cleaner.apply(rows);
            if (result != null) result.forEach(control::retainOutput);
            return result;
        }, maxBytes);
    }

    private Run run(List<Map<String, Object>> input,
                    java.util.function.BiFunction<List<Map<String, Object>>, EtlRowPolicyPort.CleanRowSetControl,
                            List<Map<String, Object>>> cleaner, long maxBytes) {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        registry.register("test-source", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception {
                for (var row : input) pushRow(row);
            }
        });
        EtlRowPolicyPort port = new EtlRowPolicyPort() {
            @Override public boolean requiresCleanRowSet(String nodeNo) { return true; }
            @Override public List<Map<String, Object>> cleanRows(String nodeNo, List<Map<String, Object>> rows,
                                                                 CleanRowSetControl control) {
                return cleaner.apply(rows, control);
            }
            @Override public Map<String, Object> clean(String nodeNo, Map<String, Object> row) {
                fail("A set plan must never be executed row by row");
                return null;
            }
            @Override public List<Violation> evaluate(String nodeNo, Map<String, Object> row) { return List.of(); }
            @Override public void recordIssue(String nodeNo, long sequence, Map<String, Object> row, List<Violation> violations) { }
            @Override public void complete(String nodeNo, Counts counts) { }
        };
        var source = EtlGovernanceNodeTest.node("source", "test-source", 1, "{}");
        var clean = EtlGovernanceNodeTest.node("clean", "data-clean", 2, "{\"executionMode\":\"GOVERNANCE\"}");
        List<Map<String, Object>> output = new ArrayList<>();
        try (var engine = new EtlPipelineEngine(registry)) {
            EtlRunResult result = engine.execute(EtlDag.build(List.of(source, clean),
                            List.of(EtlGovernanceNodeTest.edge("source", "clean", "main"))),
                    EtlRunOptions.builder().rowPolicyPort(port).timeoutSeconds(10).maxRows(10_000).maxBytes(maxBytes)
                            .rowObserver((nodeNo, outputPort, row) -> {
                                if ("clean".equals(nodeNo)) output.add(new LinkedHashMap<>(row));
                            }).build());
            assertFalse(result.isTerminationUnconfirmed());
            return new Run(result, output);
        }
    }
}
