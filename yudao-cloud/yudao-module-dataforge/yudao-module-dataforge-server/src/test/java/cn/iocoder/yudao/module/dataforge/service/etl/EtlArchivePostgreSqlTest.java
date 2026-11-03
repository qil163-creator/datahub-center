package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class EtlArchivePostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    EtlBatchPgFixture f;
    EtlArchiveService archive;
    GovernanceEtlApi governance;
    MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach void setup() throws Exception {
        f = new EtlBatchPgFixture(POSTGRES);
        security = mockStatic(SecurityFrameworkUtils.class); security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(99L);
        governance = mock(GovernanceEtlApi.class);
        archive = new EtlArchiveService();
        inject(archive, "archives", f.app.getBean(EtlArchiveMapper.class));
        inject(archive, "batches", f.app.getBean(EtlQualityBatchMapper.class));
        inject(archive, "records", f.records); inject(archive, "coordinator", f.coordinator);
        inject(archive, "governance", governance); inject(archive, "access", f.access);
    }
    @AfterEach void close() throws Exception { if (security != null) security.close(); if (f != null) f.close(); }

    @Test void archiveAndRestoreAreIdempotentAuditedAndRejectMissingRun() throws Exception {
        var run = completed(); var request = archiveRequest(run.getId());
        archive.archive(request); archive.archive(request);
        assertEquals("1", f.value("SELECT count(*) FROM dh_dataforge_etl_archive_action WHERE action='ARCHIVE'"));
        assertThrows(RuntimeException.class, () -> archive.requireActive(run.getId()));
        archive.requireEvidenceAccess(run.getId());
        assertThrows(RuntimeException.class, () -> archive.get(-1L));
        assertThrows(RuntimeException.class, () -> archive.restore(retry(-1L)));
        archive.restore(retry(run.getId())); archive.restore(retry(run.getId())); archive.requireActive(run.getId());
        assertEquals("2", f.value("SELECT count(*) FROM dh_dataforge_etl_archive_action"));
        assertEquals("3", f.value("SELECT run_status FROM dh_dataforge_etl_record WHERE id=" + run.getId()));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(f.control, f.schema);
        assertEquals("2", f.value("SELECT count(*) FROM dh_dataforge_etl_archive_action"));
    }

    @Test void expiryBlocksRestorationAndRawEvidenceButKeepsAudit() throws Exception {
        var run = completed(); archive.archive(archiveRequest(run.getId()));
        f.sql("UPDATE dh_dataforge_etl_archive SET archived_at=clock_timestamp()-interval '40 days',evidence_access_until=clock_timestamp()-interval '1 second'");
        assertThrows(RuntimeException.class, () -> archive.restore(retry(run.getId())));
        assertThrows(RuntimeException.class, () -> archive.requireEvidenceAccess(run.getId()));
        assertNotNull(archive.get(run.getId()));
        assertEquals("1", f.value("SELECT count(*) FROM dh_dataforge_etl_archive_action"));
    }

    @Test void incompleteEvidenceAndUnclosedGovernanceBlockArchiveAndRpcRunsOutsideTransaction() throws Exception {
        var run = completed(); f.quality(run, "quality", 1, 0, 0, 1);
        assertThrows(RuntimeException.class, () -> archive.archive(archiveRequest(run.getId())));
        verifyNoInteractions(governance);
        f.sql("UPDATE dh_dataforge_etl_quality_batch SET registration_status='REGISTERED',governance_report_id=42");
        when(governance.canArchiveReports(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return CommonResult.success(false);
        });
        assertThrows(RuntimeException.class, () -> archive.archive(archiveRequest(run.getId())));
        assertEquals("0", f.value("SELECT count(*) FROM dh_dataforge_etl_archive"));
        when(governance.canArchiveReports(any())).thenReturn(CommonResult.success(true));
        archive.archive(archiveRequest(run.getId()));
        assertEquals("1", f.value("SELECT count(*) FROM dh_dataforge_etl_archive"));
    }

    @Test void activeRunCannotBeArchived() {
        var run = f.run("active", true);
        assertThrows(RuntimeException.class, () -> archive.archive(archiveRequest(run.getId())));
    }

    private EtlRecordDO completed() throws Exception {
        var run = f.run(UUID.randomUUID().toString(), false);
        f.sql("UPDATE dh_dataforge_etl_record SET run_status=3,occupies_slot=false WHERE id=" + run.getId());
        return f.records.selectById(run.getId());
    }
    private EtlArchiveReqVO archiveRequest(Long id) { var r = new EtlArchiveReqVO(); r.setRecordId(id); r.setReason("验收后归档"); r.setRetentionDays(30); return r; }
    private EtlReplayRetryReqVO retry(Long id) { var r = new EtlReplayRetryReqVO(); r.setRecordId(id); r.setReason("恢复依赖后重试"); return r; }
    private void inject(Object target, String field, Object value) { ReflectionTestUtils.setField(target, field, value); }
}
