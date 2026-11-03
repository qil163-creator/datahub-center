package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeTdengineCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRedisCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRabbitCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRocketCompiler;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlConnectionResolverAdapter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationManagedConnectorCompilationTest {
    private final IntegrationFlowCompiler compiler = new IntegrationFlowCompiler();
    private final IntegrationRuntimeProperties properties = new IntegrationRuntimeProperties();
    private final IntegrationDataxBindingsProperties resources = new IntegrationDataxBindingsProperties();
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    private final DatabaseService databases = mock(DatabaseService.class);
    private final IntegrationHiveStorageSupport hive = mock(IntegrationHiveStorageSupport.class);
    private final IntegrationTdengineBindingSupport tdengine = mock(IntegrationTdengineBindingSupport.class);
    private final IntegrationRedisBindingSupport redis = mock(IntegrationRedisBindingSupport.class);
    private final IntegrationRabbitBindingSupport rabbit = mock(IntegrationRabbitBindingSupport.class);
    private final IntegrationRocketBindingSupport rocket = mock(IntegrationRocketBindingSupport.class);
    private final EtlConnectionInfo connection = new EtlConnectionInfo();

    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(compiler, "properties", properties); ReflectionTestUtils.setField(compiler, "dataxBindings", resources);
        ReflectionTestUtils.setField(compiler, "access", access); ReflectionTestUtils.setField(compiler, "connections", connections);
        ReflectionTestUtils.setField(compiler, "databases", databases); ReflectionTestUtils.setField(compiler, "hiveStorage", hive);
        ReflectionTestUtils.setField(compiler, "tdengineBindings", tdengine);
        ReflectionTestUtils.setField(compiler, "redisBindings", redis);
        ReflectionTestUtils.setField(compiler, "rabbitBindings", rabbit);
        ReflectionTestUtils.setField(compiler, "rocketBindings", rocket);
        var binding = new IntegrationRuntimeProperties.NativeTableBinding(); binding.setSparkNamespace(List.of("managed", "fixtures"));
        properties.setNativeTableBindings(Map.of(1L, binding, 2L, binding));
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("fixtures");
        when(connections.resolve(anyLong())).thenReturn(connection);
        when(databases.getTableMeta(anyLong(), anyString())).thenAnswer(invocation -> {
            var metadata = new DatabaseTableMetaRespVO(); metadata.setSchemaName("fixtures"); metadata.setTableName(invocation.getArgument(1));
            var id = new DatabaseTableColumnRespVO(); id.setFieldName("id"); id.setFieldTypeName("BIGINT");
            var label = new DatabaseTableColumnRespVO(); label.setFieldName("label"); label.setFieldTypeName("STRING");
            metadata.setColumns(List.of(id, label)); return metadata;
        });
        when(hive.requireOrdinaryText(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test void hdfsProgramsContainRealProviderParametersAndShareTaskAuthorizedSchemaAnalysis() {
        var binding = resource(Set.of("read-hdfs", "write-hdfs")); binding.setRootPath("/datahub_components");
        binding.setDefaultFs("hdfs://hdfs-native-namenode:8020"); binding.setInstalledPlugins(Set.of("spark-hdfs"));
        resources.setBindings(Map.of("hdfs", binding));
        var columns = List.of(Map.of("name", "id", "type", "Long", "index", 0), Map.of("name", "label", "type", "String", "index", 1));
        var graph = graph("read-hdfs", Map.of("resourceBindingId", "hdfs", "path", "source.csv", "columns", columns),
                "write-hdfs", Map.of("resourceBindingId", "hdfs", "path", "output", "fileName", "records", "compression", "gzip", "columns", columns));
        var program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("EtlHdfsTextProvider")); assertTrue(program.contains("datahub.filename` 'records'"));
        assertTrue(program.contains("compression` 'gzip'"));
        assertRenamedOutput(graph);
        var fields = compiler.analyzeNative(task(), EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(Map.of("id", "BIGINT", "label", "STRING"), fields.nodes().get("read").outputFields());
        binding.setAllowedTaskIds(Set.of(99L));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
    }

    @Test void hiveInputOutputPreservesFieldPredicateAndRequiresStorageEvidenceForBothTables() {
        connection.setProductType(ProductTypeEnum.HIVE);
        var fields = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"));
        var graph = graph("read-hive", Map.of("databaseId", 1, "tableName", "source", "columns", fields, "whereCondition", "id > 0"),
                "write-hive", Map.of("databaseId", 2, "tableName", "target", "columns", fields));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("WHERE")); assertTrue(program.contains("INSERT INTO"));
        verify(hive, times(2)).requireOrdinaryText(eq(connection), any());
        var analysis = compiler.analyzeNative(task(), EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(Set.of("id", "label"), analysis.nodes().get("read").outputFields().keySet());
    }

    @Test void unsupportedHiveStorageCannotBeBypassedByChoosingGenericTableComponents() {
        connection.setProductType(ProductTypeEnum.HIVE);
        doThrow(new IllegalArgumentException("ACID 不可映射为普通文件表")).when(hive).requireOrdinaryText(any(), any());
        var fields = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"));
        for (var codes : List.of(List.of("read-hive", "write-hive"), List.of("read-db", "insert-db"))) {
            var graph = graph(codes.getFirst(), Map.of("databaseId", 1, "tableName", "source", "columns", fields),
                    codes.getLast(), Map.of("databaseId", 2, "tableName", "target", "columns", fields));
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
            assertTrue(failure.getMessage().contains("ACID"));
        }
    }

    @Test void apiSourceFormalPayloadIncludesManagedFunctionAndRejectsOtherTaskResourceBindings() {
        var binding = resource(Set.of("rest-api")); binding.setAddresses(List.of("http://127.0.0.1:19084/rows"));
        binding.setInstalledPlugins(Set.of("spark-http")); resources.setBindings(Map.of("http", binding));
        properties.setManagedFunctions(Set.of("DH_READ_API"));
        properties.setSparkInitializationSql("CREATE TEMPORARY FUNCTION DH_READ_API AS 'cn.iocoder.yudao.framework.etl.spark.EtlHttpRowsFunction' USING JAR 'file:///managed/http.jar';");
        var api = Map.of("requestUrl", "http://127.0.0.1:19084/rows", "requestMethod", "GET", "dataKey", "data.rows",
                "dataColumns", List.of(Map.of("fieldName", "id", "fieldType", -5), Map.of("fieldName", "label", "fieldType", 12)));
        var graph = graph("rest-api", Map.of("resourceBindingId", "http", "apiConfig", api, "fetchSize", 100),
                "insert-db", Map.of("databaseId", 2, "tableName", "target"));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("CREATE TEMPORARY FUNCTION DH_READ_API")); assertTrue(program.contains("LATERAL VIEW DH_READ_API"));
        assertTrue(program.contains("USING JAR"));
        var runtime = new IntegrationDsRuntime(); ReflectionTestUtils.setField(runtime, "properties", properties);
        var originalSteps = compiler.compile(task(), graph.toString());
        var reversed = JSONUtil.parseObj(reverseKeys(graph)); var reorderedSteps = compiler.compile(task(), reversed.toString());
        assertEquals(runtime.fingerprint(task(), originalSteps), runtime.fingerprint(task(), reorderedSteps), "HTTP保存配置的对象键重排不能改变摘要");
        assertEquals(originalSteps.getFirst().parameters(), reorderedSteps.getFirst().parameters());
        binding.setAllowedTaskIds(Set.of(99L));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
    }

    @Test void soapSourceFormalPayloadPreservesManagedInitializationAndSecretReferenceScope() {
        var binding = resource(Set.of("read-webservice")); binding.setAuthentication(EtlDataxConnectorCompiler.Authentication.REQUIRED);
        binding.setAddresses(List.of("http://127.0.0.1:19085/wsdl", "http://127.0.0.1:19085/soap11"));
        binding.setInstalledPlugins(Set.of("spark-webservice")); resources.setBindings(Map.of("soap", binding));
        properties.setManagedFunctions(Set.of("DH_READ_SOAP"));
        properties.setSparkInitializationSql("CREATE TEMPORARY FUNCTION DH_READ_SOAP AS 'cn.iocoder.yudao.framework.etl.spark.EtlWebServiceRowsFunction' USING JAR 'file:///managed/soap.jar';");
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L));
        secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("SPARK")); properties.setManagedSecretBindings(Map.of("apiAccess", secret));
        var source = new LinkedHashMap<String, Object>(); source.put("resourceBindingId", "soap"); source.put("apiUrl", "http://127.0.0.1:19085/wsdl");
        source.put("operationName", "List"); source.put("apiMethod", "soap11"); source.put("serviceName", "Service"); source.put("portName", "soap11Port");
        source.put("requestParams", Map.of("label", "O'Reilly<&水")); source.put("returnDataLine", "ListResponse/rows/row");
        source.put("headerSecretRefs", Map.of("Authorization", "apiAccess"));
        source.put("columns", List.of(Map.of("name", "id", "type", "LONG"), Map.of("name", "label", "type", "STRING")));
        var graph = graph("read-webservice", source, "insert-db", Map.of("databaseId", 2, "tableName", "target"));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("CREATE TEMPORARY FUNCTION DH_READ_SOAP")); assertTrue(program.contains("LATERAL VIEW DH_READ_SOAP"));
        assertTrue(program.contains("USING JAR"));
        secret.setWorkerGroups(Set.of("other"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
    }

    @Test void tdengineFormalFlinkPayloadAndSchemaUseTheSameContinuousManagedBinding() {
        var task = task(); task.setEngine("FLINK"); task.setMode("REALTIME");
        var fields = new LinkedHashMap<String, String>(); fields.put("id", "BIGINT"); fields.put("label", "STRING");
        when(tdengine.require(eq(task), eq(1L), anyMap())).thenReturn(new EtlNativeTdengineCompiler.Binding("tdengine:6041", Set.of("sensors"),
                Set.of("group"), "tdAccess", fields, 500, false, true));
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L));
        secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("FLINK")); properties.setManagedSecretBindings(Map.of("tdAccess", secret));
        properties.setNativeKafkaBindings(Map.of(2L, new EtlNativeConnectorCompiler.KafkaBinding("kafka:9092", Set.of(), Set.of("result"),
                EtlNativeConnectorCompiler.Authentication.NONE, true)));
        var graph = graph("read-tdengine", Map.of("databaseId", 1, "topic", "sensors", "groupId", "group"), "write-kafka",
                Map.of("databaseId", 2, "topic", "result", "columns", List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"))));
        String program = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(program.contains("datahub-tdengine-tmq")); assertTrue(program.contains("credential-ref")); assertTrue(program.contains("tdAccess"));
        var analysis = compiler.analyzeNative(task, EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(fields, analysis.nodes().get("read").outputFields());
        secret.setEngines(Set.of("SPARK"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
    }

    @Test void redisFormalPayloadAndSchemaConsumeTaskAuthorizedSourceAndSinkPlans() {
        when(redis.require(any(), eq(1L))).thenReturn(new EtlNativeRedisCompiler.Binding("redis-native", 6379, 0, false, "redisAccess", Set.of("input:*"), Set.of("result:"), true));
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L));
        secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("SPARK")); properties.setManagedSecretBindings(Map.of("redisAccess", secret));
        properties.setManagedFunctions(Set.of("DH_READ_REDIS"));
        properties.setSparkInitializationSql("CREATE TEMPORARY FUNCTION DH_READ_REDIS AS 'cn.iocoder.yudao.framework.etl.spark.EtlRedisRowsFunction' USING JAR 'file:///managed/redis.jar';");
        var columns = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"));
        var graph = graph("read-redis", Map.of("databaseId", 1, "keyPattern", "input:*", "dataType", "hash", "columns", columns),
                "write-redis", Map.of("databaseId", 1, "keyPattern", "result:${id}", "dataType", "hash", "columns", columns, "batchSize", 2));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("CREATE TEMPORARY FUNCTION DH_READ_REDIS")); assertTrue(program.contains("EtlRedisProvider"));
        assertRenamedOutput(graph);
        var analysis = compiler.analyzeNative(task(), EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(Map.of("id", "BIGINT", "label", "STRING"), analysis.nodes().get("read").outputFields());
        secret.setAllowedTaskIds(Set.of(99L));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
    }

    @Test void rabbitFormalRealtimePayloadSharesSchemaAndCheckpointAcknowledgementSettings() {
        var task = task(); task.setEngine("FLINK"); task.setMode("REALTIME");
        var binding = new EtlNativeRabbitCompiler.Binding("rabbit-native", 5672, "/components", Set.of("components.in"), Set.of("components.out"), "rabbitAccess", false, true);
        when(rabbit.require(eq(task), anyLong(), anyString(), anyMap())).thenReturn(binding);
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L));
        secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("FLINK")); properties.setManagedSecretBindings(Map.of("rabbitAccess", secret));
        var columns = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"));
        var graph = graph("read-rabbitmq", Map.of("databaseId", 1, "topic", "components.in", "columns", columns, "config", Map.of("prefetchCount", 8)),
                "write-rabbitmq", Map.of("databaseId", 2, "topic", "components.out", "columns", columns, "batchSize", 1, "config", Map.of("confirmTimeoutMillis", 5000)));
        String program = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(program.contains("datahub-rabbitmq")); assertTrue(program.contains("SET 'execution.checkpointing.interval' = '60000ms'"));
        assertTrue(program.contains("prefetch-count")); assertTrue(program.contains("confirm.timeout.ms"));
        assertTrue(program.contains("SET 'restart-strategy.type' = 'fixed-delay'"));
        assertTrue(program.contains("SET 'restart-strategy.fixed-delay.attempts' = '3'"));
        assertTrue(program.contains("SET 'restart-strategy.fixed-delay.delay' = '1000ms'"));
        var analysis = compiler.analyzeNative(task, EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(Map.of("id", "BIGINT", "label", "STRING"), analysis.nodes().get("read").outputFields());
        properties.setFlinkRestartAttempts(0); properties.setFlinkRestartDelayMs(0);
        String noRestart = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(noRestart.contains("SET 'restart-strategy.type' = 'none'"));
        properties.setFlinkRestartAttempts(11);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
        properties.setFlinkRestartAttempts(3); properties.setFlinkRestartDelayMs(1000);
        secret.setWorkerGroups(Set.of("other"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
    }

    @Test void ftpFormalPayloadAndFieldAnalysisUseBothResourceAndSecretTaskScopes() {
        var binding = resource(Set.of("read-ftp", "write-ftp")); binding.setRootPath("/"); binding.setAddresses(List.of("ftp://fixture:21"));
        binding.setAuthentication(EtlDataxConnectorCompiler.Authentication.REQUIRED); binding.setInstalledPlugins(Set.of("spark-ftp", "ftp-chroot")); resources.setBindings(Map.of("ftp", binding));
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L)); secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("SPARK"));
        properties.setManagedSecretBindings(Map.of("ftpAccess", secret)); properties.setManagedFunctions(Set.of("DH_READ_FTP"));
        properties.setSparkInitializationSql("CREATE TEMPORARY FUNCTION DH_READ_FTP AS 'cn.iocoder.yudao.framework.etl.spark.EtlFtpRowsFunction' USING JAR 'file:///managed/ftp.jar';");
        var columns = List.of(Map.of("name", "id", "type", "Long", "index", 0), Map.of("name", "label", "type", "String", "index", 1));
        var graph = graph("read-ftp", Map.of("resourceBindingId", "ftp", "credentialRef", "ftpAccess", "path", "input.csv", "columns", columns),
                "write-ftp", Map.of("resourceBindingId", "ftp", "credentialRef", "ftpAccess", "path", "output", "fileName", "records", "writeModeType", 2, "columns", columns));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("EtlFtpTextProvider")); assertTrue(program.contains("CREATE TEMPORARY FUNCTION DH_READ_FTP"));
        var analysis = compiler.analyzeNative(task(), EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(Map.of("id", "BIGINT", "label", "STRING"), analysis.nodes().get("read").outputFields());
        secret.setAllowedTaskIds(Set.of(99L)); assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
        secret.setAllowedTaskIds(Set.of(4L)); binding.setAllowedTaskIds(Set.of(99L));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(), graph.toString()));
    }

    @Test void rocketFormalPayloadSharesSchemaAndRejectsCompetingDuplicateSourcesWithinTheTask() {
        var task = task(); task.setEngine("FLINK"); task.setMode("REALTIME");
        var binding = new EtlNativeRocketCompiler.Binding("rocket-nameserver:9876", Set.of("components.in"), Set.of("components.out"), Set.of("reader_group"), Set.of("writer_group"), "rocketAccess", false, true);
        when(rocket.require(eq(task), anyLong(), anyString(), anyMap())).thenReturn(binding);
        var secret = new IntegrationRuntimeProperties.ManagedSecretBinding(); secret.setAllowedTaskIds(Set.of(4L)); secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("FLINK"));
        properties.setManagedSecretBindings(Map.of("rocketAccess", secret));
        var columns = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String"));
        var source = Map.<String, Object>of("databaseId", 1, "topic", "components.in", "groupId", "reader_group", "columns", columns);
        var target = Map.<String, Object>of("databaseId", 2, "topic", "components.out", "groupId", "writer_group", "columns", columns, "batchSize", 1);
        var graph = graph("read-rocketmq", source, "write-rocketmq", target);
        String program = JSONUtil.toJsonStr(compiler.compile(task, graph.toString()).getFirst().parameters());
        assertTrue(program.contains("datahub-rocketmq")); assertTrue(program.contains("restart-strategy.fixed-delay.attempts"));
        var analysis = compiler.analyzeNative(task, EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))),
                EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        assertEquals(Map.of("id", "BIGINT", "label", "STRING"), analysis.nodes().get("read").outputFields());
        var duplicate = new JSONObject().set("nodes", List.of(node("first", "read-rocketmq", source), node("second", "read-rocketmq", source), node("write", "write-rocketmq", target)))
                .set("edges", List.of(Map.of("sourceNodeId", "first", "targetNodeId", "write"), Map.of("sourceNodeId", "second", "targetNodeId", "write")));
        var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, duplicate.toString())); assertTrue(failure.getMessage().contains("多个源"));
        secret.setWorkerGroups(Set.of("other")); assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph.toString()));
    }

    private static Object reverseKeys(Object value) {
        if (value instanceof Map<?, ?> object) {
            var result = new LinkedHashMap<String, Object>();
            object.keySet().stream().map(String::valueOf).sorted(Comparator.reverseOrder()).forEach(key -> result.put(key, reverseKeys(object.get(key)))); return result;
        }
        if (value instanceof Collection<?> array) return array.stream().map(IntegrationManagedConnectorCompilationTest::reverseKeys).toList();
        return value;
    }

    private void assertRenamedOutput(JSONObject original) {
        var graph = JSONUtil.parseObj(original.toString());
        var target = graph.getJSONArray("nodes").getJSONObject(1).getJSONObject("properties").getJSONObject("nodeJson");
        target.set("columns", List.of(Map.of("name", "id", "key", "id", "type", "Long"), Map.of("name", "标签", "key", "label", "type", "String")));
        target.set("fieldMappings", List.of(Map.of("source", "id", "target", "id", "enabled", true), Map.of("source", "label", "target", "标签", "enabled", true)));
        String program = JSONUtil.toJsonStr(compiler.compile(task(), graph.toString()).getFirst().parameters());
        assertTrue(program.contains("(`id`, `标签`) SELECT `id`, `label`"));
    }

    private static IntegrationDataxBindingsProperties.ManagedBinding resource(Set<String> codes) {
        var binding = new IntegrationDataxBindingsProperties.ManagedBinding(); binding.setAllowedTaskIds(Set.of(4L));
        binding.setConnectorCodes(codes); binding.setAuthentication(EtlDataxConnectorCompiler.Authentication.NONE); return binding;
    }
    private static IntegrationTaskDO task() {
        var task = new IntegrationTaskDO(); task.setId(4L); task.setEngine("SPARK"); task.setMode("OFFLINE"); task.setWorkerGroup("isolated");
        task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig())); return task;
    }
    private static JSONObject graph(String read, Map<String, ?> source, String write, Map<String, ?> target) {
        return new JSONObject().set("nodes", List.of(node("read", read, source), node("write", write, target)))
                .set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
    }
    private static Map<String, Object> node(String id, String code, Map<String, ?> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", code.startsWith("write") || code.equals("insert-db") ? 3 : 1, "nodeJson", config));
    }
}
