package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeOdpsCompiler;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationOdpsCompilationTest {
    private static LinkedHashMap<String, String> fields() {
        var fields = new LinkedHashMap<String, String>(); fields.put("id", "BIGINT"); fields.put("amount", "DECIMAL(24,4)"); fields.put("label", "STRING"); return fields;
    }
    private static IntegrationTaskDO task() {
        var task = new IntegrationTaskDO(); task.setId(4L); task.setEngine("SPARK"); task.setMode("OFFLINE"); task.setWorkerGroup("isolated"); task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig())); return task;
    }
    @Test void platformProjectEndpointTaskWorkerAndEngineAreBoundWithoutReadingCredentials() {
        var support = new IntegrationOdpsBindingSupport(); var properties = new IntegrationOdpsBindingsProperties();
        var databases = mock(DatabaseService.class); var types = mock(DatabaseTypeService.class); var access = mock(ExecutionAccessService.class);
        ReflectionTestUtils.setField(support, "properties", properties); ReflectionTestUtils.setField(support, "databases", databases);
        ReflectionTestUtils.setField(support, "databaseTypes", types); ReflectionTestUtils.setField(support, "access", access);
        var binding = new IntegrationOdpsBindingsProperties.ManagedBinding(); binding.setAllowedTaskIds(Set.of(4L)); binding.setWorkerGroups(Set.of("isolated")); binding.setEngines(Set.of("SPARK"));
        binding.setProject("fixture"); binding.setServiceEndpoint("https://service.example/api"); binding.setTunnelEndpoint("https://tunnel.example"); binding.setCredentialRef("odpsAccess"); binding.setSparkConnectorInstalled(true);
        properties.setBindings(Map.of(1L, binding)); var database = new DatabaseDO(); database.setDatabaseType(8); database.setDatabaseName("fixture");
        database.setUserName("managed-access-identity");
        database.setExtendConfig("{\"schemaVersion\":1,\"endpoint\":\"https://service.example/api\"}");
        var type = new DatabaseTypeDO(); type.setTypeCode("ODPS"); when(databases.getDatabase(1L)).thenReturn(database); when(types.getDatabaseType(8L)).thenReturn(type);
        assertEquals("odpsAccess", support.require(task(), 1L).credentialRef()); verify(access).requireDatabase(1L);
        database.setDatabaseName("other"); assertThrows(IllegalArgumentException.class, () -> support.require(task(), 1L)); database.setDatabaseName("fixture");
        database.setExtendConfig("{\"schemaVersion\":1,\"endpoint\":\"https://other.example/api\"}"); assertThrows(IllegalArgumentException.class, () -> support.require(task(), 1L));
        database.setExtendConfig("{\"schemaVersion\":1,\"endpoint\":\"https://service.example/api\"}");
        for (String mismatch : List.of("task", "worker", "engine", "mode", "installed")) {
            var task = task(); if (mismatch.equals("task")) task.setId(99L); if (mismatch.equals("worker")) task.setWorkerGroup("other");
            if (mismatch.equals("engine")) task.setEngine("FLINK"); if (mismatch.equals("mode")) task.setMode("REALTIME"); binding.setSparkConnectorInstalled(!mismatch.equals("installed"));
            assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L));
        }
        assertThrows(IllegalArgumentException.class, () -> support.require(task(), null));
    }
    @Test void formalSparkPayloadAndSchemaShareTheResourceContractAndRejectWrongSecretOrPartition() {
        var compiler = new IntegrationFlowCompiler(); var properties = new IntegrationRuntimeProperties(); var support = mock(IntegrationOdpsBindingSupport.class);
        ReflectionTestUtils.setField(compiler, "properties", properties); ReflectionTestUtils.setField(compiler, "odpsBindings", support);
        var resource = new EtlNativeOdpsCompiler.Resource("table_data", "ds='20261102'", fields(), true, true, false);
        var binding = new EtlNativeOdpsCompiler.Binding("https://service.example/api", "https://tunnel.example", "fixture", "odpsAccess", List.of(resource), true, false);
        when(support.require(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(binding);
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L)); secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("SPARK"));
        properties.setManagedSecretBindings(Map.of("odpsAccess", secret)); properties.setManagedFunctions(Set.of("DH_READ_ODPS"));
        properties.setSparkInitializationSql("CREATE TEMPORARY FUNCTION DH_READ_ODPS AS 'cn.iocoder.yudao.framework.etl.spark.EtlOdpsRowsFunction' USING JAR 'file:///managed/odps.jar';");
        var source = new LinkedHashMap<String, Object>(); source.put("databaseId", 1); source.put("tableName", "table_data"); source.put("partition", "ds='20261102'"); source.put("columns", new ArrayList<>(fields().keySet()));
        var target = new LinkedHashMap<>(source); target.put("batchSize", 2);
        var graph = new JSONObject().set("nodes", List.of(node("read", "read-odps", source), node("write", "write-odps", target)))
                .set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("EtlOdpsProvider")); assertTrue(program.contains("CREATE TEMPORARY FUNCTION DH_READ_ODPS"));
        var analysis = compiler.analyzeNative(task(), EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))), EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(fields(), analysis.nodes().get("read").outputFields());
        secret.setWorkerGroups(Set.of("other")); assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
        secret.setWorkerGroups(Set.of("isolated")); source.put("partition", "ds='other'");
        var unauthorized = new JSONObject().set("nodes", List.of(node("read", "read-odps", source), node("write", "write-odps", target))).set("edges", graph.get("edges"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), unauthorized.toString()));
    }
    private static Map<String, Object> node(String id, String code, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", code.startsWith("write") ? 3 : 1, "nodeJson", config));
    }
}
