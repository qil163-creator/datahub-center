package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.validation.EtlGraphValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.etl.core.engine.EtlGovernanceNodeTest.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlGraphValidatorTest {
    private static final String SOURCE = "{\"fields\":[{\"name\":\"id\",\"value\":\"1\"}]}";
    private static final String QUALITY = "{\"ruleSetId\":\"123\",\"fieldBindings\":[{\"field\":\"id\"}]}";

    @Test
    void routeRequiresAllThreeDataPortsAndRejectsControlOnlyConnection() {
        List<EtlNodeDef> nodes = List.of(node("source", "generate-records", 1, SOURCE),
                node("quality", "quality-check", 2, QUALITY), node("pass", "value-map", 2, "{}"),
                node("warn", "value-map", 2, "{}"), node("reject", "value-map", 2, "{}"));
        List<EtlEdgeDef> edges = new ArrayList<>(List.of(edge("source", "quality", null),
                edge("quality", "pass", "pass"), edge("quality", "warn", "warn"), edge("quality", "reject", "reject")));
        assertTrue(EtlGraphValidator.validate(nodes, edges, new EtlExecutorRegistry()).valid());
        edges.getLast().setEdgeType("CONTROL");
        var result = EtlGraphValidator.validate(nodes, edges, new EtlExecutorRegistry());
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> "OUTPUT_REQUIRED".equals(issue.code()) && "ports.reject".equals(issue.field())));
        edges.getLast().setEdgeType("DATA"); edges.getLast().setOutputPort("main");
        result = EtlGraphValidator.validate(nodes, edges, new EtlExecutorRegistry());
        assertTrue(result.issues().stream().anyMatch(issue -> "INVALID_PORT".equals(issue.code())));
    }

    @Test
    void taggedStrategyUsesMainAndLegacyEdgesDefaultToMain() {
        List<EtlNodeDef> nodes = List.of(node("source", "generate-records", 1, SOURCE),
                node("quality", "quality-check", 2, "{\"disposition\":\"KEEP_TAGGED\"," + QUALITY.substring(1)),
                node("sink", "value-map", 2, "{}"));
        List<EtlEdgeDef> edges = List.of(edge("source", "quality", null), edge("quality", "sink", null));
        assertTrue(EtlGraphValidator.validate(nodes, edges, new EtlExecutorRegistry()).valid());
        assertEquals(List.of("quality"), EtlDag.build(nodes, edges).getDataDownstreams("source", "main"));
    }

    @Test
    void explicitInputSlotsDetermineJoinOrderWhileLegacyOrderIsPreserved() {
        var nodes = List.of(node("left", "generate-records", 1, SOURCE), node("right", "generate-records", 1, SOURCE),
                node("merge", "data-merge", 2, "{}"));
        var right = edge("right", "merge", null); var left = edge("left", "merge", null);
        var edges = List.of(right, left);
        assertEquals(List.of("right", "left"), EtlDag.build(nodes, edges).getDataUpstreamList("merge"));
        left.setInputPort("input-1"); right.setInputPort("input-2");
        assertEquals(List.of("left", "right"), EtlDag.build(nodes, edges).getDataUpstreamList("merge"));
        right.setInputPort("input-1"); assertThrows(IllegalStateException.class, () -> EtlDag.build(nodes, edges));
        right.setInputPort(null); assertThrows(IllegalStateException.class, () -> EtlDag.build(nodes, edges));
        right.setInputPort("input-x"); assertThrows(IllegalStateException.class, () -> EtlDag.build(nodes, edges));
        left.setInputPort("in"); right.setInputPort("main");
        assertEquals(List.of("right", "left"), EtlDag.build(nodes, edges).getDataUpstreamList("merge"));
    }

    @Test
    void duplicateEdgesAndFieldBindingsMissingAreRejected() {
        var source = node("source", "generate-records", 1, SOURCE);
        var target = node("quality", "quality-check", 2, "{\"ruleSetId\":123}");
        var result = EtlGraphValidator.validate(List.of(source, target),
                List.of(edge("source", "quality", null), edge("source", "quality", null)), new EtlExecutorRegistry());
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> "DUPLICATE_EDGE".equals(issue.code())));
        assertTrue(result.issues().stream().anyMatch(issue -> "fieldBindings".equals(issue.field()) && "REQUIRED".equals(issue.code())));
    }
}
