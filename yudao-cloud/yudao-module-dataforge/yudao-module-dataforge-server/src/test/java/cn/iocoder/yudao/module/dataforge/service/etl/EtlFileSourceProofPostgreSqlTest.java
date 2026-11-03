package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.etl.config.EtlProperties;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.engine.*;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL JSONB CAS/owned transaction, real zero-row read and bounded file publication. Policy RPC is isolated. */
class EtlFileSourceProofPostgreSqlTest {
    private static PostgreSQLContainer<?> postgres;
    private static boolean external;
    @TempDir Path root;
    private EtlBatchPgFixture pg;
    private EtlFileSourceProofService service;
    private EtlRecordDO run;
    private EtlDag dag;
    private String sql;
    private EtlFileNativeSourceGuard nativeGuard;
    private String isolatedRole;
    @BeforeAll static void database() {
        String url = System.getProperty("datahub.test.postgres.url"); external = url != null && !url.isBlank();
        if (external) postgres = new ExternalPostgres(url);
        else { postgres = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer(); postgres.start(); }
    }
    @AfterAll static void stop() { if (postgres != null && !external) postgres.stop(); }
    private static final class ExternalPostgres extends PostgreSQLContainer<ExternalPostgres> {
        private final String url;
        private ExternalPostgres(String url) { super("postgres:16-alpine"); this.url = url; }
        @Override public String getJdbcUrl() { return url; }
        @Override public String getUsername() { return System.getProperty("datahub.test.postgres.user", "etl_fixture"); }
        @Override public String getPassword() { return System.getProperty("datahub.test.postgres.password", ""); }
    }
    @BeforeEach void setup() throws Exception {
        pg = new EtlBatchPgFixture(postgres); run = pg.run("file-proof", true);
        var connection = pg.connections.resolve(1L); connection.setConfigurationRevision("file-proof-revision");
        sql = "SELECT id FROM " + pg.targetSchema + ".rows WHERE id < 0";
        var source = EtlFileSourceProofTest.source("input", sql);
        source.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId", 1L, "ifSql", 1, "sql", sql)));
        dag = EtlFileSourceProofTest.graph(List.of(source, EtlFileSourceProofTest.output()));
        run.setConfigJson(EtlFileSourceProofTest.snapshot(dag)); assertEquals(1, pg.records.updateById(run));
        service = new EtlFileSourceProofService();
        EtlFileSourceProofTest.configure(service, pg.queries, pg.access, pg.connections, pg.records, pg.coordinator);
        nativeGuard = new EtlFileNativeSourceGuard(); ReflectionTestUtils.setField(nativeGuard, "runtime", pg.runtime);
        ReflectionTestUtils.setField(service, "nativeGuard", nativeGuard);
    }
    @AfterEach void close() throws Exception {
        if (pg != null) {
            try {
                // Only a role created by this test is ever revoked/dropped. No shared identity is altered.
                if (isolatedRole != null) { pg.sql("DROP OWNED BY " + isolatedRole); pg.sql("DROP ROLE " + isolatedRole); }
            } finally { pg.close(); }
        }
    }

    @Test void realZeroRowQuerySealsInOwnedTransactionBeforePublishingAndFinishPreservesTheProof() throws Exception {
        var session = service.prepare(run, dag, Map.of()); var delegate = mock(EtlFileOutputPort.class);
        when(delegate.reserve(eq("output"), eq("etl://exports"), eq("结果.csv"))).thenAnswer(call ->
                EtlFileStore.binding(root, List.of("owner-99", "run-" + run.getId()), UUID.randomUUID().toString(), "结果.csv"));
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "external publication occurs after the owned commit");
            assertEquals("SEALED", JSONUtil.parseObj(pg.records.selectById(run.getId()).getConfigJson())
                    .getJSONObject(EtlFileSourceProofService.KEY).getStr("state")); return null;
        }).when(delegate).approvePublish(anyList());
        var policy = new EtlProperties(); policy.getFileOutput().setAllowedDirectories(List.of(root.toString()));
        AtomicLong readRows = new AtomicLong(-1);
        var listener = session.wrapListener(new EtlRunListener() {
            public void onNodeSuccess(String node, long read, long write) { if ("input".equals(node)) readRows.set(read); }
        });
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry(), pg.runtime, policy.fileOutputPolicy())) {
            var result = engine.execute(dag, EtlRunOptions.builder().params(Map.of())
                    .readPolicyPort(session.wrapRead(mock(EtlReadPolicyPort.class)))
                    .connectionResolver(id -> session.checkConnection(id, pg.connections.resolve(id)))
                    .fileOutputPort(session.wrapFiles(delegate)).listener(listener)
                    .build());
            assertTrue(result.isSuccess(), result.getFirstError()); assertEquals(0, readRows.get());
            assertTrue(result.getFileOutputStates().get("output").startsWith("PUBLISHED"));
        }
        assertTrue(pg.coordinator.finish(run.getId(), run.getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS, RunProtocol.Effect.COMPLETE, true, "done"));
        var completed = pg.records.selectById(run.getId()); service.verify(completed, dag, Map.of());
        assertFalse(completed.getOccupiesSlot()); assertNull(completed.getExecutionToken());
    }

    @Test void concurrentConfigChangeFailsJsonbCasWithoutApprovingOrReplacingTheNewSnapshot() throws Exception {
        var session = completedSession();
        pg.sql("UPDATE dh_dataforge_etl_record SET config_json=config_json || '{\"concurrent\":true}'::jsonb WHERE id=" + run.getId());
        var delegate = mock(EtlFileOutputPort.class);
        assertThrows(RuntimeException.class, () -> session.wrapFiles(delegate).approvePublish(List.of())); verifyNoInteractions(delegate);
        assertEquals("true", pg.value("SELECT config_json->>'concurrent' FROM dh_dataforge_etl_record WHERE id=" + run.getId()));
        assertEquals("0", pg.value("SELECT count(*) FROM dh_dataforge_etl_record WHERE config_json ? 'fileSourcePolicyProof'"));
        assertFalse(JSONUtil.parseObj(run.getConfigJson()).containsKey(EtlFileSourceProofService.KEY));
    }

    @Test void failedSealWriteRollsBackAndLostLeaseNeverApproves() throws Exception {
        var session = completedSession();
        pg.sql("CREATE FUNCTION reject_file_proof() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.config_json ? 'fileSourcePolicyProof' THEN RAISE EXCEPTION 'fixture proof failure'; END IF; RETURN NEW; END $$");
        pg.sql("CREATE TRIGGER reject_file_proof BEFORE UPDATE ON dh_dataforge_etl_record FOR EACH ROW EXECUTE FUNCTION reject_file_proof()");
        var delegate = mock(EtlFileOutputPort.class);
        assertThrows(RuntimeException.class, () -> session.wrapFiles(delegate).approvePublish(List.of())); verifyNoInteractions(delegate);
        assertEquals("0", pg.value("SELECT count(*) FROM dh_dataforge_etl_record WHERE config_json ? 'fileSourcePolicyProof'"));
        pg.sql("DROP TRIGGER reject_file_proof ON dh_dataforge_etl_record");
        pg.sql("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second' WHERE id=" + run.getId());
        assertThrows(RuntimeException.class, () -> session.wrapFiles(delegate).approvePublish(List.of())); verifyNoInteractions(delegate);
    }

    @Test void initiallyEnabledRlsKeepsOrdinaryZeroRowEtlExecutionButCannotSealOrDownload() throws Exception {
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows ENABLE ROW LEVEL SECURITY");
        var session = service.prepare(run, dag, Map.of()); var read = mock(EtlReadPolicyPort.class);
        when(read.rewrite("input", 1L, sql)).thenReturn(sql);
        assertSame(read, session.wrapRead(read));
        var output = mock(EtlFileOutputPort.class);
        when(output.reserve(anyString(), anyString(), anyString())).thenAnswer(call ->
                EtlFileStore.binding(root, List.of("unproven-rls"), UUID.randomUUID().toString(), "结果.csv"));
        var policy = new EtlProperties(); policy.getFileOutput().setAllowedDirectories(List.of(root.toString()));
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry(), pg.runtime, policy.fileOutputPolicy())) {
            var result = engine.execute(dag, EtlRunOptions.builder().params(Map.of()).readPolicyPort(session.wrapRead(read))
                    .connectionResolver(id -> session.checkConnection(id, pg.connections.resolve(id)))
                    .fileOutputPort(session.wrapFiles(output))
                    .build());
            assertTrue(result.isSuccess(), result.getFirstError());
            assertTrue(result.getFileOutputStates().get("output").startsWith("PUBLISHED"));
        }
        assertFalse(JSONUtil.parseObj(pg.records.selectById(run.getId()).getConfigJson()).containsKey(EtlFileSourceProofService.KEY));
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
    }

    @Test void rlsEnabledAfterReadRejectsPublicationAndAfterSealRejectsDownload() throws Exception {
        var session = completedSession(); var output = mock(EtlFileOutputPort.class);
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows ENABLE ROW LEVEL SECURITY");
        assertThrows(RuntimeException.class, () -> session.wrapFiles(output).approvePublish(List.of())); verifyNoInteractions(output);
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows DISABLE ROW LEVEL SECURITY");
        session.wrapFiles(output).approvePublish(List.of()); service.verify(run, dag, Map.of());
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows ENABLE ROW LEVEL SECURITY");
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        assertThrows(ServiceException.class, () -> session.checkConnection(1L, pg.connections.resolve(1L)));
    }

    @Test void renamedThenRecreatedTableCannotReuseItsEarlierPhysicalIdentity() throws Exception {
        completedSession().wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        service.verify(run, dag, Map.of()); var before = nativeGuard.inspect(pg.connections.resolve(1L), sql, 99L);
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows RENAME TO previous_rows");
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        pg.sql("CREATE TABLE " + pg.targetSchema + ".rows(id bigint)");
        var after = nativeGuard.inspect(pg.connections.resolve(1L), sql, 99L);
        assertNotEquals(before.relationHash(), after.relationHash()); assertNotEquals(before.storageHash(), after.storageHash());
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
    }

    @Test void nativeProbeWithAnOrPredicateCannotMaterializeSourceRowsAndReleasesItsOperation() throws Exception {
        var operation = pg.runtime.queries().register("file-proof-session-test", UUID.randomUUID().toString(), UUID.randomUUID(),
                new cn.iocoder.yudao.framework.dbswitch.data.query.OperationBudget(java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(3), 16, 64 * 1024, 16 * 1024, 1));
        try {
            operation.bindWorker();
            try (var session = operation.openSession(EtlRowOriginCapture.spec(pg.connections.resolve(1L)),
                    cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.METADATA)) {
                var owned = session.connection(); assertTrue(owned.isReadOnly()); assertFalse(owned.getAutoCommit());
                assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> owned.setReadOnly(true));
                assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> owned.setAutoCommit(false));
                assertThrows(java.sql.SQLFeatureNotSupportedException.class, owned::commit);
                assertThrows(java.sql.SQLFeatureNotSupportedException.class, owned::rollback);
            }
        } finally {
            operation.workerExited();
            assertEquals(cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
        }
        pg.sql("INSERT INTO " + pg.targetSchema + ".rows(id,_quality_status,_etl_batch_key,_etl_row_no)"
                + " VALUES(1,'PASS','native-proof-fixture',1),(2,'PASS','native-proof-fixture',2)");
        String query = "SELECT id FROM " + pg.targetSchema + ".rows WHERE id=1 OR id=2";
        var identity = nativeGuard.inspect(pg.connections.resolve(1L), query, 99L);
        assertTrue(identity.relationHash().matches("[a-f0-9]{64}")); assertTrue(identity.storageHash().matches("[a-f0-9]{64}"));
        assertEquals(0, pg.runtime.queries().activeOperations(), "proof cannot return before source session shutdown is confirmed");
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows ENABLE ROW LEVEL SECURITY");
        assertThrows(EtlFileNativeSourceGuard.Unproven.class, () -> nativeGuard.inspect(pg.connections.resolve(1L), query, 99L));
        assertEquals(0, pg.runtime.queries().activeOperations(), "rejected native proof also closes its bounded operation");
    }

    @Test void actualReaderDetectsTableReplacementAfterTheLastMetadataAdmission() throws Exception {
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        var info = session.checkConnection(1L, pg.connections.resolve(1L)); read.rewrite("input", 1L, sql);
        pg.sql("ALTER TABLE " + pg.targetSchema + ".rows RENAME TO prior_rows"); pg.sql("CREATE TABLE " + pg.targetSchema + ".rows(id bigint)");
        try (var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), info.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                assertThrows(ServiceException.class, () -> read.validateReadConnection("input", 1L, sql, connection, EtlReadPolicyPort.ReadConnectionStage.OPENED));
                assertFalse(connection.isClosed()); assertFalse(connection.getAutoCommit());
            } finally { connection.rollback(); }
        }
        var output = mock(EtlFileOutputPort.class);
        assertThrows(ServiceException.class, () -> session.wrapFiles(output).approvePublish(List.of())); verifyNoInteractions(output);
    }

    @Test void nativeSelectGrantRevocationForAnIndependentNonSuperuserRefusesTheHistoricalFile() throws Exception {
        isolatedRole = "etl_file_role_" + UUID.randomUUID().toString().replace("-", "");
        String testPassword = UUID.randomUUID().toString().replace("-", "");
        pg.sql("CREATE ROLE " + isolatedRole + " LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT PASSWORD '" + testPassword + "'");
        pg.sql("GRANT USAGE ON SCHEMA " + pg.targetSchema + " TO " + isolatedRole);
        pg.sql("GRANT SELECT ON " + pg.targetSchema + ".rows TO " + isolatedRole);
        var connection = pg.connections.resolve(1L); connection.setUsername(isolatedRole); connection.setPassword(testPassword);
        try (var actor = DriverManager.getConnection(postgres.getJdbcUrl(), isolatedRole, testPassword);
             var statement = actor.createStatement(); var row = statement.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(row.next()); assertFalse(row.getBoolean(1)); assertFalse(row.getBoolean(2));
        }
        var session = completedSession(); session.wrapFiles(mock(EtlFileOutputPort.class)).approvePublish(List.of());
        service.verify(run, dag, Map.of());
        pg.sql("REVOKE SELECT ON " + pg.targetSchema + ".rows FROM " + isolatedRole);
        assertThrows(ServiceException.class, () -> service.verify(run, dag, Map.of()));
        assertThrows(ServiceException.class, () -> session.checkConnection(1L, connection));
        assertThrows(EtlFileNativeSourceGuard.Unproven.class, () -> nativeGuard.inspect(connection, sql, 99L));
    }

    private EtlFileSourceProofService.Session completedSession() throws Exception {
        var session = service.prepare(run, dag, Map.of()); var read = session.wrapRead(mock(EtlReadPolicyPort.class));
        read.rewrite("input", 1L, sql); var info = pg.connections.resolve(1L);
        try (var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), info.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                read.validateReadConnection("input", 1L, sql, connection, EtlReadPolicyPort.ReadConnectionStage.OPENED);
                assertFalse(rows.next());
                read.validateReadConnection("input", 1L, sql, connection, EtlReadPolicyPort.ReadConnectionStage.DRAINED);
            } finally { connection.rollback(); }
        }
        session.wrapListener(new EtlRunListener() { }).onNodeSuccess("input", 0, 0); return session;
    }
}
