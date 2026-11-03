package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.connection.EtlApiPort;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.schema.*;
import cn.iocoder.yudao.framework.etl.core.validation.EtlGraphValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EtlInlineApiTest {
    private EtlNodeDef source(boolean paged) {
        var node = new EtlNodeDef(); node.setNo("api"); node.setName("接口"); node.setToolCode("rest-api"); node.setToolType(1);
        node.setNodeJson(new JSONObject().set("fetchSize", 2).set("apiConfig", new JSONObject()
                .set("requestUrl", "https://example.invalid/data").set("requestMethod", "GET")
                .set("ifPage", paged ? 1 : 0).set("pageKey", "page").set("pageSizeKey", "size")
                .set("dataColumns", List.of(Map.of("fieldName", "id")))).toString());
        return node;
    }

    @Test void executionPassesOwnConfigurationAndPagesToPort() {
        var registry = new EtlExecutorRegistry(); var accepted = new ArrayList<Map<String, Object>>();
        registry.register("capture", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception {
                Map<String, Object> row;
                while ((row = readNextRow()) != null) accepted.add(row);
            }
        });
        var sink = new EtlNodeDef(); sink.setNo("sink"); sink.setName("sink"); sink.setToolCode("capture"); sink.setToolType(3);
        var edge = new EtlEdgeDef(); edge.setSourceNo("api"); edge.setTargetNo("sink"); edge.setEdgeType("DATA");
        var pages = new ArrayList<Integer>();
        EtlApiPort port = new EtlApiPort() {
            public boolean isPaged(JSONObject config) { return config.getInt("ifPage") == 1; }
            public List<EtlFieldMeta> getColumns(JSONObject config) { return List.of(new EtlFieldMeta("id", "Integer")); }
            public List<Map<String, Object>> fetchRows(JSONObject config, int page, int size) {
                assertEquals("https://example.invalid/data", config.getStr("requestUrl"));
                assertFalse(config.containsKey("apiId")); assertEquals(2, size); pages.add(page);
                return page == 1 ? List.of(Map.of("id", 1), Map.of("id", 2)) : List.of(Map.of("id", 3));
            }
        };
        var source = source(true); var dag = EtlDag.build(List.of(source, sink), List.of(edge));
        var result = new EtlPipelineEngine(registry).execute(dag, EtlRunOptions.builder().apiPort(port).build());
        assertTrue(result.isSuccess(), result.getFirstError()); assertEquals(List.of(1, 2), pages); assertEquals(3, accepted.size());
        var schema = new EtlSchemaService(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()).resolve(dag, null, port);
        assertEquals("id", schema.getNodes().get("api").getOutputFields().getFirst().getName());
    }

    @Test void oldApiIdAndMissingPaginationAreRejectedAtPublication() {
        var node = source(true); node.setNodeJson("{\"apiId\":15}");
        assertFalse(EtlGraphValidator.validate(List.of(node), List.of(), new EtlExecutorRegistry()).valid());
        node = source(true); var config = cn.hutool.json.JSONUtil.parseObj(node.getNodeJson());
        config.getJSONObject("apiConfig").remove("pageKey"); node.setNodeJson(config.toString());
        assertFalse(EtlGraphValidator.validate(List.of(node), List.of(), new EtlExecutorRegistry()).valid());
        assertTrue(EtlGraphValidator.validate(List.of(source(false)), List.of(), new EtlExecutorRegistry()).valid());
    }
}
