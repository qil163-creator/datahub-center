package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.config.EtlProperties;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.*;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlFileOutputMapper;
import cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated real PostgreSQL receipt/lease/audit transactions and real managed files; no CAS callback is mocked. */
class EtlFileRetirementPostgreSqlTest {
    private static PostgreSQLContainer<?> postgres;
    private static boolean external;
    @TempDir Path root;
    private EtlBatchPgFixture pg;
    private EtlFileRecoveryService service;
    private EtlFileOutputMapper files;
    private EtlRecordDO run;
    private EtlFileStore.Binding binding;
    private EtlFileStore.Fingerprint fingerprint;

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
        pg = new EtlBatchPgFixture(postgres);
        pg.migrate("0115_dataforge_etl-file-recovery.sql"); pg.migrate("0116_dataforge_etl-file-temporary-resources.sql");
        pg.migrate("0117_dataforge_etl-file-retirement.sql");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("retirement-real-pg", new SpringManagedTransactionFactory(), pg.platform));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(pg.platform);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor); configuration.addMapper(EtlFileOutputMapper.class);
        files = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(EtlFileOutputMapper.class);
        service = new EtlFileRecoveryService();
        var storage = new EtlFileStorageProperties(); storage.setStorageRoots(Map.of("exports", root.toString()));
        var properties = new EtlProperties(); properties.getFileOutput().setAllowedDirectories(List.of(root.toString()));
        ReflectionTestUtils.setField(service, "files", files); ReflectionTestUtils.setField(service, "records", pg.records);
        ReflectionTestUtils.setField(service, "coordinator", pg.coordinator); ReflectionTestUtils.setField(service, "access", pg.access);
        ReflectionTestUtils.setField(service, "storageProperties", storage); ReflectionTestUtils.setField(service, "etlProperties", properties);
        ReflectionTestUtils.setField(service, "transactionManager", pg.app.getBean(PlatformTransactionManager.class));
        login(99L); run = pg.run("retirement-fixture", true);
        var node = new EtlNodeDef(); node.setNo("out"); node.setName("out"); node.setToolCode("write-csv");
        node.setToolType(EtlNodeDef.TOOL_TYPE_OUTPUT); node.setNodeJson(JSONUtil.toJsonStr(Map.of("filePath", "etl://exports", "fileName", "out.csv")));
        var port = service.createPort(run, EtlDag.buildLenient(List.of(node), List.of()), Map.of());
        binding = port.reserve("out", "etl://exports", "out.csv");
        try (var ignored = EtlFileStore.acquire(binding)) {
            port.created(binding, EtlFileStore.create(binding)); Files.writeString(binding.temporary(), "id\n1\n");
            fingerprint = EtlFileStore.seal(binding); var prepared = new EtlFileStore.Prepared(binding, fingerprint);
            port.ready(prepared); port.approvePublish(List.of(prepared)); EtlFileStore.publish(prepared);
            port.published(prepared); port.discarded(binding, "PUBLISHED");
        }
        assertTrue(pg.coordinator.finish(run.getId(), run.getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS, RunProtocol.Effect.COMPLETE, true, "done"));
    }
    @AfterEach void close() throws Exception { SecurityContextHolder.clearContext(); if (pg != null) pg.close(); }

    @Test void defaultKeepNeverDeletesAnOldSuccessfulPublication() throws Exception {
        pg.sql("UPDATE dh_dataforge_etl_file_output SET create_time=clock_timestamp()-interval '2 years'");
        service.recoverPending(); service.recover(row().getId());
        assertEquals("KEEP", row().getRetentionStatus()); assertEquals("NONE", row().getPurgePhase()); assertPair();
    }

    @Test void explicitRequestAndLostHttpAcknowledgementCreateExactlyOneAuditedIntent() throws Exception {
        var request = request(); var response = service.requestRetirement(request);
        assertEquals("REQUESTED", response.getRetentionStatus()); assertEquals("PENDING", response.getRecoveryStatus());
        assertEquals(99L, response.getRetirementRequestedBy()); assertEquals(request.getExpectedRevision() + 1, response.getRevision());
        assertEquals(response.getRevision(), service.requestRetirement(request).getRevision());
        assertEquals("1", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='RETIRE_REQUEST'"));
        service.recoverPending(); service.recover(row().getId()); assertEquals(0, row().getAttempts()); assertPair();
        assertFalse(JSONUtil.toJsonStr(response).contains(root.toString())); assertFalse(JSONUtil.toJsonStr(response).contains("storageIdentity"));
    }

    @Test void dueOwnedCleanupCompletesAndDuplicateDispatchPreservesPublicationHistory() throws Exception {
        var request = request(); service.requestRetirement(request); makeDue();
        request.setRetireAfter(row().getRetireAfter()); // Simulate the original accepted deadline after the isolated clock fixture advances it.
        service.recoverPending(); assertEquals("PURGED", row().getRetentionStatus()); assertEquals("COMPLETE", row().getPurgePhase());
        assertEquals("PUBLISHED", row().getState()); assertEquals("DONE", row().getRecoveryStatus());
        assertFalse(Files.exists(binding.target())); assertFalse(Files.exists(binding.anchor())); assertTrue(Files.exists(binding.lock()));
        assertEquals("COMPLETE", pg.records.selectById(run.getId()).getEffectState());
        long revision = row().getRevision(); service.recoverPending(); service.recover(row().getId());
        assertEquals(revision, row().getRevision()); assertEquals("PURGED", service.requestRetirement(request).getRetentionStatus());
        assertEquals("1", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='RETIRE_REQUEST'"));
    }

    @Test void invalidConfirmationDeadlineRevisionAndOwnerCannotCreateIntent() throws Exception {
        var unconfirmed = request(); unconfirmed.setConfirmDeletion(false); assertThrows(RuntimeException.class, () -> service.requestRetirement(unconfirmed));
        var old = request(); old.setRetireAfter(LocalDateTime.MIN);
        assertThrows(RuntimeException.class, () -> service.requestRetirement(old));
        var far = request(); far.setRetireAfter(LocalDateTime.MAX);
        assertThrows(RuntimeException.class, () -> service.requestRetirement(far));
        var stale = request(); stale.setExpectedRevision(row().getRevision() + 1);
        assertThrows(RuntimeException.class, () -> service.requestRetirement(stale));
        var owned = request(); login(100L); assertThrows(RuntimeException.class, () -> service.requestRetirement(owned));
        TenantContextHolder.setTenantId(7L); assertEquals("KEEP", row().getRetentionStatus()); assertPair();
    }

    @Test void failedRequestAuditRollsBackEveryRetirementField() throws Exception {
        denyAudit("NEW.action='RETIRE_REQUEST'"); var request = request();
        assertThrows(RuntimeException.class, () -> service.requestRetirement(request));
        assertEquals("KEEP", row().getRetentionStatus()); assertEquals("DONE", row().getRecoveryStatus());
        assertNull(row().getRetireAfter()); assertNull(row().getRetirementRequestedBy()); assertPair();
    }

    @Test void aDownloadStartedBeforeTheRequestFencesCleanupUntilItCloses() throws Exception {
        try (var download = EtlFileStore.openPublished(binding, fingerprint)) {
            service.requestRetirement(request()); makeDue(); service.recoverPending();
            assertEquals("RETRY", row().getRecoveryStatus()); assertEquals("NONE", row().getPurgePhase()); assertPair(); download.verifyUnchanged();
        }
        clearBackoff(); service.recoverPending(); assertEquals("PURGED", row().getRetentionStatus());
    }

    @Test void identicalReplacementMovesToManualAndIsNeverDeleted() throws Exception {
        service.requestRetirement(request()); makeDue(); Files.delete(binding.target()); Files.writeString(binding.target(), "id\n1\n");
        service.recoverPending(); assertEquals("MANUAL", row().getRecoveryStatus()); assertEquals("REQUESTED", row().getRetentionStatus());
        assertEquals("RETIREMENT_IDENTITY_UNPROVEN", row().getLastErrorCode()); assertPair();
    }

    @Test void newlyIntroducedKnownLedgerReferencePreventsUnlink() throws Exception {
        service.requestRetirement(request()); makeDue();
        pg.sql("INSERT INTO dh_dataforge_etl_file_output(record_id,pipeline_id,node_no,owner_id,run_token,operation_key,storage_key,storage_identity,"
                + "file_name,state,temporary_file_key,payload_hash,payload_bytes,publication_approved,recovery_status) "
                + "SELECT record_id,pipeline_id,'other-reference',owner_id,run_token,'" + UUID.randomUUID() + "',storage_key,storage_identity,"
                + "'other.csv',state,temporary_file_key,payload_hash,payload_bytes,publication_approved,'DONE' FROM dh_dataforge_etl_file_output");
        service.recoverPending(); assertEquals("REQUESTED", row().getRetentionStatus()); assertNotEquals("PURGED", row().getRetentionStatus()); assertPair();
    }

    @Test void reopenedOrIncompleteRunAndRevokedCleanupAuthorityPreventUnlink() throws Exception {
        service.requestRetirement(request()); makeDue();
        pg.sql("UPDATE dh_dataforge_etl_record SET occupies_slot=true,effect_state='UNKNOWN' WHERE id=" + run.getId());
        service.recoverPending(); assertEquals("RETRY", row().getRecoveryStatus()); assertPair();
        pg.sql("UPDATE dh_dataforge_etl_record SET occupies_slot=false,effect_state='COMPLETE' WHERE id=" + run.getId()); clearBackoff();
        doThrow(new IllegalStateException("permission revoked")).when(pg.access).requireUserPermission(99L, EtlFileRecoveryService.FILE_CLEANUP_PERMISSION);
        service.recoverPending(); assertEquals("REQUESTED", row().getRetentionStatus()); assertPair();
    }

    @Test void realIntentTransactionFailureDoesNotUnlinkEitherName() throws Exception {
        service.requestRetirement(request()); makeDue(); denyAudit("NEW.action='RETIRE_PHASE'");
        service.recoverPending(); assertEquals("RETRY", row().getRecoveryStatus()); assertEquals("NONE", row().getPurgePhase()); assertPair();
    }

    @Test void lostPostUnlinkReceiptRetriesBothMissingObjectsUsingCommittedIntent() throws Exception {
        service.requestRetirement(request()); makeDue(); denyAudit("NEW.action='RETIRE_PHASE' AND NEW.after_state LIKE '%/TARGET_REMOVED/%'");
        service.recoverPending(); assertEquals("RETRY", row().getRecoveryStatus()); assertEquals("INTENT", row().getPurgePhase());
        assertFalse(Files.exists(binding.target())); assertFalse(Files.exists(binding.anchor()));
        pg.sql("DROP TRIGGER deny_retirement_audit ON dh_dataforge_etl_file_audit"); clearBackoff(); service.recoverPending();
        assertEquals("PURGED", row().getRetentionStatus()); assertEquals("DONE", row().getRecoveryStatus());
    }

    @Test void interruptedTargetUnlinkResumesOnlyWithProvableNativeAnchorIdentity() throws Exception {
        service.requestRetirement(request()); makeDue();
        pg.sql("UPDATE dh_dataforge_etl_file_output SET purge_phase='INTENT'"); Files.delete(binding.target());
        boolean nativeIdentity = Files.readAttributes(binding.anchor(), BasicFileAttributes.class).fileKey() != null;
        service.recoverPending();
        if (nativeIdentity) { assertEquals("PURGED", row().getRetentionStatus()); assertFalse(Files.exists(binding.anchor())); }
        else { assertEquals("MANUAL", row().getRecoveryStatus()); assertTrue(Files.exists(binding.anchor())); }
    }

    @Test void expiredClaimCanBeRecoveredButALiveClaimCannotBeTakenOver() throws Exception {
        service.requestRetirement(request()); makeDue();
        String token = UUID.randomUUID().toString();
        pg.sql("UPDATE dh_dataforge_etl_file_output SET recovery_status='CLAIMED',recovery_token='" + token
                + "',recovery_lease_until=clock_timestamp()+interval '1 minute',attempts=1");
        service.recoverPending(); service.recover(row().getId()); assertEquals(token, row().getRecoveryToken()); assertPair();
        pg.sql("UPDATE dh_dataforge_etl_file_output SET recovery_lease_until=clock_timestamp()-interval '1 second'");
        service.recoverPending(); assertEquals("PURGED", row().getRetentionStatus()); assertEquals(2, row().getAttempts());
        assertNull(row().getRecoveryToken()); assertNull(row().getRecoveryLeaseUntil());
    }

    @Test void migrationReplayPreservesRequestsAndManualRetryNeverCreatesANewRetirementIntent() throws Exception {
        service.requestRetirement(request()); makeDue(); pg.migrate("0117_dataforge_etl-file-retirement.sql");
        assertEquals("REQUESTED", row().getRetentionStatus());
        pg.sql("UPDATE dh_dataforge_etl_file_output SET recovery_status='MANUAL'");
        var retry = new EtlFileRetryReqVO(); retry.setId(row().getId()); retry.setExpectedRevision(row().getRevision()); retry.setReason("retry existing intent");
        service.retry(retry); service.recoverPending(); assertEquals("PURGED", row().getRetentionStatus());
        assertEquals("1", pg.value("SELECT count(*) FROM dh_dataforge_etl_file_audit WHERE action='RETIRE_REQUEST'"));
        retry.setExpectedRevision(row().getRevision()); assertThrows(RuntimeException.class, () -> service.retry(retry));
    }

    private EtlFileOutputDO row() { return files.selectList(null).stream().filter(f -> "out".equals(f.getNodeNo())).findFirst().orElseThrow(); }
    private EtlFileRetireReqVO request() {
        var request = new EtlFileRetireReqVO(); request.setId(row().getId()); request.setExpectedRevision(row().getRevision());
        request.setRetireAfter(pg.records.databaseNow().plusMinutes(2).withNano(0)); request.setReason("expired owned fixture output"); request.setConfirmDeletion(true); return request;
    }
    private void makeDue() throws Exception { pg.sql("UPDATE dh_dataforge_etl_file_output SET retire_after=clock_timestamp()-interval '1 second' WHERE node_no='out'"); }
    private void clearBackoff() throws Exception { pg.sql("UPDATE dh_dataforge_etl_file_output SET next_attempt_at=NULL WHERE node_no='out'"); }
    private void assertPair() { assertTrue(Files.exists(binding.target())); assertTrue(Files.exists(binding.anchor())); }
    private void login(Long actor) { SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(actor).setTenantId(7L), new MockHttpServletRequest()); }
    private void denyAudit(String predicate) throws Exception {
        pg.sql("CREATE FUNCTION deny_retirement_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + predicate
                + " THEN RAISE EXCEPTION 'isolated receipt failure'; END IF; RETURN NEW; END $$");
        pg.sql("CREATE TRIGGER deny_retirement_audit BEFORE INSERT ON dh_dataforge_etl_file_audit FOR EACH ROW EXECUTE FUNCTION deny_retirement_audit()");
    }
}
