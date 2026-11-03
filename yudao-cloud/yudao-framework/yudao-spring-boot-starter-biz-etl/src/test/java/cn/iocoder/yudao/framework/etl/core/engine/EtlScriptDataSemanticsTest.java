package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.schema.*;
import cn.iocoder.yudao.framework.etl.core.validation.EtlGraphValidator;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlScriptDataSemanticsTest {
    @Test void scriptToolGroupingDoesNotEraseJavaOrJsDataEdgesOrUpstreamSchema() {
        for (String tool : List.of("java-script", "js-script")) {
            var source = node("source", "boundary-input", 1, "{\"fields\":[{\"name\":\"id\",\"type\":\"Long\"}]}");
            var script = node("script", tool, 4, "{}"); var sink = node("sink", "sort-records", 2, "{}");
            var dag = EtlDag.build(List.of(source, script, sink), List.of(edge("source", "script", "DATA"), edge("script", "sink", "DATA")));
            assertEquals("source", dag.getDataUpstream("script")); assertEquals("script", dag.getDataUpstream("sink"));
            assertEquals(List.of("sink"), dag.getAllDataDownstreams("script")); assertEquals(List.of(), dag.getControlUpstreams("script"));
            try (var runtime = DbSwitchRuntime.standalone()) {
                var schema = new EtlSchemaService(runtime).resolve(dag, null, null);
                assertTrue(schema.getErrors().isEmpty());
                assertEquals(List.of("id"), schema.getNodes().get("script").getInputFields().stream().map(EtlFieldMeta::getName).toList());
                assertEquals(List.of("id"), schema.getNodes().get("sink").getInputFields().stream().map(EtlFieldMeta::getName).toList());
            }
        }
    }

    @Test void multipleDataInputsToOneRowScriptAreRejectedByDagAndGraphValidator() {
        for (String tool : List.of("java-script", "js-script")) {
            var sources = List.of(node("a", "generate-records", 1, "{\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\"}]}"),
                    node("b", "generate-records", 1, "{\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\"}]}"), node("script", tool, 4, "{}"));
            var edges = List.of(edge("a", "script", "DATA"), edge("b", "script", "DATA"));
            assertThrows(IllegalStateException.class, () -> EtlDag.build(sources, edges));
            var validation = EtlGraphValidator.validate(sources, edges, new EtlExecutorRegistry());
            assertFalse(validation.valid());
            assertTrue(validation.issues().stream().anyMatch(issue -> "GRAPH_INVALID".equals(issue.code())),
                    "the publication validator must reject the actual graph shape");
        }
    }

    @Test void explicitControlAndHistoricalZeroInputInitializationRemainCompatibleForRowScripts() {
        for (String tool : List.of("java-script", "js-script")) {
            var nodes = List.of(node("source", "generate-records", 1, "{}"), node("script", tool, 4, "{}"));
            var dag = EtlDag.build(nodes, List.of(edge("source", "script", "CONTROL")));
            assertNull(dag.getDataUpstream("script")); assertEquals(List.of("source"), dag.getControlUpstreams("script"));
            assertEquals(List.of(), dag.getAllDataDownstreams("source"));
            assertDoesNotThrow(() -> EtlDag.build(List.of(node("script", tool, 4, "{}")), List.of()));
        }
    }

    @Test void sqlShellAndPythonScriptsKeepTheirOriginalControlSemantics() {
        for (String tool : List.of("sql-script", "shell-script", "python-script")) {
            var nodes = List.of(node("source", "generate-records", 1, "{}"), node("script", tool, 4, "{}"), node("after", "shell-script", 4, "{}"));
            var dag = EtlDag.build(nodes, List.of(edge("source", "script", "DATA"), edge("script", "after", "DATA")));
            assertNull(dag.getDataUpstream("script")); assertNull(dag.getDataUpstream("after"));
            assertEquals(List.of("source"), dag.getControlUpstreams("script")); assertEquals(List.of("script"), dag.getControlUpstreams("after"));
            assertEquals(List.of(), dag.getAllDataDownstreams("source")); assertEquals(List.of(), dag.getAllDataDownstreams("script"));
        }
    }

    private static EtlNodeDef node(String id, String code, int type, String json) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type); node.setNodeJson(json); return node;
    }
    private static EtlEdgeDef edge(String from, String to, String type) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(from); edge.setTargetNo(to); edge.setEdgeType(type); return edge;
    }
}
