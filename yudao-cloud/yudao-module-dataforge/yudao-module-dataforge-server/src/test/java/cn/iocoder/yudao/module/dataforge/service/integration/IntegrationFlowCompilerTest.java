package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationFlowCompilerTest {
    private final IntegrationFlowCompiler compiler = new IntegrationFlowCompiler();
    { org.springframework.test.util.ReflectionTestUtils.setField(compiler, "properties", new IntegrationRuntimeProperties()); }

    @Test void writerUiStateIsRemovedOnlyFromExecutionCopyAfterSecretChecks() {
        var execution = new java.util.LinkedHashMap<String, Object>(Map.of("columns", List.of(Map.of("name", "target", "key", "source", "type", "STRING")),
                "fieldMappings", List.of(Map.of("source", "source", "target", "target", "enabled", true))));
        String legacy = graph(List.of(node("write", "write-mongodb", execution)), List.of());
        assertEquals(new JSONObject(execution), compiler.graph(legacy).getJSONArray("nodes").getJSONObject(0).getJSONObject("properties").getJSONObject("nodeJson"));
        execution.put("fieldMappingColumns", List.of(Map.of("name", "unused", "type", "STRING")));
        execution.put("fieldMappingDraft", List.of(Map.of("source", "missing", "target", "unused", "enabled", false)));
        execution.put("fieldMappingLayout", Map.of("version", 1, "sourceOrder", List.of("source")));
        execution.put("unknownExecutionOption", true);
        String persisted = graph(List.of(node("write", "write-mongodb", execution)), List.of());
        var cleaned = compiler.graph(persisted).getJSONArray("nodes").getJSONObject(0).getJSONObject("properties").getJSONObject("nodeJson");
        assertFalse(cleaned.containsKey("fieldMappingColumns")); assertFalse(cleaned.containsKey("fieldMappingDraft")); assertFalse(cleaned.containsKey("fieldMappingLayout"));
        assertTrue(cleaned.getBool("unknownExecutionOption")); assertTrue(persisted.contains("fieldMappingDraft"));
        var unknown = assertThrows(IllegalArgumentException.class, () -> cn.iocoder.yudao.framework.etl.core.compile.EtlNativeMongoCompiler.compile(
                cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.SPARK, false, "write", "write-mongodb", cleaned,
                new cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler.Binding(java.util.Set.of("write-mongodb"), null, null,
                        List.of("localhost:27017"), "db", cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler.Authentication.ANONYMOUS,
                        java.util.Set.of("mongodbwriter", "spark-mongodb"))));
        assertTrue(unknown.getMessage().contains("unknownExecutionOption"));
        execution.put("fieldMappingDraft", Map.of("password", "private"));
        assertThrows(IllegalArgumentException.class, () -> compiler.graph(graph(List.of(node("write", "write-mongodb", execution)), List.of())));
    }

    @Test void dataxOnlyAllowsThreeComponentsEvenForDraftAndCompilesRealDedupReaderSql() {
        prepareDatax(); var task = task("DATAX", "OFFLINE");
        var nodes = List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("dedup", "data-clean", Map.of("enableDeduplication", true, "deduplicationStrategy", "ALL_FIELDS")),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target")));
        var edges = List.<Map<String, Object>>of(Map.of("sourceNodeId", "read", "targetNodeId", "dedup"), Map.of("sourceNodeId", "dedup", "targetNodeId", "write"));
        var sql = String.valueOf(compiler.compile(task, graph(nodes, edges)).getFirst().parameters().get("sql"));
        assertTrue(sql.startsWith("SELECT DISTINCT"), sql); assertTrue(sql.contains("source"));
        for (String tool : List.of("read-csv", "write-mongodb", "calculator", "data-clean")) {
            String invalid = graph(List.of(node("bad", tool, Map.of())), List.of());
            assertThrows(IllegalArgumentException.class, () -> compiler.validateEngineComponents(task, invalid), tool);
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, invalid), tool);
        }
        var byKey = node("dedup", "data-clean", Map.of("enableDeduplication", true, "deduplicationStrategy", "KEY_FIELDS", "keyFields", List.of("id"),
                "orderBy", List.of(Map.of("field", "id", "order", "ASC")), "uniqueFields", List.of("id")));
        var keyed = String.valueOf(compiler.compile(task, graph(List.of(nodes.getFirst(), byKey, nodes.getLast()), edges)).getFirst().parameters().get("sql"));
        assertTrue(keyed.contains("ROW_NUMBER() OVER (PARTITION BY \"id\""), keyed);
        assertTrue(keyed.contains("DataHub ordering key is not unique"));
    }

    @Test void flinkProductWhitelistRejectsTheOtherThirtySevenBeforeCheckRunPublicationOrDraftResources() {
        var task = task("FLINK", "OFFLINE");
        for (var component : new IntegrationComponentCapabilitiesService().get("FLINK", "OFFLINE").components()) {
            String flow = graph(List.of(node("n", component.componentCode(), Map.of())), List.of());
            if (component.status() != cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO.Status.UNAVAILABLE)
                assertDoesNotThrow(() -> compiler.validateEngineComponents(task, flow), component.componentCode());
            else {
                assertThrows(IllegalArgumentException.class, () -> compiler.validateEngineComponents(task, flow), component.componentCode());
                for (var failure : List.of(assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, flow)),
                        assertThrows(IllegalArgumentException.class, () -> compiler.compileForExecution(task, flow)),
                        assertThrows(IllegalArgumentException.class, () -> compiler.compileForPublication(task, flow))))
                    assertTrue(failure.getMessage().contains("12个组件"), component.componentCode());
            }
        }
        assertDoesNotThrow(() -> compiler.validateEngineComponents(task, graph(List.of(node("row", "row-column", Map.of("mode", "COL2ROW"))), List.of())));
        assertThrows(IllegalArgumentException.class, () -> compiler.validateEngineComponents(task, graph(List.of(node("api", "rest-api", Map.of())), List.of())));
    }

    @Test void realtimeCannotUseSparkOrDataxThroughDraftCheckPublicationOrRun() {
        String flow = graph(List.of(node("r", "read-db", Map.of("databaseId", 1, "tableName", "source"))), List.of());
        for (String engine : List.of("SPARK", "DATAX")) {
            var task = task(engine, "REALTIME");
            assertThrows(IllegalArgumentException.class, () -> compiler.validateEngineComponents(task, flow));
            for (var failure : List.of(assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, flow)),
                    assertThrows(IllegalArgumentException.class, () -> compiler.compileForExecution(task, flow)),
                    assertThrows(IllegalArgumentException.class, () -> compiler.compileForPublication(task, flow))))
                assertTrue(failure.getMessage().contains("实时任务只支持Flink"));
        }
    }

    @Test void upsertRequiresManagedWorkerAndAnExplicitKeyThenUsesDistinctTaskType() {
        prepareDatax();
        var task = task("DATAX", "OFFLINE"); task.setWorkerGroup("default");
        var flow = graph(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target", "dataSyncType", 2, "upsertKeys", List.of("id")))),
                List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, flow));
        var properties = (IntegrationRuntimeProperties) org.springframework.test.util.ReflectionTestUtils.getField(compiler, "properties");
        properties.setDataxUpsertWorkerGroups(java.util.Set.of("default"));
        var step = compiler.compile(task, flow).getFirst();
        assertEquals("DATAX_UPSERT", step.taskType());
        assertEquals(List.of("id"), step.parameters().get("upsertKeys"));
        assertEquals("public", step.parameters().get("upsertSchema"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, flow.replace("\"upsertKeys\":[\"id\"]", "\"upsertKeys\":[]")));
    }

    @Test void databaseSqlHooksCannotBeSilentlyIgnored() {
        prepareDatax();
        for (String hook : List.of("preSql", "postSql")) {
            var flow = graph(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                    node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target", hook, "DELETE FROM target"))),
                    List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("DATAX", "OFFLINE"), flow));
            assertTrue(failure.getMessage().contains("前后置 SQL"));
        }
    }

    @Test void transactionHooksReachTheVersionedWriterAndRejectUnsupportedSql() {
        prepareDatax();
        var properties = (IntegrationRuntimeProperties) org.springframework.test.util.ReflectionTestUtils.getField(compiler, "properties");
        properties.setDataxUpsertWorkerGroups(java.util.Set.of("default"));
        var task = task("DATAX", "OFFLINE"); task.setWorkerGroup("default");
        var target = new java.util.LinkedHashMap<String, Object>(Map.of("databaseId", 2, "tableName", "target", "dataSyncType", 2, "upsertKeys", List.of("id")));
        target.put("preSql", "DELETE FROM public.target WHERE id < 0;");
        target.put("postSql", "UPDATE public.audit SET count=count+1 WHERE id=1;");
        var edges = List.<Map<String, Object>>of(Map.of("sourceNodeId", "read", "targetNodeId", "write"));
        var source = node("read", "read-db", Map.of("databaseId", 1, "tableName", "source"));
        var step = compiler.compile(task, graph(List.of(source, node("write", "insert-db", target)), edges)).getFirst();
        assertEquals("DATAX_UPSERT", step.taskType());
        assertEquals("POSTGRESQL_UPSERT_HOOKS_V2", step.parameters().get("writeContract"));
        assertEquals(1, ((List<?>) step.parameters().get("preStatements")).size());
        assertEquals(1, ((List<?>) step.parameters().get("postStatements")).size());
        for (String invalidSql : List.of("COMMIT", "DROP TABLE public.target", "UPDATE private.audit SET count=1", "UPDATE public._datahub_upsert_runs SET state='SUCCESS'")) {
            target.put("postSql", invalidSql);
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, graph(List.of(source, node("write", "insert-db", target)), edges)));
        }
    }

    @Test void flinkBoundNativeGraphUsesAttachedStreamingAndConfiguredResources() {
        prepareDatax(); var properties = (IntegrationRuntimeProperties) org.springframework.test.util.ReflectionTestUtils.getField(compiler, "properties");
        var binding = new IntegrationRuntimeProperties.NativeTableBinding(); binding.setFlinkNamespace(List.of("default_catalog", "default_database")); binding.setFlinkContinuousSource(true); binding.setFlinkContinuousTables(java.util.Set.of("source"));
        properties.setNativeTableBindings(Map.of(1L, binding, 2L, binding));
        var task = task("FLINK", "REALTIME"); var config = new EngineConfig(); config.setSlots(3); config.setTaskManagerCount(2);
        config.setParallelism(4); config.setJobManagerMemory("2G"); task.setEngineConfigJson(JsonUtils.toJsonString(config));
        var step = compiler.compile(task, graph(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))),
                List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")))).getFirst();
        assertEquals("FLINK", step.taskType()); assertEquals(4, step.parameters().get("parallelism"));
        assertEquals("2G", step.parameters().get("jobManagerMemory"));
        assertTrue(String.valueOf(step.parameters().get("initScript")).contains("STREAMING"));
        assertTrue(String.valueOf(step.parameters().get("initScript")).contains("60000ms"));
        assertTrue(String.valueOf(step.parameters().get("initScript")).contains("execution.attached"));
    }

    @Test void scriptNodesCannotBypassManagedSourceAndCatalogAuthorization() {
        for (String engine : List.of("SPARK", "FLINK")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task(engine, "OFFLINE"),
                    graph(List.of(node("script", "sql-script", Map.of("sql", "DROP TABLE unrelated.private_table"))), List.of())));
            assertTrue(failure.getMessage().contains("FLINK".equals(engine) ? "12个组件" : "独立 SQL"));
        }
    }

    @Test void unsupportedTransformCanBeDraftedButCannotPretendToBeNativeExecutable() {
        String graph = graph(List.of(node("convert", "column-map", Map.of())), List.of());
        assertNotNull(compiler.graph(graph));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("FLINK", "REALTIME"), graph));
    }

    @Test void rejectsCyclesAndDanglingEdges() {
        var a = node("a", "sql-script", Map.of("sql", "SELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("SPARK", "OFFLINE"), graph(List.of(a), List.of(Map.of("sourceNodeId", "a", "targetNodeId", "a")))));
        assertThrows(IllegalArgumentException.class, () -> compiler.graph(graph(List.of(a), List.of(Map.of("sourceNodeId", "missing", "targetNodeId", "a")))));
    }

    @Test void rejectsNestedSecretsTemplatesAndNativeSessionOverrides() {
        assertThrows(IllegalArgumentException.class, () -> compiler.graph(graph(List.of(node("a", "read-db", Map.of("password", "fixture"))), List.of())));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("FLINK", "REALTIME"), graph(List.of(node("a", "sql-script", Map.of("sql", "SELECT ${unsafe}"))), List.of())));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("FLINK", "REALTIME"), graph(List.of(node("a", "sql-script", Map.of("sql", "SET 'execution.attached'='false'; SELECT 1"))), List.of())));
    }

    @Test void dataxFullReadHonorsMetadataProjectionMappingAndWhere() {
        var source = Map.<String, Object>of("databaseId", 1, "tableName", "source", "selectedColumns", List.of("id"), "whereCondition", "id > 10");
        var target = Map.<String, Object>of("databaseId", 2, "tableName", "target", "fieldMappings", List.of(Map.of("source", "id", "target", "target_id", "enabled", true)));
        prepareDatax();
        var step = compiler.compile(task("DATAX", "OFFLINE"), graph(List.of(node("source", "read-db", source), node("target", "insert-db", target)),
                List.of(Map.of("sourceNodeId", "source", "targetNodeId", "target")))).getFirst();
        String sql = String.valueOf(step.parameters().get("sql"));
        assertTrue(sql.contains("\"id\" AS \"target_id\""), sql); assertTrue(sql.contains("id > 10"), sql);
        assertEquals(17, step.parameters().get("dataSource")); assertEquals(19, step.parameters().get("dataTarget"));
    }

    @Test void dataxNeverIgnoresIncrementalModeOrDestructiveOutput() {
        prepareDatax();
        var edges = List.<Map<String, Object>>of(Map.of("sourceNodeId", "source", "targetNodeId", "target"));
        var source = Map.<String, Object>of("databaseId", 1, "tableName", "source", "readMode", "ID_INCREMENT");
        var target = Map.<String, Object>of("databaseId", 2, "tableName", "target");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("DATAX", "OFFLINE"), graph(List.of(node("source", "read-db", source), node("target", "insert-db", target)), edges)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("DATAX", "OFFLINE"), graph(List.of(node("source", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("target", "insert-db", Map.of("databaseId", 2, "tableName", "target", "targetTruncate", true))), edges)));
    }

    @Test void tableReadDraftContractIsValidatedAndExecutionCannotIgnoreBatchOrCursorConfiguration() {
        for (var config : List.of(Map.<String, Object>of("readMode", "ID_INCREMENT", "incrementColumn", "id", "startValue", "9007199254740993"),
                Map.<String, Object>of("readMode", "TIME_INCREMENT", "timeFormat", "yyyy-MM-dd", "timeConditions", List.of(Map.of("field", "updated_at", "operator", ">=", "baselineType", "FIXED", "baselineValue", "2026-11-03"))),
                Map.<String, Object>of("readMode", "FULL", "batchRead", Map.of("enabled", true, "mode", "ID", "start", "0", "size", "1000")))) {
            String flow = graph(List.of(node("source", "read-db", config)), List.of());
            assertDoesNotThrow(() -> compiler.graph(flow));
            for (String engine : List.of("DATAX", "FLINK", "SPARK")) {
                var task = task(engine, "OFFLINE");
                var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task, flow));
                assertTrue(failure.getMessage().contains("可保存"), failure.getMessage());
                assertThrows(IllegalArgumentException.class, () -> compiler.compileForExecution(task, flow));
                assertThrows(IllegalArgumentException.class, () -> compiler.compileForPublication(task, flow));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> compiler.graph(graph(List.of(node("source", "read-db",
                Map.of("readMode", "ID_INCREMENT", "startValue", 9007199254740993L))), List.of())));
    }

    @Test void dataxOutputWhereIsRejectedInsteadOfIgnored() {
        prepareDatax(); var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("DATAX", "OFFLINE"),
                graph(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                        node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target", "whereCondition", "id>0"))),
                        List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")))));
        assertTrue(failure.getMessage().contains("WHERE"));
    }

    @Test void dataxNodeSpecificFailureAndTimeoutAreNotSilentlyIgnored() {
        for (String field : List.of("failGoOn", "overTimes")) {
            var node = new java.util.LinkedHashMap<>(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")));
            var props = new java.util.LinkedHashMap<>((Map<String, Object>) node.get("properties")); props.put(field, 1); node.put("properties", props);
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("DATAX", "OFFLINE"), graph(List.of(node), List.of())));
            assertTrue(failure.getMessage().contains("独立超时"));
        }
    }

    @Test void realtimeCatalogRequiresBothManagedFlagAndExactSourceTableAllowlist() {
        prepareDatax(); var properties = (IntegrationRuntimeProperties) org.springframework.test.util.ReflectionTestUtils.getField(compiler, "properties");
        var binding = new IntegrationRuntimeProperties.NativeTableBinding(); binding.setFlinkNamespace(List.of("default_catalog", "default_database")); binding.setFlinkContinuousSource(true);
        properties.setNativeTableBindings(Map.of(1L, binding, 2L, binding));
        String graph = graph(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), List.of(Map.of("sourceNodeId", "read", "targetNodeId", "write")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("FLINK", "REALTIME"), graph));
        binding.setFlinkContinuousTables(java.util.Set.of("other"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("FLINK", "REALTIME"), graph));
        binding.setFlinkContinuousTables(java.util.Set.of("source")); binding.setFlinkContinuousSource(false);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(task("FLINK", "REALTIME"), graph));
    }

    private void prepareDatax() {
        var properties = new IntegrationRuntimeProperties();
        var sourceBinding = new IntegrationRuntimeProperties.DatabaseBinding(); sourceBinding.setDsDatasourceId(17); sourceBinding.setDsType("POSTGRESQL");
        var targetBinding = new IntegrationRuntimeProperties.DatabaseBinding(); targetBinding.setDsDatasourceId(19); targetBinding.setDsType("POSTGRESQL");
        properties.setDatabaseBindings(Map.of(1L, sourceBinding, 2L, targetBinding));
        var resolver = org.mockito.Mockito.mock(cn.iocoder.yudao.module.dataforge.service.etl.EtlConnectionResolverAdapter.class);
        var access = org.mockito.Mockito.mock(cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.class);
        var databases = org.mockito.Mockito.mock(cn.iocoder.yudao.module.dataforge.service.database.DatabaseService.class);
        var connection = new cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo();
        connection.setSchema("public"); connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.anyLong())).thenReturn(connection);
        var metadata = new cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO();
        var id = new cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO(); id.setFieldName("id");
        id.setFieldTypeName("BIGINT");
        var targetId = new cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO(); targetId.setFieldName("target_id"); targetId.setFieldTypeName("BIGINT");
        metadata.setSchemaName("public"); metadata.setTableName("source");
        metadata.setColumns(List.of(id, targetId));
        org.mockito.Mockito.when(databases.getTableMeta(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(metadata);
        org.springframework.test.util.ReflectionTestUtils.setField(compiler, "properties", properties);
        org.springframework.test.util.ReflectionTestUtils.setField(compiler, "connections", resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(compiler, "access", access);
        org.springframework.test.util.ReflectionTestUtils.setField(compiler, "databases", databases);
    }

    private static IntegrationTaskDO task(String engine, String mode) {
        var task = new IntegrationTaskDO(); task.setId(7L); task.setEngine(engine); task.setMode(mode);
        task.setEngineConfigJson(JsonUtils.toJsonString(new EngineConfig())); return task;
    }

    private static Map<String, Object> node(String id, String code, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", 4, "nodeJson", config));
    }

    private static String graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return new JSONObject().set("nodes", nodes).set("edges", edges).toString();
    }
}
