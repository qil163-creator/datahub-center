package cn.iocoder.yudao.framework.etl.core;

import cn.iocoder.yudao.framework.etl.core.connection.EtlStagedOutputPort;
import cn.iocoder.yudao.framework.etl.core.engine.*;
import cn.iocoder.yudao.framework.etl.core.executor.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EtlManagedOutputTest {
    private EtlNodeDef node(String id, String code, int type) { var n = new EtlNodeDef(); n.setNo(id); n.setName(id); n.setToolCode(code); n.setToolType(type); n.setNodeJson("{}"); return n; }
    private EtlRunResult execute(int count, int bytes, boolean fail, EtlStagedOutputPort port) {
        var registry = new EtlExecutorRegistry();
        registry.register("test-source", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            @Override protected void execute() throws Exception {
                for (int i=0;i<count;i++) pushRow(Map.of("id", i, "value", "x".repeat(bytes)));
                if (fail) throw new IllegalStateException("source failed before completion");
            }
        });
        var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("output");
        var dag = EtlDag.build(List.of(node("source", "test-source", 1), node("output", "managed-db-output", 3)), List.of(edge));
        return new EtlPipelineEngine(registry).execute(dag, EtlRunOptions.builder().timeoutSeconds(10)
                .maxRows(20_000).maxBytes(32L*1024*1024).maxRowBytes(1024*1024).stagedOutputPort(port).build());
    }
    @Test void successfulWholeInputStagesOnceAsImmutableRows() {
        AtomicInteger stages = new AtomicInteger();
        assertTrue(execute(3, 20, false, (node, rows) -> { stages.incrementAndGet(); assertEquals(3, rows.size());
            assertThrows(UnsupportedOperationException.class, () -> rows.add(Map.of()));
            assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().put("id", 9));
        }).isSuccess());
        assertEquals(1, stages.get());
    }
    @Test void noPortAndUpstreamFailureDoNotStage() {
        assertFalse(execute(2, 20, false, null).isSuccess());
        AtomicInteger stages = new AtomicInteger();
        assertFalse(execute(2, 20, true, (node, rows) -> stages.incrementAndGet()).isSuccess());
        assertEquals(0, stages.get());
    }
    @Test void rowAndByteBoundsFailBeforeStaging() {
        AtomicInteger stages = new AtomicInteger();
        assertFalse(execute(5001, 1, false, (node, rows) -> stages.incrementAndGet()).isSuccess());
        assertFalse(execute(30, 400_000, false, (node, rows) -> stages.incrementAndGet()).isSuccess());
        assertEquals(0, stages.get());
    }
}
