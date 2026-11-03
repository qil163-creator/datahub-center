package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlVersionRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationSubflowCompilationTest {
    private final IntegrationFlowCompiler compiler = new IntegrationFlowCompiler();
    private final EtlSubflowService subflows = new EtlSubflowService();
    private final EtlVersionService versions = mock(EtlVersionService.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private EtlVersionRespVO version;

    @BeforeEach void prepare() {
        var properties = new IntegrationRuntimeProperties();
        var binding = new IntegrationRuntimeProperties.NativeTableBinding();
        binding.setFlinkNamespace(List.of("catalog", "managed")); binding.setSparkNamespace(List.of("managed"));
        binding.setFlinkContinuousSource(true); binding.setFlinkContinuousTables(Set.of("source"));
        properties.setNativeTableBindings(Map.of(1L, binding, 2L, binding));
        var resolver = mock(EtlConnectionResolverAdapter.class); var connection = new EtlConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("public");
        when(resolver.resolve(anyLong())).thenReturn(connection);
        var databases = mock(DatabaseService.class);
        when(databases.getTableMeta(anyLong(), anyString())).thenAnswer(invocation -> {
            var metadata = new DatabaseTableMetaRespVO(); metadata.setTableName(invocation.getArgument(1)); metadata.setSchemaName("public");
            var id = new DatabaseTableColumnRespVO(); id.setFieldName("id"); id.setFieldTypeName("BIGINT");
            var label = new DatabaseTableColumnRespVO(); label.setFieldName("label"); label.setFieldTypeName("STRING");
            metadata.setColumns("source".equals(invocation.getArgument(1)) ? List.of(id) : List.of(id, label)); return metadata;
        });
        ReflectionTestUtils.setField(compiler, "properties", properties); ReflectionTestUtils.setField(compiler, "connections", resolver);
        ReflectionTestUtils.setField(compiler, "databases", databases); ReflectionTestUtils.setField(compiler, "access", access);
        ReflectionTestUtils.setField(compiler, "subflows", subflows);
        ReflectionTestUtils.setField(subflows, "versions", versions); ReflectionTestUtils.setField(subflows, "access", access);
        version = new EtlVersionRespVO(); version.setId(9L); version.setPipelineId(7L); version.setEventType("PUBLISH");
        version.setContentHash("fixed-child-version");
        version.setConfigJson(JSONUtil.toJsonStr(Map.of("nodes", List.of(
                node("in", "boundary-input", 1, Map.of("portName", "input", "fields", List.of(Map.of("name", "id", "type", "Long")))),
                node("constant", "add-constant", 2, Map.of("fields", List.of(Map.of("name", "label", "fieldType", "String", "value", "${params.label}")))),
                node("out", "boundary-output", 3, Map.of("portName", "result", "fields", List.of(Map.of("name", "label", "type", "String"))))),
                "edges", List.of(edge("in", "constant", "main", "in"), edge("constant", "out", "main", "in")),
                "parameters", List.of(Map.of("name", "label", "type", "STRING", "maxLength", 100)))));
        when(versions.get(9L)).thenReturn(version);
    }

    @Test void publishedTypedSubflowCompilesIntoBothNativeEnginesAndRetainsDesignIdentity() {
        for (String engine : List.of("SPARK", "FLINK")) {
            var task = task(engine); String flow = flow(Map.of("label", "literal-child"));
            var step = compiler.compile(task, flow).getFirst();
            assertEquals(engine, step.taskType()); assertTrue(JSONUtil.toJsonStr(step.parameters()).contains("literal-child"));
            assertFalse(JSONUtil.toJsonStr(step.parameters()).contains("${params"));
            var graph = JSONUtil.parseObj(flow);
            var analysis = compiler.analyzeNative(task, EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                    EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
            assertEquals(Map.of("id", "BIGINT"), analysis.nodes().get("call").inputFields());
            assertEquals(Set.of("id", "label"), analysis.nodes().get("call").outputFields().keySet());
            assertTrue(flow.contains("sub-pipeline"));
        }
        verify(access, atLeast(2)).requireUserPermission(8L, "dataforge:etl:query");
    }

    @Test void versionAuthorizationAndExplicitParametersAreCheckedBeforeNativeCompilation() {
        doThrow(new IllegalStateException("revoked")).when(access).requireUserPermission(8L, "dataforge:etl:query");
        assertThrows(IllegalStateException.class, () -> compiler.compile(task("SPARK"), flow(Map.of("label", "x"))));
        verifyNoInteractions(versions);
        reset(access); version.setEventType("SAVE");
        assertThrows(RuntimeException.class, () -> compiler.compile(task("SPARK"), flow(Map.of("label", "x"))));
        version.setEventType("PUBLISH");
        assertThrows(RuntimeException.class, () -> compiler.compile(task("SPARK"), flow(Map.of())));
        var anonymous = task("SPARK"); anonymous.setCreator(null); anonymous.setOwnerId(99L);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(anonymous, flow(Map.of("label", "x"))));
    }

    @Test void expandedSecretsCannotBypassIntegrationCredentialPolicy() {
        var graph = JSONUtil.parseObj(version.getConfigJson());
        graph.getJSONArray("nodes").getJSONObject(1).getJSONObject("properties").getJSONObject("nodeJson").set("password", "forbidden");
        version.setConfigJson(graph.toString());
        var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("SPARK"), flow(Map.of("label", "x"))));
        assertTrue(failure.getMessage().contains("凭据"));
    }

    private static IntegrationTaskDO task(String engine) {
        var task = new IntegrationTaskDO(); task.setId(11L); task.setCreator("8"); task.setEngine(engine);
        task.setMode("FLINK".equals(engine) ? "REALTIME" : "OFFLINE"); task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig())); return task;
    }

    private static String flow(Map<String, String> parameters) {
        return new JSONObject().set("nodes", List.of(node("read", "read-db", 1, Map.of("databaseId", 1, "tableName", "source")),
                        node("call", "sub-pipeline", 2, Map.of("pipelineId", "7", "versionId", "9", "parameters", parameters)),
                        node("write", "insert-db", 3, Map.of("databaseId", 2, "tableName", "target"))))
                .set("edges", List.of(edge("read", "call", "main", "input"), edge("call", "write", "result", "in"))).toString();
    }
    private static Map<String, Object> node(String id, String code, int type, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", type, "nodeJson", config));
    }
    private static Map<String, Object> edge(String source, String target, String output, String input) {
        return Map.of("sourceNodeId", source, "targetNodeId", target, "sourcePort", output, "targetPort", input);
    }
}
