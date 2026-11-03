package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EtlGovernanceNodeTest {
    @Test
    void qualityRoutesOneThousandRowsAndRecordsEvidenceBeforeDelivery() {
        verifyQualityDisposition("ROUTE");
    }

    @Test
    void keepTaggedRetainsEveryRowWithoutChangingQualityCounts() {
        verifyQualityDisposition("KEEP_TAGGED");
    }

    private void verifyQualityDisposition(String disposition) {
        List<Map<String, Object>> input = IntStream.range(0, 1000)
                .mapToObj(i -> Map.<String, Object>of("id", i)).toList();
        TestPort port = new TestPort();
        port.evaluator = row -> {
            int id = (Integer) row.get("id");
            if (id < 930) return List.of();
            var warning = new EtlRowPolicyPort.Violation("format", "id", "WARN", "FORMAT", "format");
            return id < 950 ? List.of(warning) : List.of(warning,
                    new EtlRowPolicyPort.Violation("range", "id", "ERROR", "RANGE", "range"));
        };
        List<String> ports = "ROUTE".equals(disposition) ? List.of("pass", "warn", "reject") : List.of("main");
        Run run = run("quality-check", "{\"disposition\":\"" + disposition + "\"}", input, port, ports,
                row -> {
                    if (!"PASS".equals(row.get("_quality_status"))) {
                        assertTrue(port.evidence.containsKey((Integer) row.get("id")), "Evidence must precede row delivery");
                    }
                });
        assertTrue(run.result.isSuccess(), run.result.getFirstError());
        assertEquals(new EtlRowPolicyPort.Counts(1000, 930, 20, 50, 120), port.counts);
        assertEquals(70, port.evidence.size());
        assertEquals(1000, run.outputs.values().stream().mapToInt(List::size).sum());
        if ("ROUTE".equals(disposition)) {
            assertEquals(930, run.outputs.get("pass").size());
            assertEquals(20, run.outputs.get("warn").size());
            assertEquals(50, run.outputs.get("reject").size());
            for (String outputPort : ports) assertTrue(run.outputs.get(outputPort).stream()
                    .allMatch(row -> outputPort.toUpperCase().equals(row.get("_quality_status"))));
        } else {
            assertEquals(930, run.outputs.get("main").stream().filter(row -> "PASS".equals(row.get("_quality_status"))).count());
        }
        assertTrue(input.stream().allMatch(row -> row.size() == 1));
        assertTrue(port.evidence.values().stream().allMatch(row -> row.size() == 1));
        assertEquals(931L, port.sequences.get(930));
        assertEquals(1000L, port.sequences.get(999));
    }

    @Test
    void failedEvidenceWriteStopsBeforeRejectedRowLeavesNode() {
        AtomicBoolean completed = new AtomicBoolean();
        TestPort port = new TestPort() {
            @Override public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> violations) {
                throw new IllegalStateException("evidence store unavailable");
            }
            @Override public void complete(String node, Counts counts) { completed.set(true); }
        };
        port.evaluator = row -> List.of(new EtlRowPolicyPort.Violation("r", "id", "ERROR", "INVALID", "invalid"));
        Run run = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> run("quality-check", "{}",
                List.of(Map.of("id", 1)), port, List.of("pass", "warn", "reject"), row -> { }));
        assertFalse(run.result.isSuccess());
        assertFalse(run.result.isTerminationUnconfirmed());
        assertTrue(run.result.getFirstError().contains("evidence store unavailable"), run.result.getFirstError());
        assertTrue(run.outputs.values().stream().allMatch(List::isEmpty));
        assertFalse(completed.get());
    }

    @Test
    void qualityCannotOverwriteExistingStatus() {
        Run run = run("quality-check", "{}", List.of(Map.of("_quality_status", "original")),
                new TestPort(), List.of("main"), row -> { });
        assertFalse(run.result.isSuccess());
        assertTrue(run.result.getFirstError().contains("状态字段已存在"), run.result.getFirstError());
        assertTrue(run.outputs.get("main").isEmpty());
    }

    @Test
    void retiredStandardMappingNodeFailsBeforeAnyOutput() {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        assertFalse(registry.supports("standard-map"));
        var definition = node("retired", "standard-map", 2, "{}");
        var validation = cn.iocoder.yudao.framework.etl.core.validation.EtlGraphValidator
                .validate(List.of(definition), List.of(), registry);
        assertTrue(validation.issues().stream().anyMatch(issue -> "UNSUPPORTED_TOOL".equals(issue.code())));
        AtomicBoolean delivered = new AtomicBoolean();
        var error = assertThrows(IllegalStateException.class, () -> run("standard-map", "{}",
                List.of(Map.of("raw", "source-code")), new TestPort(), List.of("main"),
                row -> delivered.set(true)));
        assertTrue(error.getMessage().contains("standard-map"));
        assertFalse(delivered.get());
    }

    @Test
    void governanceDataCleanUsesPolicyPortAndPreservesRawInput() {
        Map<String, Object> original = new LinkedHashMap<>(Map.of("name", "  Alice  ", "id", 7));
        TestPort port = new TestPort();
        port.cleaner = row -> {
            Map<String, Object> output = new LinkedHashMap<>(row);
            output.put("name", String.valueOf(output.get("name")).trim());
            return output;
        };
        Run run = run("data-clean", "{\"executionMode\":\"GOVERNANCE\"}", List.of(original), port,
                List.of("main"), row -> { });
        assertTrue(run.result.isSuccess(), run.result.getFirstError());
        assertEquals(Map.of("name", "Alice", "id", 7), run.outputs.get("main").getFirst());
        assertEquals(Map.of("name", "  Alice  ", "id", 7), original);
    }

    private record Run(EtlRunResult result, Map<String, List<Map<String, Object>>> outputs) { }

    private Run run(String code, String config, List<Map<String, Object>> input, TestPort policies,
                    List<String> outputPorts, java.util.function.Consumer<Map<String, Object>> observer) {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        registry.register("test-source", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception { for (var row : input) pushRow(row); }
        });
        Map<String, List<Map<String, Object>>> outputs = new ConcurrentHashMap<>();
        registry.register("test-sink", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception {
                Map<String, Object> row;
                while ((row = readNextRow()) != null) {
                    observer.accept(row);
                    outputs.get(node.getNo()).add(new LinkedHashMap<>(row));
                }
            }
        });
        List<EtlNodeDef> nodes = new ArrayList<>(List.of(node("source", "test-source", 1, "{}"), node("policy", code, 2, config)));
        List<EtlEdgeDef> edges = new ArrayList<>(List.of(edge("source", "policy", "main")));
        for (String port : outputPorts) {
            outputs.put(port, Collections.synchronizedList(new ArrayList<>()));
            nodes.add(node(port, "test-sink", 3, "{}")); edges.add(edge("policy", port, port));
        }
        var result = new EtlPipelineEngine(registry).execute(EtlDag.build(nodes, edges),
                EtlRunOptions.builder().timeoutSeconds(5).rowPolicyPort(policies).build());
        return new Run(result, outputs);
    }

    static EtlNodeDef node(String id, String code, int type, String config) {
        EtlNodeDef node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code);
        node.setToolType(type); node.setNodeJson(config); return node;
    }

    static EtlEdgeDef edge(String source, String target, String port) {
        EtlEdgeDef edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target);
        edge.setOutputPort(port); edge.setEdgeType("DATA"); return edge;
    }

    private static class TestPort implements EtlRowPolicyPort {
        Function<Map<String, Object>, List<Violation>> evaluator = row -> List.of();
        Function<Map<String, Object>, Map<String, Object>> cleaner = LinkedHashMap::new;
        final Map<Integer, Map<String, Object>> evidence = new ConcurrentHashMap<>();
        final Map<Integer, Long> sequences = new ConcurrentHashMap<>();
        volatile Counts counts;
        @Override public Map<String, Object> clean(String nodeNo, Map<String, Object> row) { return cleaner.apply(row); }
        @Override public List<Violation> evaluate(String nodeNo, Map<String, Object> row) { return evaluator.apply(row); }
        @Override public void recordIssue(String nodeNo, long sequence, Map<String, Object> row, List<Violation> violations) {
            Integer id = (Integer) row.get("id"); evidence.put(id, row); sequences.put(id, sequence);
        }
        @Override public void complete(String nodeNo, Counts result) { counts = result; }
    }
}
