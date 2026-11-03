package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBinding;
import cn.iocoder.yudao.framework.dbswitch.driver.*;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Business dispatch tests. Native TD restoration and pool-exit evidence have separate real/driver tests. */
class DataAccessGroupRecoveryExecutionTest {
    private final DataAccessExecutor executor = new DataAccessExecutor();
    private final AccessRunCoordinator coordinator = mock(AccessRunCoordinator.class);
    private final AccessGroupRewriteCoordinator groups = mock(AccessGroupRewriteCoordinator.class);
    private final AccessGroupRewriteCoordinator.RecoveryHandle recovery = mock(AccessGroupRewriteCoordinator.RecoveryHandle.class);
    private final AccessRunAuthorization authorization = mock(AccessRunAuthorization.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DatabaseQuerySupport permission = mock(DatabaseQuerySupport.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final ConfiguredJdbcDrivers drivers = mock(ConfiguredJdbcDrivers.class);
    private final ConfiguredJdbcDrivers.Binding defaultDriver = mock(ConfiguredJdbcDrivers.Binding.class);
    private final DriverRuntimeRegistry registry = mock(DriverRuntimeRegistry.class);
    private final ExecutorService workers = mock(ExecutorService.class);
    private final IDatabaseSynchronize sync = mock(IDatabaseSynchronize.class);
    private final DbSwitchOptions options = DbSwitchOptions.defaults();
    private DataAccessDO access;
    private DataAccessTaskDO run;

    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(1L);
        var config = new AccessTaskConfig(); var source = new AccessTaskConfig.Source(); source.setSourceIncludes("source_stable");
        config.setSource(List.of(source)); config.getTarget().setChangeDataSync(true); config.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        access = new DataAccessDO(); access.setId(10L); access.setSourceDatabaseId(11L); access.setTargetDatabaseId(12L);
        access.setEngineType(1); access.setDataAccessJson(config);
        run = new DataAccessTaskDO(); run.setId(31L); run.setDataAccessId(10L); run.setConfigJson(AccessRunSnapshot.freeze(access));
        run.setReleaseRevision(1);
        when(resolver.resolve(eq(11L), any())).thenReturn(connection(11L, "source_db"));
        when(resolver.resolve(eq(12L), any())).thenReturn(connection(12L, "target_db"));
        when(runtime.drivers()).thenReturn(drivers); when(runtime.options()).thenReturn(options); when(drivers.runtimes()).thenReturn(registry);
        when(drivers.find(ProductTypeEnum.TDENGINE)).thenReturn(Optional.of(defaultDriver));
        when(runtime.synchronizer(any(), eq(ProductTypeEnum.TDENGINE))).thenReturn(sync);
        when(groups.prepareRecovery(any(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(mock(AccessGroupRewriteCoordinator.RecoveryAdmission.class));
        var pendingTable = table("leaf"); when(recovery.pendingTables()).thenReturn(List.of(pendingTable));
        when(workers.submit(any(Runnable.class))).thenAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return CompletableFuture.completedFuture(null); });
        var timing = new RunExecutionProperties(); timing.setStopPoll(Duration.ofMillis(20));
        ReflectionTestUtils.setField(executor, "properties", timing);
        ReflectionTestUtils.setField(executor, "coordinator", coordinator); ReflectionTestUtils.setField(executor, "groupRewriteCoordinator", groups);
        ReflectionTestUtils.setField(executor, "authorization", authorization); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", permission); ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(executor, "executor", workers);
    }
    @AfterEach void clear() {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext(); Thread.interrupted(); TransactionSynchronizationManager.clear();
    }

    @Test void prepareOnlyResolvesConfigurationAndKeepsCredentialBearingMaterialRedacted() {
        try (var pools = mockStatic(DataSourceUtils.class)) {
            var prepared = prepare();
            assertNotNull(prepared.admission()); assertEquals("PreparedGroupRecovery[redacted]", prepared.toString());
            verify(defaultDriver, times(2)).verifyUnchanged();
            verify(groups).prepareRecovery(any(), same(run), eq("recovery-token"), any(), any(), any(), any());
            pools.verifyNoInteractions(); verify(runtime, never()).synchronizer(any(), any());
        }
    }

    @Test void multipleOrDynamicallyDiscoveredSourcesAreRejectedBeforeAnyConnectionResolution() {
        var raw = cn.hutool.json.JSONUtil.parseObj(run.getConfigJson());
        raw.getJSONObject("dataAccessJson").set("source", List.of(new AccessTaskConfig.Source(), new AccessTaskConfig.Source()));
        run.setConfigJson(raw.toString());
        assertThrows(IllegalArgumentException.class, this::prepare); verifyNoInteractions(resolver, groups);
        var dynamic = new AccessTaskConfig.Source(); dynamic.setIncludeOrExclude(2);
        raw.getJSONObject("dataAccessJson").set("source", List.of(dynamic)); run.setConfigJson(raw.toString());
        assertThrows(IllegalArgumentException.class, this::prepare); verifyNoInteractions(resolver, groups);
    }

    @Test void executionUsesCurrentActorTenantAndOnlyTargetPrepareAfterWholeMaterialAndPermissionPreflight() {
        var originalSecurity = SecurityContextHolder.getContext();
        doAnswer(inv -> {
            assertEquals(1L, TenantContextHolder.getRequiredTenantId());
            assertEquals(88L, ((LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId());
            return null;
        }).when(authorization).execution(any(), eq(88L));
        when(recovery.finished(true)).thenReturn(true);
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare()));
            var order = inOrder(recovery, authorization, permission, sync, io.pool, coordinator);
            order.verify(authorization).action(any(), eq(88L), eq("dataforge:access:resolve"));
            order.verify(authorization).execution(any(), eq(88L));
            order.verify(recovery).assertOwnership(); order.verify(recovery).pendingTables();
            order.verify(sync).configureBatchLimits(BatchLimits.defaults()); order.verify(sync).bindGroupRewrite(any());
            order.verify(sync).prepare("target_db", "leaf", List.of("ts", "id", "value"), List.of("ts", "id"));
            order.verify(io.pool).close(); order.verify(recovery).finished(true);
            order.verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, true);
            verify(sync, never()).executeInsert(any()); verify(sync, never()).executeUpdate(any()); verify(sync, never()).executeDelete(any());
            assertEquals(1, io.opened.get()); assertSame(originalSecurity, SecurityContextHolder.getContext());
            assertEquals(4, permits());
        }
    }

    @Test void allAppliedJournalSkipsTargetPoolAndStillLeavesFinalReviewToCoordinator() {
        when(recovery.pendingTables()).thenReturn(List.of()); when(recovery.finished(true)).thenReturn(true);
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare())); assertEquals(0, io.opened.get()); verifyNoInteractions(sync);
            verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, true);
        }
    }

    @Test void revokedAuthorizationOrCorruptLaterTableHasZeroTargetIo() {
        recordsDrainButKeepsPendingGroups();
        doThrow(new IllegalStateException("private-auth-secret")).when(authorization).execution(any(), any());
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare())); assertEquals(0, io.opened.get()); verifyNoInteractions(sync);
            verify(recovery).finished(true); verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, false);
        }
        reset(authorization, recovery); when(recovery.pendingTables()).thenThrow(new IllegalStateException("private-material-secret"));
        try (var io = new TargetIo()) { assertTrue(submit(prepare())); assertEquals(0, io.opened.get()); }
    }

    @Test void configurationRevisionChangeAfterAdmissionRejectsTheOldTargetWithoutIo() {
        var prepared = prepare();
        var changed = connection(12L, "target_db"); changed.setConfigurationRevision("changed");
        when(resolver.resolve(eq(12L), any())).thenReturn(changed);
        try (var io = new TargetIo()) { assertTrue(submit(prepared)); assertEquals(0, io.opened.get()); }
        verify(recovery).finished(true); verifyNoInteractions(sync);
    }

    @Test void repeatedPreparedDispatchCannotRunOrReleaseTheFirstExecutionTwice() {
        var prepared = prepare();
        try (var io = new TargetIo()) {
            assertTrue(submit(prepared)); assertFalse(submit(prepared)); assertEquals(1, io.opened.get());
            verify(workers, times(1)).submit(any(Runnable.class)); verify(recovery, times(1)).finished(true);
        }
    }

    @Test void transactionRollbackDoesNotDispatchAndCommitDispatchesExactlyOnce() {
        var prepared = prepare(); TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var io = new TargetIo()) {
            assertTrue(submit(prepared)); assertFalse(submit(prepared)); assertEquals(0, io.opened.get());
            var hooks = TransactionSynchronizationManager.getSynchronizations(); assertEquals(1, hooks.size());
            hooks.getFirst().afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            assertEquals(0, io.opened.get()); verifyNoInteractions(recovery);
        }
        TransactionSynchronizationManager.clear(); TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare())); var hook = TransactionSynchronizationManager.getSynchronizations().getFirst();
            TransactionSynchronizationManager.setActualTransactionActive(false); hook.afterCommit();
            assertEquals(1, io.opened.get()); verify(recovery).finished(true);
        }
    }

    @Test void localCapacityAndExecutorRejectionOnlyConfirmThisProcessHadNoTargetIo() {
        ReflectionTestUtils.setField(executor, "capacity", new Semaphore(0));
        try (var io = new TargetIo()) { assertFalse(submit(prepare())); assertEquals(0, io.opened.get()); }
        verify(recovery).finished(true);
        reset(recovery); ReflectionTestUtils.setField(executor, "capacity", new Semaphore(1));
        when(workers.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException());
        try (var io = new TargetIo()) { assertFalse(submit(prepare())); assertEquals(0, io.opened.get()); assertEquals(1, permits()); }
        verify(recovery).finished(true);
    }

    @Test void aDrainedPriorExecutorStillInLocalMapDoesNotLeaveTheNewAdmissionPermanentlyUndrained() {
        @SuppressWarnings("unchecked") var running = (Map<String, Object>) ReflectionTestUtils.getField(executor, "runningTasks");
        running.put("31", new Object()); // Old worker's authoritative completion precedes removal from the local map.
        recordsDrainButKeepsPendingGroups();
        try (var io = new TargetIo()) { assertFalse(submit(prepare())); assertEquals(0, io.opened.get()); }
        verify(recovery).finished(true); verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, false);
        verifyNoInteractions(workers); assertEquals(4, permits());
    }

    @Test void unknownNativeResultCanRecordDrainedOnlyAfterPhysicalExitButKeepsPendingGroups() {
        recordsDrainButKeepsPendingGroups();
        doThrow(new IllegalStateException("private-driver-secret")).when(sync).prepare(anyString(), anyString(), any(), any());
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare())); assertTrue(io.exit.isDone());
            verify(recovery).finished(true); verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, false);
        }
    }

    @Test void hikariClosedWithPendingPhysicalReceiptNeverSignsTheExecutorAsDrained() {
        try (var io = new TargetIo()) {
            doNothing().when(io.pool).close(); when(io.pool.isClosed()).thenReturn(true);
            assertTrue(submit(prepare())); assertFalse(io.exit.isDone());
            verify(recovery).finished(false); verify(coordinator).finishGroupRecovery(31L, "recovery-token", false, false);
        }
    }

    @Test void poolConstructionFailureWithoutAnOwnedReceiptCannotInventPhysicalExit() {
        try (var io = new TargetIo()) {
            io.failOpening.set(true); assertTrue(submit(prepare()));
            verify(recovery).finished(false); verify(coordinator).finishGroupRecovery(31L, "recovery-token", false, false);
        }
    }

    @Test void latePhysicalExitIsRetainedUntilTheExistingMonitorPersistsItsReceipt() {
        try (var io = new TargetIo()) {
            doNothing().when(io.pool).close();
            assertTrue(submit(prepare()));
            verify(recovery).finished(false);
            assertEquals(3, permits(), "A quarantined physical operation keeps its bounded local slot");
            io.exit.complete(null);
            verify(recovery, never()).finished(true); // Completing a driver callback never performs control database I/O.
            when(recovery.finished(true)).thenReturn(true);
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            verify(recovery).finished(true); verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, true);
            assertEquals(4, permits());
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            verify(recovery, times(1)).finished(true); assertEquals(4, permits());
        }
    }

    @Test void controlDatabaseOutageAfterPhysicalExitCanRetryWithoutRepeatingTargetWork() {
        when(recovery.finished(true)).thenThrow(new IllegalStateException("private-control-secret")).thenReturn(true);
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare())); assertEquals(1, io.opened.get()); assertEquals(3, permits());
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            verify(recovery, times(2)).finished(true); assertEquals(1, io.opened.get()); assertEquals(4, permits());
            verify(sync, times(1)).prepare(anyString(), anyString(), any(), any());
        }
    }

    @Test void monitorUsesRecoveryLeaseAndStopBeforeWorkerStartHasZeroTargetIo() {
        recordsDrainButKeepsPendingGroups();
        var queued = new AtomicReference<Runnable>();
        when(workers.submit(any(Runnable.class))).thenAnswer(inv -> { queued.set(inv.getArgument(0)); return new CompletableFuture<>(); });
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare()));
            @SuppressWarnings("unchecked") var running = (Map<String, Object>) ReflectionTestUtils.getField(executor, "runningTasks");
            ReflectionTestUtils.setField(running.values().iterator().next(), "lastRenewNanos", 0L);
            when(coordinator.renewGroupRecovery(31L, "recovery-token")).thenReturn(false);
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            queued.get().run(); assertEquals(0, io.opened.get()); verify(coordinator).renewGroupRecovery(31L, "recovery-token");
            verify(coordinator, never()).renew(any()); verify(recovery).finished(true); assertEquals(4, permits());
        }
    }

    @Test void controlCompletionFailureKeepsReviewTokenAndLocalSlotAndRestoresSecurity() {
        when(recovery.finished(true)).thenThrow(new IllegalStateException("private-control-secret"));
        doThrow(new IllegalStateException("private-control-secret")).when(coordinator).finishGroupRecovery(any(), anyString(), anyBoolean(), anyBoolean());
        var before = SecurityContextHolder.getContext();
        try (var io = new TargetIo()) {
            assertTrue(submit(prepare()));
            // An unacknowledged drain is retried by the monitor without repeating any target work.
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            assertEquals(1, io.opened.get()); verify(sync, times(1)).prepare(anyString(), anyString(), any(), any());
        }
        verify(coordinator, times(2)).finishGroupRecovery(31L, "recovery-token", false, false);
        assertEquals(3, permits()); assertSame(before, SecurityContextHolder.getContext());
    }

    @Test void failedPhysicalReceiptKeepsTheTokenAndLocalSlotWithoutControlIo() {
        try (var io = new TargetIo()) {
            doNothing().when(io.pool).close();
            assertTrue(submit(prepare()));
            verify(recovery).finished(false); verify(coordinator).finishGroupRecovery(31L, "recovery-token", false, false);
            io.exit.completeExceptionally(new IllegalStateException("private-driver-secret"));
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            verify(recovery, never()).finished(true); verify(coordinator, times(1)).finishGroupRecovery(any(), anyString(), anyBoolean(), anyBoolean());
            assertEquals(3, permits());
        }
    }

    @Test void saturatedExecutorDefersReceiptPersistenceToTheNextMonitorTick() {
        try (var io = new TargetIo()) {
            doNothing().when(io.pool).close();
            assertTrue(submit(prepare())); assertEquals(3, permits());
            io.exit.complete(null); when(recovery.finished(true)).thenReturn(true);
            doThrow(new RejectedExecutionException()).when(workers).submit(any(Runnable.class));
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            verify(recovery, never()).finished(true); assertEquals(3, permits());
            doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return CompletableFuture.completedFuture(null); })
                    .when(workers).submit(any(Runnable.class));
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            verify(recovery).finished(true); verify(coordinator).finishGroupRecovery(31L, "recovery-token", true, true);
            assertEquals(4, permits()); assertEquals(1, io.opened.get());
        }
    }

    /** A real handle durably records the executor drain on a terminated finish even while data groups stay pending. */
    private void recordsDrainButKeepsPendingGroups() {
        var drained = new java.util.concurrent.atomic.AtomicBoolean();
        when(recovery.finished(true)).thenAnswer(inv -> { drained.set(true); return false; });
        when(recovery.hasConfirmedExecutorDrain()).thenAnswer(inv -> drained.get());
    }

    private DataAccessExecutor.PreparedGroupRecovery prepare() { return executor.prepareGroupRecovery(access, run, "recovery-token"); }
    private boolean submit(DataAccessExecutor.PreparedGroupRecovery prepared) { return executor.submitGroupRecovery(31L, "recovery-token", 88L, prepared, recovery); }
    private int permits() { return ((Semaphore) ReflectionTestUtils.getField(executor, "capacity")).availablePermits(); }
    private AccessGroupRewriteCoordinator.RecoveryTable table(String table) {
        var binding = mock(GroupRewriteBinding.class); when(binding.limits()).thenReturn(BatchLimits.defaults());
        return new AccessGroupRewriteCoordinator.RecoveryTable("target_db", table, List.of("ts", "id", "value"), List.of("ts", "id"), binding);
    }
    private AccessDatabaseResolver.ConnectionInfo connection(long id, String schema) {
        var value = new AccessDatabaseResolver.ConnectionInfo(); value.setDatabaseId(id);
        value.setProductType(ProductTypeEnum.TDENGINE); value.setSchema(schema); value.setConfigurationRevision("revision-1");
        value.setJdbcUrl("jdbc:TAOS-WS://fixture.invalid/" + schema); value.setUsername("fixture"); value.setPassword("private-runtime-secret"); return value;
    }
    private final class TargetIo implements AutoCloseable {
        final MockedStatic<DataSourceUtils> pools = mockStatic(DataSourceUtils.class);
        final MockedStatic<DriverPoolFactory> receipts = mockStatic(DriverPoolFactory.class);
        final HikariDataSource pool = mock(HikariDataSource.class);
        final CompletableFuture<Void> exit = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failOpening = new java.util.concurrent.atomic.AtomicBoolean();
        TargetIo() {
            pools.when(() -> DataSourceUtils.createTargetDataSource(any(TargetDataSourceProperties.class), isNull(), isNull(), same(defaultDriver),
                    same(registry), same(options), anyLong())).thenAnswer(inv -> {
                opened.incrementAndGet(); if (failOpening.get()) throw new IllegalStateException("private-pool-secret"); return pool;
            });
            receipts.when(() -> DriverPoolFactory.physicalTermination(pool)).thenReturn(exit.minimalCompletionStage());
            doAnswer(inv -> { exit.complete(null); return null; }).when(pool).close();
        }
        public void close() { receipts.close(); pools.close(); }
    }
}
