package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlDraftGraphTest {
    private static Map<String, Object> node(String id, String code, int type) {
        return Map.of("id", id, "properties", Map.of("toolCode", code, "toolType", type));
    }

    private static Map<String, Object> edge(String source, String target) {
        return Map.of("sourceNodeId", source, "targetNodeId", target);
    }

    @Test void incompleteConfigurationAndUnboundSubflowCanBeSavedButAreNotRunnable() {
        var nodes = List.of(node("clean", "data-clean", 2), node("sub", "subflow", 2));
        assertDoesNotThrow(() -> EtlPipelineServiceImpl.validateDraftGraph(nodes, List.of()));
        assertThrows(IllegalStateException.class,
                () -> EtlDag.build(EtlGraphConverter.toNodeDefs(nodes), List.of()));
    }

    @Test void emptyCanvasIsAllowedButDanglingEdgesAndDuplicateIdsAreRejected() {
        assertDoesNotThrow(() -> EtlPipelineServiceImpl.validateDraftGraph(List.of(), List.of()));
        assertThrows(ServiceException.class,
                () -> EtlPipelineServiceImpl.validateDraftGraph(List.of(), List.of(edge("a", "b"))));
        assertThrows(ServiceException.class,
                () -> EtlPipelineServiceImpl.validateDraftGraph(List.of(node("a", "data-clean", 2)),
                        List.of(edge("a", "b"))));
        assertThrows(ServiceException.class,
                () -> EtlPipelineServiceImpl.validateDraftGraph(
                        List.of(node("a", "data-clean", 2), node("a", "data-clean", 2)), List.of()));
    }

    @Test void cyclicDraftIsRejectedBeforePersistence() {
        assertThrows(ServiceException.class,
                () -> EtlPipelineServiceImpl.validateDraftGraph(
                        List.of(node("a", "data-clean", 2), node("b", "data-clean", 2)),
                        List.of(edge("a", "b"), edge("b", "a"))));
    }
}
