package cn.iocoder.yudao.framework.etl.core;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander;
import cn.iocoder.yudao.framework.etl.core.engine.*;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlSubflowExpanderTest {
    @Test void typedExplicitBindingsRejectInvalidValuesAndNeverApplyChildDefaults() {
        var old = child("7:9");
        var typed = new EtlSubflowExpander.Definition(old.key(),old.contentHash(),old.nodes(),old.edges(),old.parameters(),
                List.of(new cn.iocoder.yudao.framework.etl.core.variable.EtlParameterDefinition("label","INTEGER",true,"12","0","999999999999999999999999999999",null,null)));
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(parent(call("call","9","{}")),parentEdges(),(p,v)->typed,false));
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(parent(call("call","9","{\"label\":\"not-an-integer\"}")),parentEdges(),(p,v)->typed,false));
        var result = EtlSubflowExpander.expand(parent(call("call","9","{\"label\":\"9007199254740993\"}")),parentEdges(),(p,v)->typed,false);
        var expanded = result.nodes().stream().filter(n -> "add-constant".equals(n.getToolCode())).findFirst().orElseThrow();
        assertEquals("9007199254740993",JSONUtil.parseObj(expanded.getNodeJson()).getJSONArray("fields").getJSONObject(0).getStr("value"));
    }

    @Test void designMayKeepUnresolvedTypedBindingsButExecutionMustProveThem() {
        var old = child("7:9");
        var typed = new EtlSubflowExpander.Definition(old.key(),old.contentHash(),old.nodes(),old.edges(),old.parameters(),
                List.of(new cn.iocoder.yudao.framework.etl.core.variable.EtlParameterDefinition("label","INTEGER",true,null,null,null,null,null)));
        var nodes = parent(call("call","9","{\"label\":\"${params.parent}\"}"));
        assertNotNull(EtlSubflowExpander.expand(nodes,parentEdges(),(p,v)->typed));
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(nodes,parentEdges(),(p,v)->typed,false));
    }
    @Test void childBindingsConsumeTrimmedReferencesBeforeParentRuntimeCanReinterpretThem() {
        var definition = child("7:9");
        definition.nodes().get(1).setNodeJson("{\"fields\":[{\"name\":\"label\",\"value\":\"${ params.label }\"}]}");
        var result = EtlSubflowExpander.expand(parent(call("call","9","{\"label\":\"child-value\"}")),
                parentEdges(),(p,v)->definition,false);
        List<Map<String,Object>> rows = Collections.synchronizedList(new ArrayList<>());
        var run = new EtlPipelineEngine(new EtlExecutorRegistry()).execute(EtlDag.build(result.nodes(),result.edges()),
                EtlRunOptions.builder().params(Map.of("label","parent-value")).timeoutSeconds(5)
                        .rowObserver((id,port,row)-> { if (id.equals("sink")) rows.add(row); }).build());
        assertTrue(run.isSuccess(),run.getFirstError());
        assertEquals(3,rows.size()); assertEquals("child-value",rows.getFirst().get("label"));
        definition.nodes().get(1).setNodeJson("{\"fields\":[{\"name\":\"label\",\"value\":\"${ params.undeclared }\"}]}");
        assertThrows(IllegalArgumentException.class,()->EtlSubflowExpander.expand(parent(call("call","9","{\"label\":\"x\"}")),
                parentEdges(),(p,v)->definition,false));
    }
    private EtlNodeDef node(String id, String code, int type, String json) { var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type); node.setNodeJson(json); return node; }
    private EtlEdgeDef edge(String source, String target, String output, String input) { var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setOutputPort(output); edge.setInputPort(input); edge.setEdgeType("DATA"); return edge; }
    private EtlNodeDef call(String id, String version, String params) { return node(id, "sub-pipeline", 2, "{\"pipelineId\":\"7\",\"versionId\":\"" + version + "\",\"parameters\":" + params + "}"); }
    private EtlSubflowExpander.Definition child(String key) {
        return new EtlSubflowExpander.Definition(key, "hash-" + key, List.of(
                node("in", "boundary-input", 1, "{\"portName\":\"input\",\"fields\":[{\"name\":\"id\",\"type\":\"Object\",\"nullable\":false}]}"),
                node("transform", "add-constant", 2, "{\"fields\":[{\"name\":\"label\",\"value\":\"${params.label}\"}]}"),
                node("out", "boundary-output", 3, "{\"portName\":\"result\",\"fields\":[{\"name\":\"label\",\"type\":\"String\"}]}")),
                List.of(edge("in", "transform", "main", "in"), edge("transform", "out", "main", "in")), Set.of("label"));
    }
    private List<EtlNodeDef> parent(EtlNodeDef call) { return List.of(node("source", "generate-records", 1, "{\"count\":3,\"fields\":[{\"name\":\"id\",\"value\":\"1\"}]}"), call, node("sink", "sort-records", 2, "{\"sorts\":[{\"field\":\"id\",\"direction\":\"asc\"}]}")); }
    private List<EtlEdgeDef> parentEdges() { return List.of(edge("source", "call", "main", "input"), edge("call", "sink", "result", "in")); }

    @Test void expandsImmutableDefinitionPreservingOriginalDesignAndExecutesRows() {
        var source = parent(call("call", "9", "{\"label\":\"a\\\"b\"}")); var child = child("7:9");
        var result = EtlSubflowExpander.expand(source, parentEdges(), (p, v) -> child);
        assertEquals(5, result.nodes().size()); assertEquals("sub-pipeline", source.get(1).getToolCode());
        assertEquals(3, result.sourceMap().values().stream().filter(o -> "call".equals(o.callPath())).count());
        assertTrue(result.nodes().stream().allMatch(n -> n.getNo().length() <= 128));
        var resultAgain = EtlSubflowExpander.expand(source, parentEdges(), (p, v) -> child);
        assertEquals(JSONUtil.toJsonStr(result.nodes()), JSONUtil.toJsonStr(resultAgain.nodes()));
        List<Map<String,Object>> rows = Collections.synchronizedList(new ArrayList<>());
        var run = new EtlPipelineEngine(new EtlExecutorRegistry()).execute(EtlDag.build(result.nodes(), result.edges()),
                EtlRunOptions.builder().timeoutSeconds(5).rowObserver((id, port, row) -> { if (id.equals("sink")) rows.add(row); }).build());
        assertTrue(run.isSuccess(), run.getFirstError()); assertEquals(3, rows.size()); assertEquals("a\"b", rows.getFirst().get("label"));
    }
    @Test void rejectsMissingBindingsPortsAndSideEffectsBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(parent(call("call", "9", "{}")), parentEdges(), (p,v) -> child("7:9")));
        var wrongEdges = List.of(edge("source", "call", "main", "missing"), edge("call", "sink", "result", "in"));
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(parent(call("call", "9", "{\"label\":\"x\"}")), wrongEdges, (p,v) -> child("7:9")));
        var unsafe = child("7:9"); unsafe.nodes().get(1).setToolCode("sql-script");
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.validateDefinition(unsafe.nodes(), unsafe.edges()));
    }
    @Test void rejectsRecursiveVersionsAndExpandedSizeLimit() {
        var recursive = child("7:9"); var nodes = new ArrayList<>(recursive.nodes()); nodes.set(1, call("transform", "9", "{\"label\":\"x\"}"));
        var definition = new EtlSubflowExpander.Definition("7:9", "hash", nodes, List.of(edge("in", "transform", "main", "input"), edge("transform", "out", "result", "in")), Set.of("label"));
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(parent(call("call", "9", "{\"label\":\"x\"}")), parentEdges(), (p,v) -> definition));
        var big = new ArrayList<>(parent(call("call", "9", "{\"label\":\"x\"}")));
        for (int i=0;i<28;i++) big.add(node("extra"+i, "generate-records", 1, "{}"));
        assertThrows(IllegalArgumentException.class, () -> EtlSubflowExpander.expand(big, parentEdges(), (p,v) -> child("7:9")));
    }
    @Test void missingOrWrongBoundaryValuesFailClosed() {
        var definition = child("7:9"); definition.nodes().getFirst().setNodeJson("{\"portName\":\"input\",\"fields\":[{\"name\":\"missing\",\"nullable\":false}]}");
        var result = EtlSubflowExpander.expand(parent(call("call", "9", "{\"label\":\"x\"}")), parentEdges(), (p,v) -> definition);
        List<Map<String,Object>> rows = Collections.synchronizedList(new ArrayList<>());
        var run = new EtlPipelineEngine(new EtlExecutorRegistry()).execute(EtlDag.build(result.nodes(), result.edges()),
                EtlRunOptions.builder().timeoutSeconds(5).rowObserver((id, port, row) -> { if (id.equals("sink")) rows.add(row); }).build());
        assertFalse(run.isSuccess()); assertTrue(rows.isEmpty());
    }
}
