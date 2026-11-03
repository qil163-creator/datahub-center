package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class EtlNodeMetricsTest {
    @Test
    void namedPortsCountProducedRowsOnceWhenBroadcastToTwoDownstreams() {
        var registry = new EtlExecutorRegistry();
        registry.register("source", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception { pushRow(Map.of("id", 1), "pass"); pushRow(Map.of("id", 2), "reject"); }
        });
        registry.register("sink", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception { while (readNextRow() != null) { } }
        });
        Map<String, Long> read = new ConcurrentHashMap<>();
        EtlRunListener listener = new EtlRunListener() {
            public void onNodeMetrics(String no, long reads, long writes, Map<String, Long> ports) {
                read.put(no, reads);
                assertThrows(UnsupportedOperationException.class, () -> ports.put("bad", 1L));
            }
        };
        var dag = EtlDag.build(List.of(node("a", "source", 1), node("b", "sink", 3), node("c", "sink", 3)),
                List.of(edge("a", "b", "pass"), edge("a", "c", "pass")));
        try (var engine = new EtlPipelineEngine(registry)) {
            var result = engine.execute(dag, EtlRunOptions.builder().listener(listener).build());
            assertTrue(result.isSuccess(), result.getFirstError());
            assertEquals(Map.of("pass", 1L, "reject", 1L), result.getNodePortCounts().get("a"));
            assertEquals(1L, read.get("b")); assertEquals(1L, read.get("c"));
        }
    }

    @Test
    void failureStillReportsRowsProducedBeforeFailure() {
        var registry = new EtlExecutorRegistry();
        registry.register("source", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception { pushRow(Map.of("id", 1), "warn"); throw new IllegalStateException("failed"); }
        });
        try (var engine = new EtlPipelineEngine(registry)) {
            var result = engine.execute(EtlDag.build(List.of(node("a", "source", 1)), List.of()), EtlRunOptions.builder().build());
            assertFalse(result.isSuccess());
            assertEquals(Map.of("warn", 1L), result.getNodePortCounts().get("a"));
        }
    }

    @Test
    void externallyUnconfirmedResourcesKeepUnknownOutcomeEvenAfterNodeThreadReturns() {
        var registry = new EtlExecutorRegistry();
        registry.register("uncertain", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() { c.markTerminationUnconfirmed(); }
        });
        try (var engine = new EtlPipelineEngine(registry)) {
            var result = engine.execute(EtlDag.build(List.of(node("a", "uncertain", 4)), List.of()), EtlRunOptions.builder().build());
            assertFalse(result.isSuccess()); assertTrue(result.isTerminationUnconfirmed());
        }
        // 不运行真实遗留进程；测试归还引擎保守占住的名额，避免污染后续测试。
        try {
            var slots = EtlPipelineEngine.class.getDeclaredField("RUN_SLOTS"); slots.setAccessible(true);
            ((java.util.concurrent.Semaphore) slots.get(null)).release();
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private EtlNodeDef node(String no, String code, int type) {
        var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode(code); node.setToolType(type); return node;
    }
    private EtlEdgeDef edge(String source, String target, String port) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setEdgeType("DATA"); edge.setOutputPort(port); return edge;
    }
}
