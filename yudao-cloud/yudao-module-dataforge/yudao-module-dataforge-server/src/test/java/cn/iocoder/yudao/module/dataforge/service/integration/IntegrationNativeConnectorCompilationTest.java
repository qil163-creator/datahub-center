package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationNativeConnectorCompilationTest {
    private final IntegrationFlowCompiler compiler = new IntegrationFlowCompiler();
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final IntegrationRuntimeProperties properties = new IntegrationRuntimeProperties();

    @BeforeEach void setup() {
        properties.setNativeKafkaBindings(Map.of(1L, new EtlNativeConnectorCompiler.KafkaBinding("managed:9092",
                Set.of("source"), Set.of("target"), EtlNativeConnectorCompiler.Authentication.NONE, true)));
        ReflectionTestUtils.setField(compiler, "properties", properties); ReflectionTestUtils.setField(compiler, "access", access);
    }

    @Test void kafkaToKafkaIncludesSessionDdlAndFieldProjectionInTheSubmittedNativeProgram() {
        var task = task(); var step = compiler.compile(task, flow("read-kafka", "source")).getFirst();
        String program = JSONUtil.toJsonStr(step.parameters());
        assertEquals("FLINK", step.taskType()); assertTrue(program.contains("CREATE TEMPORARY TABLE"));
        assertTrue(program.contains("CREATE TEMPORARY VIEW")); assertTrue(program.contains("JSON_VALUE"));
        assertTrue(program.contains("INSERT INTO")); assertTrue(program.contains("properties.batch.size"));
        verify(access, times(2)).requireDatabase(1L);
        assertRenamedOutput(task, JSONUtil.parseObj(flow("read-kafka", "source")), "Integer");
    }

    @Test void unboundConnectionsAndRevokedDatabaseAccessFailBeforeNativeCompilation() {
        properties.setNativeKafkaBindings(Map.of());
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), flow("read-kafka", "source")));
        doThrow(new IllegalStateException("disabled")).when(access).requireDatabase(1L);
        assertThrows(IllegalStateException.class, () -> compiler.compile(task(), flow("read-kafka", "source")));
    }

    @Test void ordinaryTableNodesCannotBorrowGeneratedConnectorAliases() {
        var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), flow("read-db", "dh_native_kafka_reserved")));
        assertTrue(failure.getMessage().contains("内部连接器"));
    }

    @Test void secretReferencesRequireTheExactTaskWorkerGroupAndEngine() {
        properties.setManagedFunctions(Set.of("DH_CRYPTO_ENCRYPT"));
        var binding = new IntegrationRuntimeProperties.ManagedSecretBinding();
        binding.setAllowedTaskIds(Set.of(4L)); binding.setWorkerGroups(Set.of("isolated")); binding.setEngines(Set.of("FLINK"));
        properties.setManagedSecretBindings(Map.of("fixture-key", binding));
        var task = task(); task.setWorkerGroup("isolated");
        var graph = JSONUtil.parseObj(flow("read-kafka", "source"));
        graph.getJSONArray("nodes").add(node("encrypt", "data-encrypt", Map.of("encryptFields", List.of(
                Map.of("fieldName", "label", "algorithm", "AES256", "secretKeyRef", "fixture-key")))));
        graph.set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "encrypt"), Map.of("sourceNodeId", "encrypt", "targetNodeId", "write")));
        String program = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(program.contains("DH_CRYPTO_ENCRYPT")); assertTrue(program.contains("fixture-key"));
        for (String mismatch : List.of("task", "worker", "engine")) {
            binding.setAllowedTaskIds(Set.of("task".equals(mismatch) ? 99L : 4L));
            binding.setWorkerGroups(Set.of("worker".equals(mismatch) ? "other" : "isolated"));
            binding.setEngines(Set.of("engine".equals(mismatch) ? "SPARK" : "FLINK"));
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
            assertTrue(failure.getMessage().contains("授权"));
        }
    }

    @Test void uploadedFileInputsNoLongerCompileWorkerFileResources() {
        var task = task(); task.setEngine("SPARK"); task.setMode("OFFLINE");
        for (String code : List.of("read-csv", "read-excel", "read-json")) {
            var graph = new JSONObject().set("nodes", List.of(node("read", code, Map.of(
                    "resourceBindingId", "files", "relativePath", "input.csv",
                    "columns", List.of(Map.of("name", "id", "type", "Long"))))))
                    .set("edges", List.of());
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
            assertTrue(failure.getMessage().contains("暂不支持上传附件执行"));
            var nodes = cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter.toNodeDefs(
                    cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService.maps(graph.get("nodes")));
            var analysis = assertThrows(IllegalArgumentException.class, () -> compiler.analyzeNative(task, nodes, List.of()));
            assertTrue(analysis.getMessage().contains("暂不支持上传附件执行"));
        }
    }

    @Test void mongodbInputOutputUsesTheTaskAuthorizedSparkProviderAndPreservesQueryAndBatches() {
        var resources = new IntegrationDataxBindingsProperties();
        var binding = new IntegrationDataxBindingsProperties.ManagedBinding(); binding.setDatabaseId(1L); binding.setAllowedTaskIds(Set.of(4L));
        binding.setConnectorCodes(Set.of("read-mongodb", "write-mongodb")); binding.setInstalledPlugins(Set.of("spark-mongodb"));
        binding.setAddresses(List.of("mongo-native:27017")); binding.setDatabase("datahub_components");
        binding.setAuthentication(cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler.Authentication.ANONYMOUS);
        resources.setBindings(Map.of("mongo", binding)); ReflectionTestUtils.setField(compiler, "dataxBindings", resources);
        var columns = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"));
        var graph = new JSONObject().set("nodes", List.of(node("read", "read-mongodb", Map.of("databaseId", "1", "tableName", "source",
                        "whereCondition", "{\"label\":\"O'Reilly\"}", "columns", columns)),
                        node("write", "write-mongodb", Map.of("databaseId", 1L, "tableName", "target", "batchSize", 37, "columns", columns))))
                .set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
        var task = task(); task.setEngine("SPARK"); task.setMode("OFFLINE");
        var program = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(program.contains("USING mongodb OPTIONS")); assertTrue(program.contains("aggregation.pipeline"));
        assertTrue(program.contains("spark.mongodb.write.maxBatchSize` '37'"));
        assertRenamedOutput(task, graph, "Long");
        assertTrue(program.contains("spark.sql.parser.escapedStringLiterals=false"));
        binding.setAllowedTaskIds(Set.of(99L));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
    }

    private void assertRenamedOutput(IntegrationTaskDO task, JSONObject graph, String integerType) {
        var target = graph.getJSONArray("nodes").getJSONObject(1).getJSONObject("properties").getJSONObject("nodeJson");
        target.set("columns", List.of(Map.of("name", "id", "key", "id", "type", integerType), Map.of("name", "标签", "key", "label", "type", "String")));
        target.set("fieldMappings", List.of(Map.of("source", "id", "target", "id", "enabled", true), Map.of("source", "label", "target", "标签", "enabled", true)));
        String program = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(program.contains("(`id`, `标签`) SELECT `id`, `label`"));
    }

    private static IntegrationTaskDO task() {
        var task = new IntegrationTaskDO(); task.setId(4L); task.setEngine("FLINK"); task.setMode("REALTIME");
        task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig())); return task;
    }
    private static String flow(String sourceCode, String sourceTopic) {
        var fields = List.of(Map.of("name", "id", "type", "Integer"), Map.of("name", "label", "type", "String"));
        var source = new JSONObject().set("databaseId", 1).set("columns", fields).set("read-db".equals(sourceCode) ? "tableName" : "topic", sourceTopic);
        return new JSONObject().set("nodes", List.of(node("read", sourceCode, source), node("write", "write-kafka",
                        Map.of("databaseId", 1, "topic", "target", "columns", fields, "batchSize", 10000))))
                .set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write"))).toString();
    }
    private static Map<String, Object> node(String id, String code, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", code.startsWith("read") ? 1 : 3, "nodeJson", config));
    }
}
