package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy;
import cn.iocoder.yudao.framework.etl.core.script.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EtlWorkerAdmissionTest {
    @Test void missingWorkerIsRejectedBeforeAnyOtherNodeCanProduceEffects() {
        var effects = new AtomicInteger(); var registry = registry(effects);
        try (var engine = new EtlPipelineEngine(registry)) {
            for (String code : List.of("java-script", "js-script")) {
                var dag = EtlDag.build(List.of(node("effects", "effects", 4, "{}"), node("script", code, 4, "{}")), List.of());
                assertThrows(IllegalStateException.class, () -> engine.execute(dag, EtlRunOptions.builder().build()));
            }
            assertEquals(0, effects.get());
        }
    }

    @Test void invalidTrustedClasspathIsRejectedBeforeAnyNodeStarts() {
        var policy = new EtlScriptWorkerPolicy(true, List.of(Path.of(System.getProperty("java.io.tmpdir"))),
                1024 * 1024, 8 * 1024 * 1024, 8 * 1024 * 1024, 10_000, 10_000, 64 * 1024, 30_000, 30_000, 256);
        var effects = new AtomicInteger();
        try (var runtime = DbSwitchRuntime.standalone(); var engine = new EtlPipelineEngine(registry(effects), runtime,
                EtlFileOutputPolicy.disabled(), new EtlScriptRunner(policy))) {
            var dag = EtlDag.build(List.of(node("effects", "effects", 4, "{}"), node("script", "java-script", 4, "{}")), List.of());
            assertThrows(IllegalStateException.class, () -> engine.execute(dag, EtlRunOptions.builder().build()));
            assertEquals(0, effects.get());
        }
    }

    @Test void actualBuiltInNodeBatchesRowsThroughOneWorkerAndObservesItsOutputs() {
        String source = "import java.util.*; import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript; "
                + "public class NodeFixture implements EtlJavaScript { private int count; "
                + "public List<Map<String,Object>> execute(List<Map<String,Object>> rows) { "
                + "rows.get(0).put(\"batch\", ++count); return rows; } }";
        assertBatchedNode("java-script", source);
    }

    @Test void actualJsNodeReceivesAndReturnsDataDespiteBeingInTheScriptToolGroup() {
        assertBatchedNode("js-script", "var count=0; function execute(rows) { rows[0].batch = ++count; return rows; }");
    }

    private void assertBatchedNode(String tool, String source) {
        var registry = new EtlExecutorRegistry();
        registry.register("source-fixture", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            @Override protected void execute() throws Exception { pushRow(Map.of("id", 1)); pushRow(Map.of("id", 2)); }
        });
        List<Map<String, Object>> consumed = Collections.synchronizedList(new ArrayList<>());
        registry.register("sink-fixture", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            @Override protected void execute() throws Exception {
                Map<String, Object> row; while ((row = readNextRow()) != null) consumed.add(row);
            }
        });
        var from = node("source", "source-fixture", 1, "{}");
        var script = node("script", tool, 4, JSONUtil.toJsonStr(Map.of("code", source, "batchSize", 1)));
        var sink = node("sink", "sink-fixture", 3, "{}");
        var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("script"); edge.setEdgeType("DATA");
        var output = new EtlEdgeDef(); output.setSourceNo("script"); output.setTargetNo("sink"); output.setEdgeType("DATA");
        List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
        var runner = new EtlScriptRunner(EtlScriptWorkerPolicy.defaults(true));
        try (var runtime = DbSwitchRuntime.standalone(); var engine = new EtlPipelineEngine(registry, runtime,
                EtlFileOutputPolicy.disabled(), runner)) {
            var result = engine.execute(EtlDag.build(List.of(from, script, sink), List.of(edge, output)), EtlRunOptions.builder()
                    .rowObserver((id, port, row) -> { if ("script".equals(id)) seen.add(row); }).build());
            assertTrue(result.isSuccess(), result.getFirstError()); assertFalse(result.isTerminationUnconfirmed());
            assertEquals(List.of(1, 2), seen.stream().map(row -> row.get("batch")).toList());
            assertEquals(List.of(1, 2), seen.stream().map(row -> row.get("id")).toList());
            assertEquals(seen, consumed, "a real downstream consumer must receive the worker output rows");
            assertEquals(0, runner.activeWorkers());
        }
    }

    private static EtlExecutorRegistry registry(AtomicInteger effects) {
        var registry = new EtlExecutorRegistry();
        registry.register("effects", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            @Override protected void execute() { effects.incrementAndGet(); }
        });
        return registry;
    }
    private static EtlNodeDef node(String id, String code, int type, String json) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type); node.setNodeJson(json);
        return node;
    }
}
