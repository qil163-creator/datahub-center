package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlFileSourceProofTest {
    static final String SQL = "SELECT id FROM public.source";
    final EtlFileSourceProofService service = new EtlFileSourceProofService();
    final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    final EtlRecordMapper records = mock(EtlRecordMapper.class);
    final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
    final EtlFileNativeSourceGuard nativeGuard = mock(EtlFileNativeSourceGuard.class);
    EtlRecordDO run;
    EtlDag dag;
    @BeforeEach void setup() {
        initializeRecordTable();
        TenantContextHolder.setTenantId(7L);
        configure(service, queries, access, connections, records, coordinator);
        configureNative(service, nativeGuard);
        when(connections.resolve(42L)).thenReturn(connection());
        when(queries.normalizeSelect(eq(42L), anyString())).thenAnswer(call -> call.getArgument(1));
        when(coordinator.writeOwned(anyLong(), anyString(), any())).thenAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return true; });
        when(records.update(isNull(), any())).thenReturn(1);
        dag = graph(List.of(source("input", SQL), output()));
        run = EtlRecordDO.builder().id(9_007_199_254_740_993L).pipelineId(55L).executionUserId(99L)
                .executionToken("owned-proof-token").configJson(snapshot(dag)).build();
        }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test void completedEmptyReadSealsOnlyHashesAndUnsafeIdsAndRemainsDownloadableForItsBoundActor() {
        var session = service.prepare(run, dag, Map.of("limit", "2"));
        var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        assertEquals(SQL, read.rewrite("input", 42L, SQL));
        observeActualRead(read, "input", 42L, SQL);
        session.wrapListener(new EtlRunListener() { }).onNodeSuccess("input", 0, 0);
        var output = mock(EtlFileOutputPort.class);
        doAnswer(call -> { assertTrue(run.getConfigJson().contains("SEALED")); return null; }).when(output).approvePublish(anyList());
        session.wrapFiles(output).approvePublish(List.of());
        verify(output).approvePublish(List.of()); verify(records).update(isNull(), any());
        var proof = JSONUtil.parseObj(run.getConfigJson()).getJSONObject(EtlFileSourceProofService.KEY);
        assertEquals(run.getId().toString(), proof.getStr("run")); assertEquals("SEALED", proof.getStr("state"));
        assertEquals(2, proof.getInt("version"));
        assertEquals("c".repeat(64), proof.getJSONArray("sources").getJSONObject(0).getStr("relationHash"));
        assertEquals("d".repeat(64), proof.getJSONArray("sources").getJSONObject(0).getStr("storageHash"));
        assertFalse(proof.toString().contains("private-connection-sentinel"));
        assertFalse(proof.toString().contains("private-password-sentinel")); assertFalse(proof.toString().contains(SQL));
        service.verify(run, dag, Map.of("limit", "2"));
        run.setExecutionUserId(100L); assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of("limit", "2")));
    }

    @Test void provenListenerWrapperStillForwardsRuntimeObservationSamples() {
        var session = service.prepare(run, dag, Map.of());
        var received = new java.util.ArrayList<cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample>();
        var delegate = new EtlRunListener() {
            @Override public void onRuntimeSample(cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample sample) { received.add(sample); }
        };
        var wrapped = session.wrapListener(delegate);
        assertNotSame(delegate, wrapped);
        var sample = new cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample(1000, true, 0, 0, 0, List.of(), List.of(), List.of());
        wrapped.onRuntimeSample(sample);
        assertEquals(List.of(sample), received);
    }

    @Test void rewriteWithoutCompletedReadAndFailGoOnFailureCannotCreatePublicationProof() {
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class)); read.rewrite("input", 42L, SQL);
        var output = mock(EtlFileOutputPort.class); var files = session.wrapFiles(output);
        assertThrows(ServiceException.class, () -> files.approvePublish(List.of()));
        var listener = session.wrapListener(new EtlRunListener() { });
        assertThrows(ServiceException.class, () -> listener.onNodeSuccess("input", 0, 0));
        observeActualRead(read, "input", 42L, SQL);
        listener.onNodeSuccess("input", 1, 1); listener.onNodeFail("input", "tolerated-failure");
        assertThrows(ServiceException.class, () -> files.approvePublish(List.of()));
        verifyNoInteractions(output, records);
    }

    @Test void normalizedQueryChangedBetweenLastBatchAndSealNeverApprovesTheFile() {
        var session = completed();
        when(queries.normalizeSelect(42L, SQL)).thenReturn(SQL + " WHERE id = 2");
        var output = mock(EtlFileOutputPort.class);
        assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of()));
        verifyNoInteractions(output, records);
    }

    @Test void actualConnectionUsedByTheEngineAndEveryBatchMustMatchTheFrozenRevision() {
        var session = service.prepare(run, dag, Map.of()); var changed = connection(); changed.setConfigurationRevision("changed");
        assertThrows(ServiceException.class, () -> session.checkConnection(42L, changed));
        var read = session.wrapRead(mock(EtlReadPolicyPort.class)); read.rewrite("input", 42L, SQL);
        when(connections.resolve(42L)).thenReturn(changed);
        assertThrows(ServiceException.class, () -> read.filter("input", 42L, SQL, List.of(Map.of("id", 1L)), SQL));
    }

    @Test void compareAndSetOrLostOwnedTransactionFailsBeforeTheOriginalApproveAndLeavesNoInMemorySeal() {
        var session = completed(); String original = run.getConfigJson(); var output = mock(EtlFileOutputPort.class);
        when(records.update(isNull(), any())).thenReturn(0);
        assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of()));
        assertEquals(original, run.getConfigJson()); verifyNoInteractions(output);
        doReturn(false).when(coordinator).writeOwned(anyLong(), anyString(), any(Runnable.class));
        assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of()));
    }

    @Test void failedPublicationCannotTurnASealedProofIntoAnUnconditionalDownloadGrant() {
        var session = completed(); var output = mock(EtlFileOutputPort.class);
        doThrow(new IllegalStateException("approval-failed")).when(output).approvePublish(anyList());
        assertThrows(IllegalStateException.class, () -> session.wrapFiles(output).approvePublish(List.of()));
        assertTrue(run.getConfigJson().contains("SEALED")); // download service separately requires successful closed run/receipt
        assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of()));
    }

    @Test void twoNodesAgainstTheSameDatabaseCannotReuseOneQueriesProofOrSkipOneRead() {
        String other = "SELECT id FROM public.source WHERE id = 2";
        dag = graph(List.of(source("input", SQL), source("other", other), output())); run.setConfigJson(snapshot(dag));
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        assertThrows(ServiceException.class, () -> read.rewrite("other", 42L, SQL));
        read.rewrite("input", 42L, SQL); observeActualRead(read, "input", 42L, SQL);
        var listener = session.wrapListener(new EtlRunListener() { }); listener.onNodeSuccess("input", 0, 0);
        var output = mock(EtlFileOutputPort.class); assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of()));
        read.rewrite("other", 42L, other); observeActualRead(read, "other", 42L, other); listener.onNodeSuccess("other", 0, 0);
        session.wrapFiles(output).approvePublish(List.of());
        assertEquals(2, JSONUtil.parseObj(run.getConfigJson()).getJSONObject(EtlFileSourceProofService.KEY).getJSONArray("sources").size());
        service.verify(run, dag, Map.of());
    }

    @Test void missingProofParameterGraphConnectionOrInvalidQueryAreDenied() {
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        completed().wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        service.verify(run, dag, Map.of());
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of("new", "private-param-sentinel")));
        var modified = graph(List.of(source("input", SQL + " WHERE id = 2"), output()));
        assertThrows(ServiceException.class, () -> service.verify(run, modified, Map.of()));
        when(queries.normalizeSelect(42L, SQL)).thenThrow(new IllegalStateException("private-query-sentinel"));
        var denied = assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        assertNull(denied.getCause()); assertFalse(denied.getMessage().contains("private-"));
    }

    @Test void scriptsExternalAndFileMixedSourcesAndGovernancePlansRemainUnproven() {
        for (String code : List.of("java-script", "js-script", "sql-script", "rest-api", "read-csv", "read-json", "read-kafka", "subflow", "quality-check")) {
            var extra = source("unproven", SQL); extra.setToolCode(code);
            var mixed = graph(List.of(source("input", SQL), extra, output())); run.setConfigJson(snapshot(mixed));
            var session = service.prepare(run, mixed, Map.of()); var delegate = mock(EtlReadPolicyPort.class);
            assertSame(delegate, session.wrapRead(delegate));
            assertThrows(ServiceException.class, () -> service.verify(run, mixed, Map.of()));
        }
        var withPlans = JSONUtil.parseObj(snapshot(dag)); withPlans.set("governancePlans", Map.of("input", Map.of("unknown", "plan")));
        run.setConfigJson(withPlans.toString()); var session = service.prepare(run, dag, Map.of());
        var delegate = mock(EtlReadPolicyPort.class); assertSame(delegate, session.wrapRead(delegate));
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        verifyNoInteractions(queries, records);
    }

    @Test void oldReplayProofIsRemovedUnderTheOwnedFenceAndRebuiltForTheNewRun() {
        completed().wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        run.setId(run.getId() + 1); var session = service.prepare(run, dag, Map.of());
        assertFalse(JSONUtil.parseObj(run.getConfigJson()).containsKey(EtlFileSourceProofService.KEY));
        var read = session.wrapRead(mock(EtlReadPolicyPort.class)); read.rewrite("input", 42L, SQL); observeActualRead(read, "input", 42L, SQL);
        session.wrapListener(new EtlRunListener() { }).onNodeSuccess("input", 0, 0);
        session.wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        service.verify(run, dag, Map.of()); verify(records, times(3)).update(isNull(), any());
    }

    @Test void nativeProofUnavailableDuringPreparePreservesOrdinaryDelegatesAndCreatesNoDownloadGrant() {
        when(nativeGuard.inspect(any(), anyString(), anyLong())).thenThrow(new EtlFileNativeSourceGuard.Unproven());
        var session = service.prepare(run, dag, Map.of());
        var read = mock(EtlReadPolicyPort.class); var output = mock(EtlFileOutputPort.class); var listener = new EtlRunListener() { };
        assertSame(read, session.wrapRead(read)); assertSame(output, session.wrapFiles(output));
        assertSame(listener, session.wrapListener(listener));
        assertSame(connections.resolve(42L), session.checkConnection(42L, connections.resolve(42L)));
        session.wrapFiles(output).approvePublish(List.of()); verify(output).approvePublish(List.of());
        verifyNoInteractions(records); assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
    }

    @Test void onceNativeIdentityIsFrozenActualConnectionBatchSealAndBothDownloadChecksRejectItsChange() {
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        read.rewrite("input", 42L, SQL); observeActualRead(read, "input", 42L, SQL);
        session.wrapListener(new EtlRunListener() { }).onNodeSuccess("input", 0, 0);
        when(nativeGuard.inspect(any(), anyString(), anyLong())).thenReturn(new EtlFileNativeSourceGuard.Identity("c".repeat(64), "e".repeat(64)));
        assertThrows(ServiceException.class, () -> session.checkConnection(42L, connections.resolve(42L)));
        assertThrows(ServiceException.class, () -> read.filter("input", 42L, SQL, List.of(), SQL));
        var output = mock(EtlFileOutputPort.class);
        assertThrows(RuntimeException.class, () -> session.wrapFiles(output).approvePublish(List.of())); verifyNoInteractions(output, records);
        configureNative(service, nativeGuard); completed().wrapFiles(output).approvePublish(List.of());
        service.verify(run, dag, Map.of());
        when(nativeGuard.inspect(any(), anyString(), anyLong())).thenThrow(new EtlFileNativeSourceGuard.Unproven());
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
    }

    @Test void olderPlatformOnlyProofWithoutNativeIdentityIsNotDownloadable() {
        completed().wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        var snapshot = JSONUtil.parseObj(run.getConfigJson()); var proof = snapshot.getJSONObject(EtlFileSourceProofService.KEY);
        proof.set("version", 1); run.setConfigJson(snapshot.toString());
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        proof.set("version", 2); proof.getJSONArray("sources").getJSONObject(0).remove("storageHash"); run.setConfigJson(snapshot.toString());
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
    }

    @Test void actualReaderValidationDeniesMismatchedBindingOutOfOrderStageAndNativeChanges() throws Exception {
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        read.rewrite("input", 42L, SQL); var actual = mock(java.sql.Connection.class);
        assertThrows(ServiceException.class, () -> read.validateReadConnection("other", 42L, SQL, actual, EtlReadPolicyPort.ReadConnectionStage.OPENED));
        assertThrows(ServiceException.class, () -> read.validateReadConnection("input", 43L, SQL, actual, EtlReadPolicyPort.ReadConnectionStage.OPENED));
        assertThrows(ServiceException.class, () -> read.validateReadConnection("input", 42L, SQL + " WHERE id=2", actual, EtlReadPolicyPort.ReadConnectionStage.OPENED));
        assertThrows(ServiceException.class, () -> read.validateReadConnection("input", 42L, SQL, actual, EtlReadPolicyPort.ReadConnectionStage.DRAINED));
        doThrow(new EtlFileNativeSourceGuard.Unproven()).when(nativeGuard).validateActual(any(), anyString(), anyString(), any());
        assertThrows(ServiceException.class, () -> read.validateReadConnection("input", 42L, SQL, actual, EtlReadPolicyPort.ReadConnectionStage.OPENED));
        var output = mock(EtlFileOutputPort.class);
        assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of())); verifyNoInteractions(output, records);
    }

    @Test void historicalPolicyHashIsIgnoredButQueryAndNativeProofRemainRequired() {
        completed().wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        var snapshot = JSONUtil.parseObj(run.getConfigJson());
        var source = snapshot.getJSONObject(EtlFileSourceProofService.KEY).getJSONArray("sources").getJSONObject(0);
        assertFalse(source.containsKey("policyHash"));
        source.set("policyHash", "historical-removed-governance-field");
        run.setConfigJson(snapshot.toString());
        service.verify(run, dag, Map.of());
        source.set("authorizedHash", "f".repeat(64));
        run.setConfigJson(snapshot.toString());
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
    }

    private EtlFileSourceProofService.Session completed() {
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        read.rewrite("input", 42L, SQL); read.filter("input", 42L, SQL, List.of(Map.of("id", 1L)), SQL);
        observeActualRead(read, "input", 42L, SQL);
        session.wrapListener(new EtlRunListener() { }).onNodeSuccess("input", 1, 1); return session;
    }
    static void configure(EtlFileSourceProofService service, DatabaseQuerySupport queries, ExecutionAccessService access,
            EtlConnectionResolverAdapter connections, EtlRecordMapper records, EtlRunCoordinator coordinator) {
        ReflectionTestUtils.setField(service, "queries", queries); ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "connections", connections); ReflectionTestUtils.setField(service, "records", records);
        ReflectionTestUtils.setField(service, "coordinator", coordinator);
        configureNative(service, mock(EtlFileNativeSourceGuard.class));
    }
    static void initializeRecordTable() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "etl-file-proof-unit");
        assistant.setCurrentNamespace(EtlRecordMapper.class.getName());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, EtlRecordDO.class);
    }
    static void configureNative(EtlFileSourceProofService service, EtlFileNativeSourceGuard nativeGuard) {
        doReturn(new EtlFileNativeSourceGuard.Identity("c".repeat(64), "d".repeat(64))).when(nativeGuard).inspect(any(), anyString(), anyLong());
        ReflectionTestUtils.setField(service, "nativeGuard", nativeGuard);
    }
    static void observeActualRead(EtlReadPolicyPort read, String node, Long database, String sql) {
        var connection = mock(java.sql.Connection.class);
        try {
            read.validateReadConnection(node, database, sql, connection, EtlReadPolicyPort.ReadConnectionStage.OPENED);
            read.validateReadConnection(node, database, sql, connection, EtlReadPolicyPort.ReadConnectionStage.DRAINED);
        } catch (java.sql.SQLException unexpected) { throw new AssertionError(unexpected); }
    }
    static EtlConnectionInfo connection() {
        var value = new EtlConnectionInfo(); value.setDatabaseId(42L); value.setProductType(ProductTypeEnum.POSTGRESQL);
        value.setConfigurationRevision("revision-1"); value.setSchema("public"); value.setUsername("fixture");
        value.setJdbcUrl("jdbc:postgresql://private-connection-sentinel.invalid/test"); value.setPassword("private-password-sentinel"); return value;
    }
    static EtlNodeDef source(String id, String sql) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode("read-db"); node.setToolType(1);
        node.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId", 42L, "ifSql", 1, "sql", sql))); return node;
    }
    static EtlNodeDef output() {
        var node = new EtlNodeDef(); node.setNo("output"); node.setName("output"); node.setToolCode("write-csv"); node.setToolType(3);
        node.setNodeJson(JSONUtil.toJsonStr(Map.of("filePath", "etl://exports", "fileName", "结果.csv"))); return node;
    }
    static EtlDag graph(List<EtlNodeDef> nodes) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(nodes.getFirst().getNo()); edge.setTargetNo("output");
        return EtlDag.buildLenient(nodes, List.of(edge));
    }
    static String snapshot(EtlDag dag) {
        var edges = dag.getEdges().stream().map(edge -> {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("sourceNodeId", edge.getSourceNo()); value.put("targetNodeId", edge.getTargetNo());
            value.put("sourcePort", edge.resolveOutputPort()); value.put("targetPort", edge.getInputPort());
            value.put("properties", Map.of("edgeType", edge.isControl() ? "CONTROL" : "DATA", "outputPort", edge.resolveOutputPort())); return value;
        }).toList();
        return JSONUtil.toJsonStr(Map.of("executionNodes", EtlSubflowService.nodes(dag.getNodes()), "executionEdges", edges,
                "parameters", List.of(), "parameterValues", Map.of()));
    }
}
