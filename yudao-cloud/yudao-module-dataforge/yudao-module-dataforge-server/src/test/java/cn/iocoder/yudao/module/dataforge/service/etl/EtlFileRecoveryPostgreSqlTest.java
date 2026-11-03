package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.config.EtlProperties;
import cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.*;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedExcelWorkbook;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlFileRetryReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlFileOutputMapper;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL CAS/rollback/tenant/lease + real filesystem identities; no file publication or platform transaction is mocked. */
class EtlFileRecoveryPostgreSqlTest {
    private static PostgreSQLContainer<?> postgres;
    private static boolean external;
    @TempDir Path root;
    private EtlBatchPgFixture pg;
    private EtlFileRecoveryService service;
    private EtlFileOutputMapper mapper;
    private EtlFileStorageProperties storage;
    private EtlProperties policy;
    private EtlRecordDO run;

    @BeforeAll static void database() {
        String url = System.getProperty("datahub.test.postgres.url"); external = url != null && !url.isBlank();
        if (external) postgres = new ExternalPostgres(url);
        else { postgres = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer(); postgres.start(); }
    }
    @AfterAll static void stopDatabase() { if (postgres != null && !external) postgres.stop(); }

    private static final class ExternalPostgres extends PostgreSQLContainer<ExternalPostgres> {
        private final String url;
        private ExternalPostgres(String url) { super("postgres:16-alpine"); this.url = url; }
        @Override public String getJdbcUrl() { return url; }
        @Override public String getUsername() { return System.getProperty("datahub.test.postgres.user", "etl_fixture"); }
        @Override public String getPassword() { return System.getProperty("datahub.test.postgres.password", ""); }
    }

    @BeforeEach void setup() throws Exception {
        pg = new EtlBatchPgFixture(postgres); pg.migrate("0115_dataforge_etl-file-recovery.sql");
        pg.migrate("0116_dataforge_etl-file-temporary-resources.sql");
        pg.migrate("0117_dataforge_etl-file-retirement.sql");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("file-recovery-real-pg", new SpringManagedTransactionFactory(), pg.platform));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(pg.platform);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        var interceptors = new MybatisPlusInterceptor();

        config.addInterceptor(interceptors); config.addMapper(EtlFileOutputMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(EtlFileOutputMapper.class);
        service = new EtlFileRecoveryService(); storage = new EtlFileStorageProperties(); storage.setStorageRoots(Map.of("exports", root.toString()));
        policy = new EtlProperties(); policy.getFileOutput().setAllowedDirectories(List.of(root.toString()));
        ReflectionTestUtils.setField(service, "files", mapper); ReflectionTestUtils.setField(service, "records", pg.records);
        ReflectionTestUtils.setField(service, "coordinator", pg.coordinator); ReflectionTestUtils.setField(service, "access", pg.access);
        ReflectionTestUtils.setField(service, "etlProperties", policy); ReflectionTestUtils.setField(service, "storageProperties", storage);
        ReflectionTestUtils.setField(service, "transactionManager", pg.app.getBean(PlatformTransactionManager.class));
        login(99L); run = pg.run("file-test", true);
    }
    @AfterEach void close() throws Exception { SecurityContextHolder.clearContext(); if (pg != null) pg.close(); }

    @Test void normalPublishIsOwnerRunIsolatedAndAuditedWithoutPhysicalPathResponse() throws Exception {
        var port = port(); var output = new EtlFileOutputs(policy.fileOutputPolicy(), port);
        Path part = output.stage("n", "etl://exports", "out.csv");
        assertTrue(part.toString().contains("owner-99"));
        assertTrue(part.toString().contains("run-" + run.getId())); assertEquals("STAGED", row().getState());
        Files.writeString(part, "id\n1\n"); output.ready("n"); output.publish(); output.discard(true);
        assertEquals("PUBLISHED", row().getState()); assertEquals("DONE", row().getRecoveryStatus());
        assertEquals("5", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit"));
        var response = service.list(run.getId()); assertEquals(1, response.size());
        assertFalse(JSONUtil.toJsonStr(response).contains(root.toString())); assertFalse(JSONUtil.toJsonStr(response).contains("temporaryFileKey"));
        assertEquals("etl://exports", service.storages().getFirst().directory());
    }

    @Test void historicalPhysicalPathAndOtherOwnerCannotCreateReadOrRetry() throws Exception {
        assertThrows(RuntimeException.class, () -> service.createPort(run, dag(root.toString(), "n"), Map.of()));
        assertThrows(RuntimeException.class, () -> service.createPort(run, dag("etl://unknown", "n"), Map.of()));
        var port = port(); port.reserve("n", "etl://exports", "out.csv"); var row = row();
        login(100L); assertThrows(RuntimeException.class, () -> service.list(run.getId()));
        assertThrows(RuntimeException.class, () -> service.retry(retry(row)));
        login(99L); assertEquals(1, service.list(run.getId()).size());
        assertEquals(0, row().getAttempts());
    }

    @Test void failedDurableIntentOrAuditCannotCreateTemporaryAndRollsBackLedger() throws Exception {
        pg.sql("CREATE FUNCTION deny_file_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
        pg.sql("CREATE TRIGGER deny_file_audit BEFORE INSERT ON dh_dataforge_etl_file_audit FOR EACH ROW EXECUTE FUNCTION deny_file_audit()");
        var output = new EtlFileOutputs(policy.fileOutputPolicy(), port());
        assertThrows(RuntimeException.class, () -> output.stage("n", "etl://exports", "out.csv")); output.discard(true);
        assertEquals("0", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_output"));
        try (var paths = Files.walk(root)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().endsWith(".part"))); }
    }

    @Test void lostPublishReceiptRestoresSameInodeAndRepeatedRecoveryDoesNotRewrite() throws Exception {
        var staged = staged(port(), "n"); staged.port.approvePublish(List.of(staged.prepared)); EtlFileStore.publish(staged.prepared);
        var fingerprint = EtlFileStore.fingerprint(staged.prepared.binding(), staged.prepared.binding().target()); assertEquals("PUBLISHING", row().getState());
        expire(); service.recoverPending(); assertEquals("PUBLISHED", row().getState()); assertEquals(1, row().getAttempts());
        long revision = row().getRevision(); service.recoverPending(); service.recover(row().getId());
        assertEquals(revision, row().getRevision()); assertEquals(fingerprint, EtlFileStore.fingerprint(staged.prepared.binding(), staged.prepared.binding().target()));
    }

    @Test void unapprovedGraphDoesNotPublishAndDropsBothPayloadLinks() throws Exception {
        var staged = staged(port(), "n"); expire(); service.recoverPending();
        assertEquals("DISCARDED", row().getState()); assertFalse(Files.exists(staged.prepared.binding().target()));
        assertFalse(Files.exists(staged.prepared.binding().temporary()));
        assertFalse(Files.exists(staged.prepared.binding().anchor()));
    }

    @Test void graphWideApprovalIsAtomicAndPartialPublicationRecoversRemainingKnownFiles() throws Exception {
        var port = service.createPort(run, dag("etl://exports", "n", "m"), Map.of());
        var first = staged(port, "n"); var second = staged(port, "m");
        assertThrows(RuntimeException.class, () -> port.approvePublish(List.of(first.prepared)));
        assertEquals("0", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_output WHERE publication_approved"));
        port.approvePublish(List.of(first.prepared, second.prepared)); EtlFileStore.publish(first.prepared);
        expire(); service.recoverPending();
        assertEquals("2", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_output WHERE state='PUBLISHED'"));
        assertTrue(Files.exists(first.prepared.binding().target())); assertTrue(Files.exists(second.prepared.binding().target()));
    }

    @Test void neitherClockAgeNorExpiredLeaseCanRemoveAnActiveWriter() throws Exception {
        var staged = staged(port(), "n"); var row = row(); service.recoverPending(); service.recover(row.getId());
        assertEquals(0, row().getAttempts()); assertTrue(Files.exists(staged.prepared.binding().temporary()));
        expire();
        try (var lock = EtlFileStore.acquire(staged.prepared.binding())) {
            service.recoverPending(); assertEquals("RETRY", row().getRecoveryStatus()); assertTrue(Files.exists(staged.prepared.binding().temporary()));
        }
        pg.sql("UPDATE dh_dataforge_etl_file_output SET next_attempt_at=NULL"); service.recoverPending();
        assertEquals("DISCARDED", row().getState()); assertEquals(2, row().getAttempts());
    }

    @Test void missingCreatedReceiptAndChangedTargetRequireManualWithoutBlindDelete() throws Exception {
        var port = port(); var binding = port.reserve("n", "etl://exports", "out.csv");
        EtlFileStore.create(binding); Files.writeString(binding.temporary(), "written before receipt"); expire(); service.recoverPending();
        assertEquals("MANUAL", row().getRecoveryStatus()); assertEquals("IDENTITY_UNPROVEN", row().getLastErrorCode());
        assertEquals("written before receipt", Files.readString(binding.temporary())); assertFalse(Files.exists(binding.target()));
    }

    @Test void changedTargetEvenWithIdenticalBytesCannotBecomePublishedReceipt() throws Exception {
        var staged = staged(port(), "n"); staged.port.approvePublish(List.of(staged.prepared));
        Files.copy(staged.prepared.binding().temporary(), staged.prepared.binding().target()); expire(); service.recoverPending();
        assertEquals("MANUAL", row().getState()); assertEquals("IDENTITY_UNPROVEN", row().getLastErrorCode());
        assertTrue(Files.exists(staged.prepared.binding().temporary())); assertTrue(Files.exists(staged.prepared.binding().target()));
    }

    @Test void stopOrLeaseLossRejectsApprovalAndDoesNotPublish() throws Exception {
        var staged = staged(port(), "n"); pg.sql("UPDATE dh_dataforge_etl_record SET stop_requested=true WHERE id=" + run.getId());
        assertThrows(RuntimeException.class, () -> staged.port.approvePublish(List.of(staged.prepared)));
        assertFalse(row().getPublicationApproved()); assertFalse(Files.exists(staged.prepared.binding().target()));
        expire(); assertThrows(RuntimeException.class, () -> staged.port.published(staged.prepared));
        service.recoverPending(); assertEquals("DISCARDED", row().getState());
    }

    @Test void sameOperationDifferentPayloadOrNamespaceCannotReplaceFrozenReceipt() throws Exception {
        var port = port(); var first = port.reserve("n", "etl://exports", "out.csv");
        assertEquals(first, port.reserve("n", "etl://exports", "out.csv"));
        assertThrows(RuntimeException.class, () -> port.reserve("n", "etl://exports", "different.csv"));
        String key = EtlFileStore.create(first); port.created(first, key); Files.writeString(first.temporary(), "original");
        var prepared = new EtlFileStore.Prepared(first, EtlFileStore.seal(first)); port.ready(prepared); port.ready(prepared);
        assertThrows(RuntimeException.class, () -> port.ready(new EtlFileStore.Prepared(first, new EtlFileStore.Fingerprint(key, prepared.fingerprint().bytes(), "f".repeat(64)))));
        assertEquals(prepared.fingerprint().sha256(), row().getPayloadHash());
    }

    @Test void boundedRetryRequiresOwnerRevisionAndAuditsActualManualChange() throws Exception {
        var staged = staged(port(), "n"); expire(); pg.sql("UPDATE dh_dataforge_etl_file_output SET attempts=9");
        try (var lock = EtlFileStore.acquire(staged.prepared.binding())) { service.recoverPending(); }
        var before = row(); assertEquals(10, before.getAttempts()); assertEquals("MANUAL", before.getRecoveryStatus());
        service.recover(before.getId()); assertEquals(before.getRevision(), row().getRevision());
        var request = retry(before); service.retry(request); assertEquals(0, row().getAttempts()); assertEquals("PENDING", row().getRecoveryStatus());
        assertThrows(RuntimeException.class, () -> service.retry(request));
        assertEquals("1", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='MANUAL_RETRY' AND actor_id=99 AND reason='核查后重试'"));
        service.recoverPending(); assertEquals("DISCARDED", row().getState());
    }

    @Test void orphanLedgerAfterDeletedRunIsStillRecoverableAndOwnerQueryable() throws Exception {
        var staged = staged(port(), "n"); expire(); pg.sql("DELETE FROM dh_dataforge_etl_record WHERE id=" + run.getId());
        service.recoverPending(); assertEquals("DISCARDED", row().getState()); assertEquals(1, service.list(run.getId()).size());
        assertFalse(Files.exists(staged.prepared.binding().temporary()));
    }

    @Test void rootRebindingOrRevokedAuthorityCannotRedirectRecoveryOrApprove() throws Exception {
        var staged = staged(port(), "n"); Path another = Files.createDirectory(root.resolve("another")); storage.setStorageRoots(Map.of("exports", another.toString()));
        assertThrows(RuntimeException.class, () -> staged.port.approvePublish(List.of(staged.prepared))); expire(); service.recoverPending();
        assertEquals("STORAGE_CHANGED", row().getLastErrorCode()); assertTrue(Files.exists(staged.prepared.binding().temporary()));
        storage.setStorageRoots(Map.of("exports", root.toString())); service.retry(retry(row()));
        doThrow(new IllegalStateException("revoked")).when(pg.access).requireUserPermission(eq(99L), anyString());
        service.recoverPending(); assertEquals("AUTHORITY_OR_BINDING_CHANGED", row().getLastErrorCode());
        assertTrue(Files.exists(staged.prepared.binding().temporary()));
    }

    @Test void migrationReplaysAndDatabaseRejectsNullPayloadAndAuditMutation() throws Exception {
        port().reserve("n", "etl://exports", "out.csv"); cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(pg.control, pg.schema);
        assertEquals("1", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_output"));
        assertThrows(SQLException.class, () -> pg.sql("UPDATE dh_dataforge_etl_file_output SET payload_bytes=1,payload_hash=NULL"));
        assertThrows(SQLException.class, () -> pg.sql("UPDATE dh_dataforge_etl_file_audit SET actor_id=1"));
        assertThrows(SQLException.class, () -> pg.sql("DELETE FROM dh_dataforge_etl_file_audit"));
    }

    @Test void lostGraphDecisionAcknowledgementDoesNotDeleteDurablyApprovedBytes() throws Exception {
        var port = spy(port()); doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("lost decision acknowledgement"); })
                .when(port).approvePublish(anyList());
        var outputs = new EtlFileOutputs(policy.fileOutputPolicy(), port);
        var part = outputs.stage("n", "etl://exports", "out.csv"); Files.writeString(part, "payload"); outputs.ready("n");
        assertThrows(IllegalStateException.class, outputs::publish); outputs.discard(true);
        assertTrue(row().getPublicationApproved()); assertTrue(Files.exists(part)); assertEquals("UNKNOWN", row().getState());
        expire(); service.recoverPending(); assertEquals("PUBLISHED", row().getState()); assertFalse(Files.exists(part));
    }

    @Test void reserveToCreateCrashGapIsFencedAfterRecovery() throws Exception {
        var port = port(); var binding = port.reserve("n", "etl://exports", "out.csv"); expire(); service.recoverPending();
        assertEquals("DISCARDED", row().getState());
        try (var lock = EtlFileStore.acquire(binding)) { assertThrows(RuntimeException.class, () -> port.beforeCreate(binding)); }
        assertFalse(Files.exists(binding.temporary())); assertFalse(Files.exists(binding.anchor()));
    }

    @Test void realExcelPublishesOnlyAfterBothAuxiliaryReceiptsAreCleanedAndAudited() throws Exception {
        var outputs = new EtlFileOutputs(policy.fileOutputPolicy(), port());
        var main = outputs.stage("n", "etl://exports", "out.csv");
        try {
            try (var workbook = new EtlManagedExcelWorkbook(outputs, "n")) {
                var sheet = workbook.createSheet("records");
                for (int i = 0; i < 800; i++) sheet.createRow(i).createCell(0).setCellValue("中文-" + i);
                try (var stream = outputs.open("n")) { workbook.write(stream); }
                assertEquals(2, manifest().size()); assertTrue(manifest().stream().allMatch(r -> r.state().equals("CREATED")));
                assertThrows(IllegalStateException.class, () -> outputs.ready("n"));
            }
            assertTrue(manifest().stream().allMatch(r -> r.state().equals("CLEANED")));
            assertEquals("2", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='TEMP_INTENT'"));
            assertEquals("2", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='TEMP_CREATED'"));
            assertEquals("2", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='TEMP_CLEANED' AND before_state<>after_state"));
            outputs.ready("n"); outputs.publish(); assertEquals("PUBLISHED", row().getState());
            assertEquals("0", pg.value("SELECT count(*) FROM (SELECT split_part(after_state,'/aux:',2) after_aux, "
                    + "lead(split_part(before_state,'/aux:',2)) OVER (ORDER BY revision) next_aux FROM dh_dataforge_etl_file_audit) a "
                    + "WHERE next_aux IS NOT NULL AND after_aux<>next_aux"), "JSONB formatting must not change adjacent manifest audit digests");
            assertTrue(Files.size(main.getParent().resolve("out.csv")) > 0);
            assertFalse(JSONUtil.toJsonStr(service.list(run.getId())).contains("hardlink-v1"));
        } finally { outputs.discard(true); }
    }

    @Test void knownAuxiliaryOrphansNeedLostOriginalLeaseAndAvailableOsLock() throws Exception {
        var scratch = scratch(); var row = row();
        service.recoverPending(); assertEquals(0, row().getAttempts()); assertTrue(Files.exists(scratch.path));
        expire();
        try (var lock = EtlFileStore.acquire(scratch.binding)) {
            service.recoverPending(); assertEquals("RETRY", row().getRecoveryStatus()); assertTrue(Files.exists(scratch.path));
        }
        pg.sql("UPDATE dh_dataforge_etl_file_output SET next_attempt_at=NULL");
        assertTrue(Files.exists(scratch.path)); assertEquals(1, row().getAttempts());
        service.recoverPending(); assertEquals("DISCARDED", row().getState()); assertEquals("CLEANED", manifest().getFirst().state());
        assertFalse(Files.exists(scratch.path)); assertFalse(Files.exists(EtlFileStore.temporaryAnchor(scratch.binding, "excel-sheet-1")));
        long revision = row().getRevision(); service.recoverPending(); assertEquals(revision, row().getRevision());
    }

    @Test void auxiliaryCreationWithoutCommittedIdentityIsRetainedForManualReconciliation() throws Exception {
        var port = port(); var binding = port.reserve("n", "etl://exports", "out.csv"); port.created(binding, EtlFileStore.create(binding));
        port.temporaryIntent(binding, "excel-sheet-1"); var key = EtlFileStore.createTemporary(binding, "excel-sheet-1");
        pg.sql("CREATE FUNCTION deny_aux_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='TEMP_CREATED' THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW; END $$");
        pg.sql("CREATE TRIGGER deny_aux_audit BEFORE INSERT ON dh_dataforge_etl_file_audit FOR EACH ROW EXECUTE FUNCTION deny_aux_audit()");
        assertThrows(RuntimeException.class, () -> port.temporaryCreated(binding, "excel-sheet-1", key));
        assertEquals("INTENT", manifest().getFirst().state()); assertNull(manifest().getFirst().fileKey());
        expire(); service.recoverPending(); assertEquals("MANUAL", row().getState());
        assertTrue(Files.exists(EtlFileStore.temporaryPath(binding, "excel-sheet-1")));
        assertTrue(Files.exists(EtlFileStore.temporaryAnchor(binding, "excel-sheet-1")));
        assertEquals("0", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='TEMP_CREATED'"));
    }

    @Test void auxiliaryCleanupLostAckReconcilesAbsentPairWithoutRewritingAnyOutput() throws Exception {
        var scratch = scratch(); var before = manifest().getFirst();
        EtlFileStore.discardTemporary(scratch.binding, before); // Process dies before its platform acknowledgement.
        assertEquals("CREATED", manifest().getFirst().state()); expire(); service.recoverPending();
        assertEquals("DISCARDED", row().getState()); assertEquals("CLEANED", manifest().getFirst().state());
        assertEquals(before.fileKey(), manifest().getFirst().fileKey()); assertFalse(Files.exists(scratch.binding.target()));
        assertEquals("1", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='TEMP_CLEANED'"));
    }

    @Test void substitutedAuxiliaryFileCannotBeDeletedOrMarkMainOutputDiscarded() throws Exception {
        var scratch = scratch(); Files.delete(scratch.path); Files.writeString(scratch.path, "different object");
        expire(); service.recoverPending(); assertEquals("MANUAL", row().getState()); assertEquals("IDENTITY_UNPROVEN", row().getLastErrorCode());
        assertEquals("different object", Files.readString(scratch.path)); assertTrue(Files.exists(scratch.binding.temporary()));
        assertEquals("CREATED", manifest().getFirst().state());
    }

    @Test void stopBlocksAuxiliaryCallbacksAndIncompleteResourcesBlockReadyEvenThroughPort() throws Exception {
        var scratch = scratch(); var prepared = new EtlFileStore.Prepared(scratch.binding, EtlFileStore.seal(scratch.binding));
        assertThrows(RuntimeException.class, () -> scratch.port.ready(prepared));
        pg.sql("UPDATE dh_dataforge_etl_record SET stop_requested=true WHERE id=" + run.getId());
        assertThrows(RuntimeException.class, () -> scratch.port.temporaryIntent(scratch.binding, "excel-template-1"));
        assertThrows(RuntimeException.class, () -> scratch.port.temporaryCleaned(scratch.binding, "excel-sheet-1", manifest().getFirst().fileKey()));
        assertEquals("CREATED", manifest().getFirst().state()); expire(); service.recoverPending(); assertEquals("DISCARDED", row().getState());
    }

    @Test void resourceIntentGapWithoutFilesAndDeletedRunCanBeRecoveredWithoutGlobalDiscovery() throws Exception {
        var port = port(); var binding = port.reserve("n", "etl://exports", "out.csv"); port.created(binding, EtlFileStore.create(binding));
        port.temporaryIntent(binding, "excel-sheet-1");
        Path unknown = binding.target().getParent().resolve("poi-sxssf-legacy.xml"); Files.writeString(unknown, "unregistered legacy data");
        expire(); pg.sql("DELETE FROM dh_dataforge_etl_record WHERE id=" + run.getId()); service.recoverPending();
        assertEquals("DISCARDED", row().getState()); assertEquals("CLEANED", manifest().getFirst().state());
        assertNull(manifest().getFirst().fileKey()); assertEquals("unregistered legacy data", Files.readString(unknown));
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void auxiliaryMigrationReplaysPreservesManifestAndRejectsMalformedOrExcessiveJson(CapturedOutput output) throws Exception {
        scratch(); var before = row().getTemporaryResourcesJson(); pg.migrate("0116_dataforge_etl-file-temporary-resources.sql");
        assertEquals(before, row().getTemporaryResourcesJson());
        assertThrows(SQLException.class, () -> pg.sql("UPDATE dh_dataforge_etl_file_output SET temporary_resources_json='{}'::jsonb"));
        assertThrows(SQLException.class, () -> pg.sql("UPDATE dh_dataforge_etl_file_output SET temporary_resources_json='[1,2,3,4,5]'::jsonb"));
        assertThrows(SQLException.class, () -> pg.sql("UPDATE dh_dataforge_etl_file_output SET temporary_resources_json=jsonb_build_array(repeat('中',6000))"));
        pg.sql("UPDATE dh_dataforge_etl_file_output SET temporary_resources_json=NULL"); assertNull(row().getTemporaryResourcesJson());
        // NULL is historical absence of evidence, not authorization to scan legacy POI directories.
        String sentinel = "PRIVATE-MANIFEST-MUST-NOT-APPEAR-IN-LOGS";
        pg.sql("UPDATE dh_dataforge_etl_file_output SET temporary_resources_json='[{\"note\":\"" + sentinel + "\"}]'::jsonb");
        expire(); service.recoverPending();
        assertEquals("MANUAL", row().getState(), "Malformed persisted evidence must remain auditable and fail closed");
        assertFalse(output.getAll().contains(sentinel), "Corrupt manifest content must never enter runtime logs");
    }

    private List<EtlFileStore.TemporaryResource> manifest() { return JsonUtils.parseArray(row().getTemporaryResourcesJson(), EtlFileStore.TemporaryResource.class); }
    private record Scratch(EtlFileOutputPort port, EtlFileStore.Binding binding, Path path) { }
    private Scratch scratch() throws Exception {
        var port = port(); var binding = port.reserve("n", "etl://exports", "out.csv"); port.created(binding, EtlFileStore.create(binding));
        port.temporaryIntent(binding, "excel-sheet-1"); var key = EtlFileStore.createTemporary(binding, "excel-sheet-1"); port.temporaryCreated(binding, "excel-sheet-1", key);
        var path = EtlFileStore.temporaryPath(binding, "excel-sheet-1"); Files.writeString(path, "orphan XML"); return new Scratch(port, binding, path);
    }

    private EtlFileOutputPort port() { return service.createPort(run, dag("etl://exports", "n"), Map.of()); }
    private EtlDag dag(String directory, String... nodes) {
        return EtlDag.buildLenient(Arrays.stream(nodes).map(no -> {
            var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode("write-csv"); node.setToolType(EtlNodeDef.TOOL_TYPE_OUTPUT);
            node.setNodeJson(JSONUtil.toJsonStr(Map.of("filePath", directory, "fileName", "out.csv"))); return node;
        }).toList(), List.of());
    }
    private record Staged(EtlFileOutputPort port, EtlFileStore.Prepared prepared) { }
    private Staged staged(EtlFileOutputPort port, String node) throws Exception {
        var binding = port.reserve(node, "etl://exports", "out.csv"); var key = EtlFileStore.create(binding); port.created(binding, key);
        Files.writeString(binding.temporary(), "id\n1\n"); var prepared = new EtlFileStore.Prepared(binding, EtlFileStore.seal(binding)); port.ready(prepared);
        return new Staged(port, prepared);
    }
    private EtlFileOutputDO row() { return mapper.selectList(null).getFirst(); }
    private void expire() throws Exception { pg.sql("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second' WHERE id=" + run.getId()); }
    private EtlFileRetryReqVO retry(EtlFileOutputDO row) {
        var request = new EtlFileRetryReqVO(); request.setId(row.getId()); request.setExpectedRevision(row.getRevision()); request.setReason("核查后重试"); return request;
    }
    private void login(Long actor) { SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(actor).setTenantId(7L), new MockHttpServletRequest()); }
}
