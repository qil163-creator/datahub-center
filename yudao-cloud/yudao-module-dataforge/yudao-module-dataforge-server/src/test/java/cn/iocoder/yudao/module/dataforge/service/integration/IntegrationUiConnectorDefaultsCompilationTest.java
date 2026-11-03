package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.compile.*;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 正式编译直接使用编辑器保存的配置，避免仅手工精简样例能够执行。 */
class IntegrationUiConnectorDefaultsCompilationTest {
    private final IntegrationFlowCompiler compiler = new IntegrationFlowCompiler();
    private final IntegrationRuntimeProperties properties = new IntegrationRuntimeProperties();
    private final IntegrationDataxBindingsProperties resources = new IntegrationDataxBindingsProperties();
    private final EtlConnectionInfo connection = new EtlConnectionInfo();
    private final DatabaseService databases = mock(DatabaseService.class);
    private final IntegrationRabbitBindingSupport rabbit = mock(IntegrationRabbitBindingSupport.class);
    private final IntegrationRocketBindingSupport rocket = mock(IntegrationRocketBindingSupport.class);
    private final IntegrationOdpsBindingSupport odps = mock(IntegrationOdpsBindingSupport.class);
    private final IntegrationTaskDO task = new IntegrationTaskDO();
    private JSONObject configs;

    @BeforeEach void prepare() throws IOException {
        configs = fixtures(); task.setId(4L); task.setEngine("SPARK"); task.setMode("OFFLINE"); task.setWorkerGroup("isolated");
        task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig()));
        var connections = mock(EtlConnectionResolverAdapter.class); var hive = mock(IntegrationHiveStorageSupport.class);
        ReflectionTestUtils.setField(compiler, "properties", properties); ReflectionTestUtils.setField(compiler, "dataxBindings", resources);
        ReflectionTestUtils.setField(compiler, "access", mock(ExecutionAccessService.class)); ReflectionTestUtils.setField(compiler, "connections", connections);
        ReflectionTestUtils.setField(compiler, "databases", databases); ReflectionTestUtils.setField(compiler, "hiveStorage", hive);
        ReflectionTestUtils.setField(compiler, "rabbitBindings", rabbit); ReflectionTestUtils.setField(compiler, "rocketBindings", rocket); ReflectionTestUtils.setField(compiler, "odpsBindings", odps);
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("fixtures"); when(connections.resolve(anyLong())).thenReturn(connection);
        when(hive.requireOrdinaryText(any(), any())).thenAnswer(call -> call.getArgument(1));
        when(databases.getTableMeta(anyLong(), anyString())).thenAnswer(call -> {
            String table = call.getArgument(1); var metadata = new DatabaseTableMetaRespVO(); metadata.setTableName(table); metadata.setSchemaName("fixtures");
            var id = new DatabaseTableColumnRespVO(); id.setFieldName("id"); id.setFieldTypeName("BIGINT");
            var name = new DatabaseTableColumnRespVO(); name.setFieldName(table.equals("target_table") ? "标签" : "name"); name.setFieldTypeName("STRING");
            metadata.setColumns(List.of(id, name)); return metadata;
        });
        var table = new IntegrationRuntimeProperties.NativeTableBinding(); table.setSparkNamespace(List.of("managed", "fixtures"));
        properties.setNativeTableBindings(Map.of(1L, table, 2L, table));
    }

    @Test void hiveEditorFullReadAndRenameAppendCompileAndAnalyzeWithoutRemovingDefaults() {
        connection.setProductType(ProductTypeEnum.HIVE); assertRenamed("hive");
    }

    @Test void odpsEditorFullReadAndRenameAppendConsumeTheExactResourceSchema() {
        var source = new LinkedHashMap<String, String>(); source.put("id", "BIGINT"); source.put("name", "STRING");
        var target = new LinkedHashMap<String, String>(); target.put("id", "BIGINT"); target.put("标签", "STRING");
        var binding = new EtlNativeOdpsCompiler.Binding("https://service.example/api", "https://tunnel.example", "fixture", "odps.fixture",
                List.of(new EtlNativeOdpsCompiler.Resource("source_table", "", source, true, false, false),
                        new EtlNativeOdpsCompiler.Resource("target_table", "", target, false, true, false)), true, false);
        when(odps.require(any(), anyLong())).thenReturn(binding); functions("DH_READ_ODPS", "EtlOdpsRowsFunction"); secret("odps.fixture");
        assertRenamed("odps");
    }

    @Test void rabbitEditorEmptyJsonDefaultsAndRenameSinkReachFormalCheckpointedFlinkPayload() {
        realtime(); secret("rabbit.fixture");
        when(rabbit.require(any(), anyLong(), anyString(), anyMap())).thenReturn(new EtlNativeRabbitCompiler.Binding("rabbit-native", 5672, "/components",
                Set.of("components.in"), Set.of("components.out"), "rabbit.fixture", false, true));
        assertRenamed("rabbitmq");
    }

    @Test void rocketEditorGroupOffsetAndRenameDefaultsAreConsumedWithoutHiddenParameters() {
        realtime(); secret("rocket.fixture");
        when(rocket.require(any(), anyLong(), anyString(), anyMap())).thenReturn(new EtlNativeRocketCompiler.Binding("rocket-nameserver:9876",
                Set.of("components_input"), Set.of("components_output"), Set.of("components_read"), Set.of("components_write"), "rocket.fixture", false, true));
        assertRenamed("rocketmq");
    }

    @Test void ftpEditorDottedResourceAndSecretReferencesReachNativeRenameInsert() {
        var binding = resource(Set.of("read-ftp", "write-ftp")); binding.setAuthentication(EtlDataxConnectorCompiler.Authentication.REQUIRED);
        binding.setRootPath("/"); binding.setAddresses(List.of("ftp://fixture:21")); binding.setInstalledPlugins(Set.of("spark-ftp", "ftp-chroot"));
        resources.setBindings(Map.of("ftp.fixture", binding)); functions("DH_READ_FTP", "EtlFtpRowsFunction"); secret("ftp.fixture"); assertRenamed("ftp");
        binding.setAllowedTaskIds(Set.of(99L)); assertThrows(IllegalArgumentException.class, () -> compile("read-ftp", "write-ftp"));
    }

    @Test void soapEditorDottedResourceAndStringJsonDefaultsReachFormalNativePayload() {
        var binding = resource(Set.of("read-webservice")); binding.setAddresses(List.of("http://soap-native:8080/service?wsdl")); binding.setInstalledPlugins(Set.of("spark-webservice"));
        resources.setBindings(Map.of("soap.fixture", binding)); functions("DH_READ_SOAP", "EtlWebServiceRowsFunction");
        var graph = graph("read-webservice", configs.getJSONObject("read-webservice"), "insert-db", Map.of("databaseId", 2, "tableName", "source_table"));
        var steps = compiler.compile(task, graph.toString()); String payload = JSONUtil.toJsonStr(steps.getFirst().parameters());
        assertTrue(payload.contains("LATERAL VIEW DH_READ_SOAP")); analyze(graph); assertStableAfterJsonbObjectKeyReordering(graph, steps);
    }

    @Test void actualPlatformHttpToFtpSavedRequestRetainsExactPayloadAndFingerprintAfterJsonbReordering() throws IOException {
        var fixture = fixture("components-20261102-platform-http-ftp-flow.json"); task.setId(fixture.getLong("taskId"));
        var http = resource(Set.of("rest-api")); http.setAllowedTaskIds(Set.of(task.getId())); http.setAuthentication(EtlDataxConnectorCompiler.Authentication.REQUIRED);
        http.setInstalledPlugins(Set.of("spark-http")); http.setAddresses(List.of("http://127.0.0.1:19087/rows"));
        platformFtp(Set.of("ftpAccess", "apiAccess"), Map.of("componentHttp", http), false);
        var graph = fixture.getJSONObject("flow"); var steps = compiler.compile(task, graph.toString()); assertStableAfterJsonbObjectKeyReordering(graph, steps);
        assertEquals(Map.of("id", "BIGINT", "label", "STRING", "amount", "DECIMAL(38,18)"), analyze(graph).nodes().get("api").outputFields());
    }

    @Test void actualPlatformCryptoToFtpSavedRequestRetainsExactPayloadAndFingerprintAfterJsonbReordering() throws IOException {
        var fixture = fixture("components-20261102-platform-crypto-ftp-flow.json"); task.setId(fixture.getLong("taskId"));
        platformFtp(Set.of("ftpAccess", "cryptoAccess"), Map.of(), true);
        var graph = fixture.getJSONObject("flow"); var steps = compiler.compile(task, graph.toString()); assertStableAfterJsonbObjectKeyReordering(graph, steps);
        assertTrue(analyze(graph).nodes().get("decrypt").outputFields().containsKey("restored"));
        assertFalse(analyze(graph).nodes().get("decrypt").outputFields().containsKey("cipher"));
    }

    private void platformFtp(Set<String> references, Map<String, IntegrationDataxBindingsProperties.ManagedBinding> other, boolean crypto) {
        var ftp = resource(Set.of("read-ftp", "write-ftp")); ftp.setAllowedTaskIds(Set.of(task.getId())); ftp.setAuthentication(EtlDataxConnectorCompiler.Authentication.REQUIRED);
        ftp.setRootPath("/"); ftp.setAddresses(List.of("ftp://fixture:21")); ftp.setInstalledPlugins(Set.of("spark-ftp", "ftp-chroot"));
        var bindings = new LinkedHashMap<>(other); bindings.put("componentFtp", ftp); resources.setBindings(bindings);
        var secretBindings = new LinkedHashMap<String, IntegrationRuntimeProperties.ManagedSecretBinding>();
        for (String reference : references) {
            var binding = new IntegrationRuntimeProperties.ManagedSecretBinding(); binding.setAllowedTaskIds(Set.of(task.getId())); binding.setWorkerGroups(Set.of("isolated")); binding.setEngines(Set.of("SPARK")); secretBindings.put(reference, binding);
        }
        properties.setManagedSecretBindings(secretBindings);
        var functions = new LinkedHashSet<>(Set.of("DH_READ_API", "DH_READ_FTP")); if (crypto) functions.addAll(Set.of("DH_CRYPTO_ENCRYPT", "DH_CRYPTO_DECRYPT"));
        properties.setManagedFunctions(functions);
        properties.setSparkInitializationSql(String.join("\n", functions.stream().sorted().map(name -> "CREATE TEMPORARY FUNCTION " + name + " AS 'cn.iocoder.yudao.framework.etl.spark.ManagedFixtureFunction' USING JAR 'file:///managed/component.jar';").toList()));
    }

    @Test void resourceIdentifiersCannotTurnIntoPathsOrBypassTaskOrConnectorAuthorization() {
        var binding = resource(Set.of("read-ftp")); resources.setBindings(Map.of("ftp.fixture", binding));
        assertNotNull(resources.require(4L, "read-ftp", null, "ftp.fixture"));
        for (String id : List.of(".fixture", "../ftp.fixture", "ftp/fixture", "ftp fixture", "ftp%2efixture", "ftp:fixture", ""))
            assertThrows(IllegalArgumentException.class, () -> resources.require(4L, "read-ftp", null, id));
        assertThrows(IllegalArgumentException.class, () -> resources.require(99L, "read-ftp", null, "ftp.fixture"));
        assertThrows(IllegalArgumentException.class, () -> resources.require(4L, "write-ftp", null, "ftp.fixture"));
        binding.setDatabaseId(1L);
        assertNotNull(resources.require(4L, "read-ftp", 1L, "ftp.fixture"));
        assertNotNull(resources.require(4L, "read-ftp", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> resources.require(4L, "read-ftp", 1L, "different.fixture"));
        assertThrows(IllegalArgumentException.class, () -> resources.require(4L, "read-ftp", 1L, "../ftp.fixture"));
    }

    private void assertRenamed(String suffix) {
        var graph = compile("read-" + suffix, "write-" + suffix);
        var analysis = analyze(graph); assertEquals(Map.of("id", "BIGINT", "name", "STRING"), analysis.nodes().get("read").outputFields());
        var invalid = JSONUtil.parseObj(configs.getJSONObject("write-" + suffix).toString());
        invalid.getJSONArray("fieldMappings").getJSONObject(1).set("source", "missing_source");
        var bad = graph("read-" + suffix, configs.getJSONObject("read-" + suffix), "write-" + suffix, invalid);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, bad.toString()));
    }
    private JSONObject compile(String read, String write) {
        var graph = graph(read, configs.getJSONObject(read), write, configs.getJSONObject(write));
        var steps = compiler.compile(task, graph.toString()); String payload = JSONUtil.toJsonStr(steps.getFirst().parameters());
        assertStableAfterJsonbObjectKeyReordering(graph, steps);
        assertTrue(payload.contains("(`id`, `标签`) SELECT `id`, `name`"), payload);
        if (task.getMode().equals("REALTIME")) assertTrue(payload.contains("execution.checkpointing.interval"));
        return graph;
    }
    private void assertStableAfterJsonbObjectKeyReordering(JSONObject graph, List<IntegrationFlowCompiler.Step> steps) {
        var reordered = JSONUtil.parseObj(reverseKeys(graph));
        var recompilation = compiler.compile(task, reordered.toString());
        var runtime = new IntegrationDsRuntime(); ReflectionTestUtils.setField(runtime, "properties", properties);
        assertEquals(runtime.fingerprint(task, steps), runtime.fingerprint(task, recompilation), "JSONB对象键重排不能改变同一执行配置的绑定摘要");
        assertEquals(steps.getFirst().parameters(), recompilation.getFirst().parameters(), "正式SQL及资源参数也必须完全一致");
    }
    private static Object reverseKeys(Object value) {
        if (value instanceof Map<?, ?> object) {
            var result = new LinkedHashMap<String, Object>();
            object.keySet().stream().map(String::valueOf).sorted(Comparator.reverseOrder()).forEach(key -> result.put(key, reverseKeys(object.get(key)))); return result;
        }
        if (value instanceof Collection<?> array) return array.stream().map(IntegrationUiConnectorDefaultsCompilationTest::reverseKeys).toList();
        return value;
    }
    private EtlNativeSqlCompiler.Analysis analyze(JSONObject graph) {
        return compiler.analyzeNative(task, EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))), EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
    }
    private void realtime() { task.setEngine("FLINK"); task.setMode("REALTIME"); }
    private void functions(String function, String implementation) {
        properties.setManagedFunctions(Set.of(function)); properties.setSparkInitializationSql("CREATE TEMPORARY FUNCTION " + function
                + " AS 'cn.iocoder.yudao.framework.etl.spark." + implementation + "' USING JAR 'file:///managed/component.jar';");
    }
    private void secret(String reference) {
        var binding = new IntegrationRuntimeProperties.ManagedSecretBinding(); binding.setAllowedTaskIds(Set.of(4L)); binding.setWorkerGroups(Set.of("isolated")); binding.setEngines(Set.of(task.getEngine()));
        properties.setManagedSecretBindings(Map.of(reference, binding));
    }
    private static IntegrationDataxBindingsProperties.ManagedBinding resource(Set<String> codes) {
        var binding = new IntegrationDataxBindingsProperties.ManagedBinding(); binding.setAllowedTaskIds(Set.of(4L)); binding.setConnectorCodes(codes);
        binding.setAuthentication(EtlDataxConnectorCompiler.Authentication.NONE); return binding;
    }
    private static JSONObject graph(String read, Map<String, ?> source, String write, Map<String, ?> target) {
        return new JSONObject().set("nodes", List.of(node("read", read, source), node("write", write, target)))
                .set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
    }
    private static Map<String, Object> node(String id, String code, Map<String, ?> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", code.startsWith("write") || code.equals("insert-db") ? 3 : 1, "nodeJson", config));
    }
    private static JSONObject fixtures() throws IOException {
        return fixture("components-20261102-ui-default-payloads.json").getJSONObject("configs");
    }
    private static JSONObject fixture(String name) throws IOException {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            var file = root.resolve("deploy/dolphinscheduler-local/verification/" + name);
            if (Files.isRegularFile(file)) return JSONUtil.parseObj(Files.readString(file));
        }
        throw new IOException("缺少实际平台配置样例：" + name);
    }
}
