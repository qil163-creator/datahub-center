package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真 PostgreSQL：全局唯一性、CAS、模式权限、冻结绑定、迁移首放与重放。 */
@Testcontainers
class EtlEnvironmentPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    private EtlBatchPgFixture pg;
    private EtlEnvironmentService service;
    private Long environmentId;
    private Long bindingId;
    @BeforeEach void setup() throws Exception {
        pg = new EtlBatchPgFixture(POSTGRES); service = pg.app.getBean(EtlEnvironmentService.class);
        environmentId = service.save(environment(null, null, "dev", true));
        bindingId = service.saveBinding(binding(null, null), 99L);
    }
    @AfterEach void close() throws Exception { if (pg != null) pg.close(); }

    @Test void migrationReplayAndGlobalUniquenessAndForeignKey() throws Exception {
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(pg.control, pg.schema);
        assertEquals(1, service.list().size());
        assertThrows(ServiceException.class, () -> service.save(environment(null, null, "dev", true)));
        assertThrows(ServiceException.class, () -> service.saveBinding(binding(null, null), 99L));
        assertThrows(ServiceException.class, () -> service.listBindings(-1L));
        assertThrows(SQLException.class, () -> pg.sql("INSERT INTO dh_dataforge_etl_environment_binding(environment_id,logical_key,database_id,schema_name) VALUES(-1,'illegal',1,'public')"));
    }

    @Test void freezesPhysicalIdentityWithoutCredentialsAndKeepsDesignLogical() {
        var input = node("read-db", "\"rows\"");
        var frozen = service.freeze(environmentId, List.of(input), 99L);
        var config = JSONUtil.parseObj(frozen.nodes().getFirst().getNodeJson());
        assertEquals(1L, config.getLong("databaseId")); assertEquals(pg.targetSchema, config.getStr("schemaName"));
        assertEquals("\"" + pg.targetSchema + "\".\"rows\"", config.getStr("tableName"));
        assertEquals(777L, JSONUtil.parseObj(input.getNodeJson()).getLong("databaseId"));
        String encoded = JsonUtils.toJsonString(frozen.bindings());
        assertFalse(encoded.contains(POSTGRES.getJdbcUrl())); assertFalse(encoded.contains("password"));
        var restored = service.reuse(environmentId, List.of(input), 99L, JSONUtil.parseObj(encoded));
        assertEquals(config, JSONUtil.parseObj(restored.nodes().getFirst().getNodeJson()));
        var snapshot = snapshot(frozen); service.validateFrozen(snapshot, 99L);
        service.validateTargetBinding(config, 99L);
        verify(pg.access, atLeastOnce()).requireDatabase(1L);
    }

    @Test void updatedBindingCannotRedirectPublishedOrQueuedRun() {
        var frozen = service.freeze(environmentId, List.of(node("read-db", "\"rows\"")), 99L);
        var change = binding(bindingId, 0); change.setSchemaName("another_schema"); service.saveBinding(change, 99L);
        assertThrows(ServiceException.class, () -> service.reuse(environmentId, List.of(node("read-db", "\"rows\"")), 99L, frozen.bindings()));
        assertThrows(ServiceException.class, () -> service.validateFrozen(snapshot(frozen), 99L));
        assertThrows(ServiceException.class, () -> service.validateTargetBinding(JSONUtil.parseObj(frozen.nodes().getFirst().getNodeJson()), 99L));
        assertThrows(ServiceException.class, () -> service.saveBinding(change, 99L));
        assertEquals(1, service.listBindings(environmentId).getFirst().getRevision());
    }

    @Test void realManagedCommitUsesFrozenEnvironmentAndLaterConsumptionRejectsRebinding() throws Exception {
        pg.requireAtomicPostgres16();
        var source = node("managed-db-output", "\"rows\"");
        var input = JSONUtil.parseObj(source.getNodeJson()).set("fields", List.of("id", "_quality_status"))
                .set("businessKeys", List.of("id")); source.setNodeJson(input.toString());
        var frozen = service.freeze(environmentId, List.of(source), 99L);
        var config = pg.adapter.prepareConfig(JSONUtil.parseObj(frozen.nodes().getFirst().getNodeJson()), 99L);
        var request = pg.adapter.request(config, "a".repeat(64), List.of(Map.of("id", 1L, "_quality_status", "PASS")), false);
        var batch = new cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO();
        batch.setExecutionUserId(99L); batch.setConfigJson(config.toString());
        batch.setBatchKey(request.key().batchKey()); batch.setPayloadHash(request.payloadHash()); batch.setOutputCount(1L);
        batch.setPayloadJson(cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.AtomicBatchCodec.encode(request));
        var receipt = pg.adapter.commit(batch);
        assertEquals(1, receipt.rowCount()); assertEquals(receipt, pg.adapter.findCommitted(batch).orElseThrow());
        service.saveBinding(binding(bindingId, 0), 99L);
        assertThrows(ServiceException.class, () -> pg.adapter.findCommitted(batch));
        assertThrows(ServiceException.class, () -> pg.adapter.readAdmitted(batch, 99L, 0, 10));
    }

    @Test void changedEnvironmentOrDatabaseIdentityFailsClosed() {
        var frozen = service.freeze(environmentId, List.of(node("read-db", "\"rows\"")), 99L);
        var connection = pg.connections.resolve(1L); String old = connection.getJdbcUrl(); connection.setJdbcUrl(old + "?changed=true");
        assertThrows(ServiceException.class, () -> service.validateFrozen(snapshot(frozen), 99L));
        connection.setJdbcUrl(old);
        service.save(environment(environmentId, 0, "dev", false));
        assertThrows(ServiceException.class, () -> service.validateFrozen(snapshot(frozen), 99L));
        service.save(environment(environmentId, 1, "dev", true));
        assertThrows(ServiceException.class, () -> service.validateFrozen(snapshot(frozen), 99L));
    }

    @Test void missingPlanAndUnsupportedNodeAndOtherSchemaNeverFallBackToOldDatabaseId() {
        assertThrows(ServiceException.class, () -> service.freeze(null, List.of(node("read-db", "\"rows\"")), 99L));
        assertThrows(ServiceException.class, () -> service.freeze(environmentId, List.of(node("sql-script", "\"rows\"")), 99L));
        assertThrows(ServiceException.class, () -> service.freeze(environmentId, List.of(node("read-db", "public.\"rows\"")), 99L));
        var sqlNode = node("read-db", "\"rows\""); sqlNode.setNodeJson(JSONUtil.parseObj(sqlNode.getNodeJson()).set("ifSql", 1).set("sql", "select * from public.secret").toString());
        assertThrows(ServiceException.class, () -> service.freeze(environmentId, List.of(sqlNode), 99L));
        assertThrows(ServiceException.class, () -> service.reuse(environmentId, List.of(node("read-db", "\"rows\"")), 99L, Map.of()));
        assertThrows(ServiceException.class, () -> service.freeze(environmentId, List.of(node("read-db", "\"rows\"")), null));
        service.deleteBinding(bindingId, 0);
        assertThrows(ServiceException.class, () -> service.freeze(environmentId, List.of(node("read-db", "\"rows\"")), 99L));
        verify(pg.connections, never()).resolve(777L);
    }

    @Test void rechecksActualObjectPermissionAndRejectsPhysicalSnapshotTampering() {
        var frozen = service.freeze(environmentId, List.of(node("managed-db-output", "\"rows\"")), 99L);
        verify(pg.access, atLeastOnce()).requireDatabase(1L);
        var snapshot = snapshot(frozen);
        var first = snapshot.getJSONArray("executionNodes").getJSONObject(0).getJSONObject("properties").getJSONObject("nodeJson");
        first.set("databaseId", 777L);
        assertThrows(ServiceException.class, () -> service.validateFrozen(snapshot, 99L));
        doThrow(new IllegalStateException("denied")).when(pg.access).requireDatabase(1L);
        assertThrows(IllegalStateException.class, () -> service.freeze(environmentId, List.of(node("read-db", "\"rows\"")), 99L));
    }

    @Test void deletionProtectsReferencesAndOldRevisionCannotDelete() throws Exception {
        assertThrows(ServiceException.class, () -> service.delete(environmentId, 0));
        service.deleteBinding(bindingId, 0);
        pg.sql("UPDATE dh_dataforge_etl_pipeline SET environment_id=" + environmentId + " WHERE id=10");
        assertThrows(ServiceException.class, () -> service.delete(environmentId, 0));
        pg.sql("UPDATE dh_dataforge_etl_pipeline SET environment_id=NULL WHERE id=10");
        assertThrows(ServiceException.class, () -> service.delete(environmentId, 1));
        service.delete(environmentId, 0); assertTrue(service.list().isEmpty());
        assertThrows(ServiceException.class, () -> service.validateSelection(environmentId));
    }

    @Test void simultaneousEditorsUseDatabaseCas() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(executor.submit(() -> {
                TenantContextHolder.setTenantId(7L);
                try { start.await(); service.save(environment(environmentId, 0, "dev", true)); return true; }
                catch (ServiceException stale) { return false; }
                finally { TenantContextHolder.clear(); }
            }));
            start.countDown(); int successes = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes); assertEquals(1, service.list().getFirst().getRevision());
        }
    }

    @Test void legacyPhysicalNodesAndEmptyEnvironmentSelectionRemainCompatible() {
        var legacy = node("read-db", "\"rows\""); legacy.setNodeJson("{\"databaseId\":1,\"tableName\":\"rows\"}");
        var result = service.freeze(null, List.of(legacy), null);
        assertEquals(legacy, result.nodes().getFirst()); assertTrue(result.bindings().isEmpty());
        assertTrue(service.freeze(environmentId, List.of(), 99L).nodes().isEmpty());
        service.validateFrozen(JSONUtil.parseObj("{\"nodes\":[],\"edges\":[]}"), 99L);
    }

    @Test void publishingResolvesEnvironmentButRunningPublishedVersionNeverRebinds() {
        var pipelineService = new EtlPipelineServiceImpl();
        var policies = mock(EtlPolicyPlanService.class); var targets = mock(EtlTargetBatchService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "environmentService", service);
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "subflowService", new EtlSubflowService());
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "designService", mock(EtlDesignService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "executorRegistry", new cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry());
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "qualityGateService", mock(cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "nodeAccessService", mock(EtlNodeAccessService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "policyPlanService", policies);
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "targetBatchService", targets);
        when(policies.freeze(anyList(), anyLong(), anyMap())).thenReturn(Map.of());
        when(targets.validate(any(), anyLong(), anyMap())).thenReturn(Map.of());
        var pipeline = pg.pipelines.selectById(10L); pipeline.setEnvironmentId(environmentId);
        pipeline.setNodesJson(JsonUtils.toJsonString(EtlSubflowService.nodes(List.of(node("read-db", "\"rows\"")))));
        pipeline.setEdgesJson("[]"); pipeline.setParamsJson("[]");
        cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO prepared = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                pipelineService, "prepareRun", pipeline, 1, 99L, true);
        assertNotNull(prepared); pipeline.setPublishedConfigJson(prepared.getConfigJson());
        var before = JSONUtil.parseObj(prepared.getConfigJson());
        assertEquals(environmentId, before.getLong("environmentId"));
        assertEquals(0, before.getJSONObject("environmentBindings").getJSONObject("reader").getInt("bindingRevision"));
        service.saveBinding(binding(bindingId, 0), 99L);
        assertThrows(ServiceException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                pipelineService, "prepareRun", pipeline, 1, 99L, false));
        cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO republished = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                pipelineService, "prepareRun", pipeline, 1, 99L, true);
        assertNotNull(republished);
        assertEquals(1, JSONUtil.parseObj(republished.getConfigJson()).getJSONObject("environmentBindings").getJSONObject("reader").getInt("bindingRevision"));
        assertEquals(prepared.getConfigJson(), pipeline.getPublishedConfigJson());
        verify(targets, times(2)).validate(any(), eq(99L), anyMap());
    }

    private cn.hutool.json.JSONObject snapshot(EtlEnvironmentService.Resolved resolved) {
        return JSONUtil.parseObj(JsonUtils.toJsonString(Map.of("environmentId", environmentId, "environmentBindings", resolved.bindings(),
                "executionNodes", EtlSubflowService.nodes(resolved.nodes()))));
    }
    private EtlNodeDef node(String tool, String table) {
        var node = new EtlNodeDef(); node.setNo("reader"); node.setToolCode(tool); node.setToolType(1); node.setName("input");
        node.setNodeJson(JSONUtil.createObj().set("logicalDatabaseKey", "warehouse").set("databaseId", 777L).set("tableName", table).toString());
        return node;
    }
    private EtlEnvironmentSaveReqVO environment(Long id, Integer revision, String code, boolean enabled) {
        var req = new EtlEnvironmentSaveReqVO(); req.setId(id); req.setRevision(revision); req.setCode(code); req.setName("测试环境"); req.setEnabled(enabled); return req;
    }
    private EtlEnvironmentBindingSaveReqVO binding(Long id, Integer revision) {
        var req = new EtlEnvironmentBindingSaveReqVO(); req.setId(id); req.setRevision(revision); req.setEnvironmentId(environmentId);
        req.setLogicalKey("warehouse"); req.setDatabaseId(1L); req.setSchemaName(pg.targetSchema); return req;
    }
}
