package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

/** Actual current coordinator, PostgreSQL slot constraint, independent reconciliation lease and Spring proxy. */
@Testcontainers
class EtlOwnershipPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    EtlBatchPgFixture f;
    @BeforeEach void setup() throws Exception { f = new EtlBatchPgFixture(POSTGRES); }
    @AfterEach void cleanup() throws Exception { if (f != null) f.close(); }

    @Test void persistentStopAndExpiredOwnershipKeepExecutionTruth() throws Exception {
        var run = f.run("owner", true);
        var queued = f.run("queued", false);
        f.coordinator.requestStop(run.getId());
        var stopped = f.records.selectById(run.getId());
        assertEquals(2, stopped.getRunStatus()); assertTrue(stopped.getStopRequested());
        assertTrue(stopped.getOccupiesSlot(), "stop acceptance is not worker termination");
        assertThrows(RuntimeException.class, () -> f.coordinator.requestStop(-1L));
        assertFalse(f.coordinator.recoverExpired(run.getId()));
        TenantContextHolder.setTenantId(7L);
        f.sql("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second' WHERE id=" + run.getId());
        assertTrue(f.coordinator.recoverExpired(run.getId()));
        var unknown = f.records.selectById(run.getId());
        assertEquals(6, unknown.getRunStatus()); assertTrue(unknown.getOccupiesSlot()); assertTrue(unknown.getReviewRequired());
        assertNull(unknown.getExecutionToken());
        assertFalse(f.coordinator.writeOwned(run.getId(), run.getExecutionToken(), () -> fail("late worker wrote children")));
        assertFalse(f.coordinator.finish(run.getId(), run.getExecutionToken(), 3, RunProtocol.Effect.COMPLETE, true, "late success"));
        assertTrue(f.coordinator.claim(queued.getId(), "another-worker").isEmpty());
        assertThrows(SQLException.class, () -> f.sql("UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=" + queued.getId()));
        assertEquals(1, f.records.selectById(queued.getId()).getRunStatus());
        f.coordinator.requestStop(queued.getId());
        assertEquals(5, f.records.selectById(queued.getId()).getRunStatus());
        assertFalse(f.records.selectById(queued.getId()).getOccupiesSlot());
    }

    @Test void qualityUsesIndependentLeaseAndLateResponseCannotOverwriteManualDecisionOrKeepSlotBlocked() throws Exception {
        var run = f.run("quality-owner", true); var queued = f.run("quality-next", false);
        f.sql("UPDATE dh_dataforge_etl_record SET quality_task_id=100 WHERE id=" + run.getId());
        assertTrue(f.coordinator.finish(run.getId(), run.getExecutionToken(), 3, RunProtocol.Effect.COMPLETE, true, "execution done"));
        assertEquals("PENDING", f.records.selectById(run.getId()).getQualityStatus());
        assertTrue(f.records.selectById(run.getId()).getOccupiesSlot());
        assertTrue(f.coordinator.claim(queued.getId(), "blocked").isEmpty());
        assertThrows(SQLException.class, () -> f.sql("UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=" + queued.getId()));
        var qualityClaim = f.coordinator.claimQuality(run.getId()).orElseThrow();
        assertNull(qualityClaim.getExecutionToken()); assertNotNull(qualityClaim.getReconcileToken());
        f.sql("UPDATE dh_dataforge_etl_record SET quality_status='FAILED',row_version=row_version+1,occupies_slot=false,"
                + "reconcile_token=NULL,reconcile_lease_until=NULL WHERE id=" + run.getId());
        assertFalse(f.coordinator.completeQuality(qualityClaim, new QualityGateService.Result("PASSED", 200L, "late response")));
        var current = f.records.selectById(run.getId());
        assertEquals(3, current.getRunStatus()); assertEquals("FAILED", current.getQualityStatus());
        assertFalse(current.getOccupiesSlot()); assertNull(current.getQualityInstanceId());
        assertTrue(f.coordinator.claim(queued.getId(), "next-worker").isPresent());
    }

    @Test void expiredPreparationCanBeRequeuedButItsOldLeaseCannotResume() throws Exception {
        var queued = f.run("preparation", false);
        var lease = f.coordinator.claim(queued.getId(), "preparer").orElseThrow();
        f.sql("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second' WHERE id=" + queued.getId());
        assertTrue(f.coordinator.recoverExpired(queued.getId()));
        var current = f.records.selectById(queued.getId());
        assertEquals(1, current.getRunStatus()); assertFalse(current.getOccupiesSlot()); assertNull(current.getExecutionToken());
        assertFalse(f.coordinator.startEffects(queued.getId(), lease.token()));
        assertFalse(f.coordinator.renew(queued.getId(), lease.token()));
    }
}