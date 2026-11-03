package cn.iocoder.yudao.framework.etl.core.schema;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class EtlSchemaGovernanceCleanTest {

    @Test
    void governanceCleanMarksConfiguredColumnsUnknownAndPreservesUntouchedTypes() {
        EtlNodeDef source = node("source", "generate-records", EtlNodeDef.TOOL_TYPE_INPUT,
                "{\"fields\":[{\"name\":\"amount\",\"type\":\"BigDecimal\"},"
                        + "{\"name\":\"label\",\"type\":\"String\"}]}");
        EtlNodeDef clean = node("clean", "data-clean", 2,
                "{\"executionMode\":\"GOVERNANCE\",\"governanceRules\":["
                        + "{\"ruleId\":\"1\",\"config\":{\"columns\":[\"amount\"]}}]}");
        EtlEdgeDef edge = new EtlEdgeDef();
        edge.setSourceNo("source");
        edge.setTargetNo("clean");

        var result = new EtlSchemaService(mock(DbSwitchRuntime.class))
                .resolve(EtlDag.build(List.of(source, clean), List.of(edge)), ignored -> null, null);

        assertEquals("BigDecimal", result.getNodes().get("source").getOutputFields().get(0).getDataType());
        assertEquals("amount", result.getNodes().get("clean").getOutputFields().get(0).getName());
        assertNull(result.getNodes().get("clean").getOutputFields().get(0).getDataType());
        assertEquals("String", result.getNodes().get("clean").getOutputFields().get(1).getDataType());
    }

    private static EtlNodeDef node(String no, String toolCode, int toolType, String json) {
        EtlNodeDef node = new EtlNodeDef();
        node.setNo(no);
        node.setName(no);
        node.setToolCode(toolCode);
        node.setToolType(toolType);
        node.setNodeJson(json);
        return node;
    }
}
