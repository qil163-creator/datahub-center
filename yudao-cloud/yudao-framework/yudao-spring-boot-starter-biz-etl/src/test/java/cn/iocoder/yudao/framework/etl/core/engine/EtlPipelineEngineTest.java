package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EtlPipelineEngineTest {
    private EtlNodeDef node(String id, String code, int type) {
        EtlNodeDef node = new EtlNodeDef();
        node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type);
        return node;
    }

    @Test
    void downstreamFailureStopsProducerBlockedOnFullChannel() {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        registry.register("producer", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception { while (true) pushRow(Map.of("id", 1)); }
        });
        registry.register("sink", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() { throw new IllegalStateException("target rejected"); }
        });
        EtlEdgeDef edge = new EtlEdgeDef(); edge.setSourceNo("a"); edge.setTargetNo("b"); edge.setEdgeType("DATA");
        EtlDag dag = EtlDag.build(List.of(node("a", "producer", 1), node("b", "sink", 3)), List.of(edge));
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var result = new EtlPipelineEngine(registry).execute(dag, EtlRunOptions.builder().build());
            assertFalse(result.isSuccess()); assertFalse(result.isTerminationUnconfirmed());
        });
    }

    @Test
    void deadlineInterruptsBlockedNodeAndReportsFailure() {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        registry.register("wait", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception { Thread.sleep(60_000); }
        });
        var dag = EtlDag.build(List.of(node("a", "wait", 4)), List.of());
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            var result = new EtlPipelineEngine(registry).execute(dag, EtlRunOptions.builder().timeoutSeconds(1).build());
            assertFalse(result.isSuccess()); assertTrue(result.getFirstError().contains("截止"));
        });
    }

    @Test
    void rowAndByteBudgetsFailBeforeUnboundedBuffering() {
        var dag = EtlDag.build(List.of(node("a", "x", 4)), List.of());
        var context = new EtlRunContext(dag, null, new EtlRunListener() { }, new EtlVariableContext(Map.of()));
        context.setLimits(EtlRunOptions.builder().maxRows(1).build());
        context.accountRow(Map.of("x", 1));
        assertThrows(IllegalStateException.class, () -> context.accountRow(Map.of("x", 2)));
        context.setLimits(EtlRunOptions.builder().maxRowBytes(128).build());
        assertThrows(IllegalStateException.class, () -> context.accountRow(Map.of("x", new byte[1024])));
    }

    @Test
    void oversizedGraphIsRejectedBeforeNodeStartup() {
        var dag = EtlDag.build(List.of(node("a", "x", 4), node("b", "x", 4)), List.of());
        assertThrows(IllegalArgumentException.class, () -> new EtlPipelineEngine(new EtlExecutorRegistry())
                .execute(dag, EtlRunOptions.builder().maxNodes(1).build()));
    }

    @Test
    void requestedBuiltInComponentsAreRegistered() {
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        List.of("sort-merge", "multi-join", "cartesian-product", "read-gzip-csv",
                "read-json", "write-json", "write-text", "flatten-row",
                "generate-records", "random-records").forEach(code ->
                assertTrue(registry.supports(code), "missing ETL executor: " + code));
    }
}
