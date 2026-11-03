package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DataAccessExecutionProtocolTest {
    @AfterEach void cleanup() { TenantContextHolder.clear(); Thread.interrupted(); }

    @Test void localCapacityUsesSharedConfigurationIncludingZeroQueue() {
        var properties = new RunExecutionProperties();
        properties.setWorkerConcurrency(2); properties.setWorkerQueue(0);
        var executor = new DataAccessExecutor();
        ReflectionTestUtils.setField(executor, "properties", properties);
        executor.init();
        try {
            var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(executor, "executor");
            assertEquals(2, pool.getMaximumPoolSize());
            assertInstanceOf(SynchronousQueue.class, pool.getQueue());
            assertEquals(2, ((Semaphore) ReflectionTestUtils.getField(executor, "capacity")).availablePermits());
        } finally { executor.destroy(); }
    }

    @Test void confirmedRemoteCancellationRetainsStoppedStatusAndPartialEffect() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        when(coordinator.startEffects(any(), any())).thenReturn(true);
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            remote.when(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString())).thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\"}"));
            remote.when(() -> SeatunnelRestApi.getJobInfo("remote-31")).thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\",\"jobStatus\":\"CANCELED\"}"));
            executor.submit(31L);
            verify(coordinator).finish(any(), argThat(result -> result.getRunStatus() == 6), eq(true), eq(RunProtocol.Effect.PARTIAL));
        }
    }

    @Test void clearedExecutionTokenCannotLetOldMonitorInterruptTerminalPersistence() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        when(coordinator.startEffects(any(), any())).thenReturn(true);
        when(coordinator.finish(any(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            when(coordinator.shouldStop(any())).thenReturn(true);
            ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns");
            assertFalse(Thread.currentThread().isInterrupted(), "execution handle must detach before terminal persistence");
            return true;
        });
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            remote.when(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString())).thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\"}"));
            remote.when(() -> SeatunnelRestApi.getJobInfo("remote-31")).thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\",\"jobStatus\":\"FINISHED\"}"));
            executor.submit(31L);
        }
    }

    @Test void expiredPreparationCannotSubmitRemoteEffects() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        when(coordinator.startEffects(any(), any())).thenReturn(false);
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(executor, "submit", 31L)));
            remote.verifyNoInteractions();
        }
    }

    @Test void invalidNativeJsonFailsPreparationWithoutRemoteEffects() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        setNativeConfig(coordinator, "json", "{invalid-native-fixture");
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            assertTrue(executor.submit(31L));
            remote.verifyNoInteractions();
            verify(coordinator, never()).startEffects(any(), any());
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 4
                    && "PREPARE".equals(run.getExecutionStage())
                    && !run.getErrorInfo().contains("invalid-native-fixture")
                    && !run.getRealTimeLog().contains("invalid-native-fixture")), eq(true), eq(RunProtocol.Effect.NONE));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"json", "hocon"})
    void nativeJobContentAndFormatReachRemoteWithoutRendering(String format) {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        String original = format.equals("json")
                ? "{\"env\": {\"job.name\":\"original\"}, \"source\":[{\"plugin_name\":\"Kafka\"}], \"sink\":[{\"plugin_name\":\"Console\"}]}"
                : "# 用户原文\nenv { job.name = original }\nsource { Kafka {} }\nsink { Console {} }\n";
        setNativeConfig(coordinator, format, original);
        when(coordinator.startEffects(any(), eq(RunProtocol.Stage.SUBMIT_SENT))).thenReturn(true);
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            remote.when(() -> SeatunnelRestApi.submitJob(original, format, "access-31"))
                    .thenAnswer(inv -> {
                        verify(coordinator).startEffects(any(), eq(RunProtocol.Stage.SUBMIT_SENT));
                        return JSONUtil.parseObj("{\"jobId\":\"remote-31\"}");
                    });
            remote.when(() -> SeatunnelRestApi.getJobInfo("remote-31"))
                    .thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\",\"jobStatus\":\"FINISHED\"}"));
            assertTrue(executor.submit(31L));
            remote.verify(() -> SeatunnelRestApi.submitJob(original, format, "access-31"));
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 3
                    && original.equals(AccessRunSnapshot.restore(run.getConfigJson()).getSeatunnelConfig())
                    && !run.getRealTimeLog().contains(original)), eq(true), eq(RunProtocol.Effect.COMPLETE));
        }
    }

    private void setNativeConfig(AccessRunCoordinator coordinator, String format, String original) {
        var run = coordinator.run(31L);
        var snapshot = JSONUtil.parseObj(run.getConfigJson());
        snapshot.set("seatunnelConfig", original); snapshot.set("configFormat", format);
        run.setConfigJson(snapshot.toString());
    }

    @Test void dbswitchDriverFailureCannotPersistConnectionSecrets() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        var mapper = mock(cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskDetailMapper.class);
        ReflectionTestUtils.setField(executor, "taskDetailMapper", mapper);
        var result = new cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult();
        result.getIfSuccess().set(false); result.setSourceTableName("orders"); result.setTargetTableName("orders");
        result.setErrorMsg("driver connection failed jdbc:fixture password=fixture-secret");
        var log = new StringBuffer();
        ReflectionTestUtils.invokeMethod(executor, "handleTableResult", DataAccessDO.builder().id(10L).build(),
                coordinator.run(31L), result, log,
                new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong(),
                new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong());
        var detail = org.mockito.ArgumentCaptor.forClass(DataAccessTaskDetailDO.class);
        verify(mapper).insert(detail.capture());
        assertFalse(detail.getValue().getErrorMsg().contains("fixture-secret"));
        assertFalse(log.toString().contains("fixture-secret"));
    }

    @Test void remoteResponseLossPersistsUnknownAndNeverBlindlyResubmits() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        when(coordinator.startEffects(any(), any())).thenReturn(true);
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            remote.when(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("response lost"));
            ReflectionTestUtils.invokeMethod(executor, "submit", 31L);
            verify(coordinator).finish(any(), argThat(result -> result.getRunStatus() == 5), eq(false), eq(RunProtocol.Effect.UNKNOWN));
            remote.verify(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString()), times(1));
            when(coordinator.claim(eq(31L), anyString())).thenReturn(Optional.empty());
            ReflectionTestUtils.invokeMethod(executor, "submit", 31L);
            remote.verify(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString()), times(1));
        }
    }

    @Test void persistedRemoteJobIdentityIsQueriedWithoutSubmittingAgain() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        var run = coordinator.run(31L); run.setSeatunnelJobId("remote-31"); run.setExecutionStage("SUBMIT_SENT");
        when(coordinator.startEffects(any(), any())).thenReturn(true);
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            remote.when(() -> SeatunnelRestApi.getJobInfo("remote-31")).thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\",\"jobStatus\":\"FINISHED\"}"));
            ReflectionTestUtils.invokeMethod(executor, "submit", 31L);
            remote.verify(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString()), never());
            remote.verify(() -> SeatunnelRestApi.getJobInfo("remote-31"), times(1));
        }
    }

    @Test void revokedAuthorizationRequestsRemoteStopAndRetainsUnknownUntilReconciled() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = fixture(coordinator);
        var authorization = (AccessRunAuthorization) ReflectionTestUtils.getField(executor, "authorization");
        doNothing().doThrow(new IllegalStateException("permission revoked"))
                .when(authorization).execution(any(), eq(99L));
        when(coordinator.startEffects(any(), any())).thenReturn(true);
        try (var remote = mockStatic(SeatunnelRestApi.class)) {
            remote.when(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString()))
                    .thenReturn(JSONUtil.parseObj("{\"jobId\":\"remote-31\"}"));
            executor.submit(31L);
            remote.verify(() -> SeatunnelRestApi.stopJob(argThat(body -> body.contains("remote-31"))));
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 5), eq(false), eq(RunProtocol.Effect.UNKNOWN));
            remote.verify(() -> SeatunnelRestApi.submitJob(anyString(), anyString(), anyString()), times(1));
        }
    }

    @Test void dbswitchEntryPersistsActualBindingBeforeEngineAndKeepsCompletionManifest() {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = dbswitchFixture(coordinator);
        var stored = new java.util.concurrent.atomic.AtomicBoolean();
        when(coordinator.recordSyncBinding(any(), any())).thenAnswer(inv -> { stored.set(true); return true; });
        try (var engine = mockConstruction(MigrationService.class, (mock, context) -> {
            assertTrue(stored.get(), "实际绑定必须先于引擎实例化落库");
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.CONFIRMED);
        })) {
            assertTrue(executor.submit(31L)); assertEquals(1, engine.constructed().size());
            var binding = org.mockito.ArgumentCaptor.forClass(cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO.class);
            verify(coordinator).recordSyncBinding(any(), binding.capture());
            assertEquals("2026-09-25T09:01:02.123456", binding.getValue().targetRevision());
            verify(coordinator).finish(any(), argThat(result -> result.getRunStatus() == 3
                    && cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(result.getSyncEvidence(),
                        cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO.class).manifestComplete()),
                    eq(true), eq(RunProtocol.Effect.COMPLETE));
        }
    }

    @Test void rejectedBindingCannotConstructOrStartAnEngine() {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        when(coordinator.recordSyncBinding(any(), any())).thenReturn(false);
        try (var engine = mockConstruction(MigrationService.class)) {
            executor.submit(31L); assertTrue(engine.constructed().isEmpty());
            verify(coordinator).finish(any(), argThat(run -> run.getSyncEvidence() == null && run.getRunStatus() == 5),
                    eq(false), eq(RunProtocol.Effect.UNKNOWN));
        }
    }

    @Test void confirmedEngineWithPendingRecoveryKeepsUnknownAndItsSlot() {
        verifyRecoveryCompletion(false, false);
    }

    @Test void recoveryControlFailureStillPersistsUnknownRunTermination() {
        verifyRecoveryCompletion(false, true);
    }

    @Test void authenticatedRecoveryCompletionCanReleaseConfirmedEngine() {
        verifyRecoveryCompletion(true, false);
    }

    private void verifyRecoveryCompletion(boolean released, boolean unavailable) {
        var coordinator = mock(AccessRunCoordinator.class);
        var executor = dbswitchFixture(coordinator);
        when(coordinator.recordSyncBinding(any(), any())).thenReturn(true);
        var recovery = (AccessGroupRewriteCoordinator) ReflectionTestUtils.getField(executor, "groupRewriteCoordinator");
        var handle = mock(AccessGroupRewriteCoordinator.Handle.class);
        when(recovery.prepare(any(), any(), any(), any(), any(), any(), any())).thenReturn(handle);
        if (unavailable) when(handle.finished(true)).thenThrow(new IllegalStateException("private-control-secret"));
        else when(handle.finished(true)).thenReturn(released);
        try (var engine = mockConstruction(MigrationService.class, (mock, context) -> {
            var request = (cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest) context.arguments().getFirst();
            assertSame(handle, request.getTarget().getGroupRewriteBindingProvider());
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.CONFIRMED);
        })) {
            assertTrue(executor.submit(31L)); assertEquals(1, engine.constructed().size());
            var order = inOrder(handle, coordinator);
            order.verify(handle).finished(true);
            order.verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == (released ? 3 : 5)
                    && (run.getErrorInfo() == null || !run.getErrorInfo().contains("private-control-secret"))),
                    eq(true), eq(released ? RunProtocol.Effect.COMPLETE : RunProtocol.Effect.UNKNOWN));
        }
    }

    @Test void lateEngineExitKeepsTheDrainOwnerAndLocalSlotUntilTheMonitorSignsTheDrain() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); var exit = new CompletableFuture<Void>(); int initial = permits(executor);
        try (var engine = mockConstruction(MigrationService.class, receipt(exit), (mock, context) -> {
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            verify(handle).finished(false);
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 5), eq(false), eq(RunProtocol.Effect.UNKNOWN));
            assertEquals(1, running(executor).size(), "the only owner able to sign the drain is retained");
            assertEquals(initial - 1, permits(executor), "an unproven exit keeps its bounded local slot");
            assertFalse(executor.submit(31L), "the retained placeholder rejects a second local dispatch of the same run");
            verify(coordinator).releaseRejected(any()); assertEquals(initial - 1, permits(executor));
            monitor(executor);
            verify(handle, never()).finished(true);
            exit.complete(null); // Completing the engine receipt itself performs no platform I/O.
            verify(handle, never()).finished(true);
            when(handle.finished(true)).thenReturn(true);
            monitor(executor);
            verify(handle).finished(true); assertTrue(running(executor).isEmpty()); assertEquals(initial, permits(executor));
            monitor(executor);
            verify(handle, times(1)).finished(true); assertEquals(initial, permits(executor));
            assertEquals(1, engine.constructed().size()); verify(engine.constructed().getFirst(), times(1)).run();
            verify(coordinator, times(1)).finish(any(), any(), anyBoolean(), any());
        }
    }

    @Test void controlOutageAfterConfirmedExitRetriesOnlyTheDrainSignature() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); int initial = permits(executor);
        when(handle.finished(true)).thenThrow(new IllegalStateException("private-control-secret")).thenReturn(true);
        try (var engine = mockConstruction(MigrationService.class, receipt(CompletableFuture.completedFuture(null)), (mock, context) -> {
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.CONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 5
                    && (run.getErrorInfo() == null || !run.getErrorInfo().contains("private-control-secret"))), eq(true), eq(RunProtocol.Effect.UNKNOWN));
            assertEquals(1, running(executor).size()); assertEquals(initial - 1, permits(executor));
            monitor(executor);
            verify(handle, times(2)).finished(true); assertTrue(running(executor).isEmpty()); assertEquals(initial, permits(executor));
            verify(engine.constructed().getFirst(), times(1)).run();
            verify(coordinator, times(1)).finish(any(), any(), anyBoolean(), any());
        }
    }

    @Test void unknownWriteResultWithProvenPhysicalExitSignsTheDrainButKeepsTheRunUnknown() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); int initial = permits(executor);
        var drained = new java.util.concurrent.atomic.AtomicBoolean();
        when(handle.finished(true)).thenAnswer(inv -> { drained.set(true); return false; }); // Pending journal keeps the pins.
        when(handle.hasConfirmedExecutorDrain()).thenAnswer(inv -> drained.get());
        try (var engine = mockConstruction(MigrationService.class, receipt(CompletableFuture.completedFuture(null)), (mock, context) -> {
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            verify(handle).finished(true); verify(handle, never()).finished(false);
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 5), eq(false), eq(RunProtocol.Effect.UNKNOWN));
            assertTrue(running(executor).isEmpty()); assertEquals(initial, permits(executor));
        }
    }

    @Test void unprovableEngineExitNeverSignsTheDrainAndKeepsTheSlotForReview() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); var exit = new CompletableFuture<Void>(); int initial = permits(executor);
        when(handle.finished(true)).thenReturn(true);
        try (var engine = mockConstruction(MigrationService.class, receipt(exit), (mock, context) -> {
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            exit.completeExceptionally(new IllegalStateException("private-driver-secret"));
            monitor(executor); monitor(executor);
            verify(handle).finished(false); verify(handle, never()).finished(true);
            assertEquals(1, running(executor).size()); assertEquals(initial - 1, permits(executor));
        }
    }

    @Test void rejectedStaleSignatureIsRetriedWithoutTargetWorkAndKeepsTheSlot() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); var exit = new CompletableFuture<Void>(); int initial = permits(executor);
        when(handle.finished(true)).thenReturn(false); // A replaced owner token is rejected by the durable guard.
        try (var engine = mockConstruction(MigrationService.class, receipt(exit), (mock, context) -> {
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            exit.complete(null);
            monitor(executor); monitor(executor);
            verify(handle, times(2)).finished(true);
            assertEquals(1, running(executor).size()); assertEquals(initial - 1, permits(executor));
            verify(engine.constructed().getFirst(), times(1)).run();
        }
    }

    @Test void saturatedExecutorDefersTheDrainSignatureToTheNextMonitorTick() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); var exit = new CompletableFuture<Void>(); int initial = permits(executor);
        try (var engine = mockConstruction(MigrationService.class, receipt(exit), (mock, context) -> {
            when(mock.run()).thenReturn(AccessSyncEvidenceTest.success());
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            exit.complete(null); when(handle.finished(true)).thenReturn(true);
            var workers = (ExecutorService) ReflectionTestUtils.getField(executor, "executor");
            doThrow(new RejectedExecutionException()).when(workers).submit(any(Runnable.class));
            monitor(executor);
            verify(handle, never()).finished(true); assertEquals(initial - 1, permits(executor));
            doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return CompletableFuture.completedFuture(null); })
                    .when(workers).submit(any(Runnable.class));
            monitor(executor);
            verify(handle).finished(true); assertTrue(running(executor).isEmpty()); assertEquals(initial, permits(executor));
        }
    }

    @Test void engineThatNeverStartedLeavesNoExitToWaitFor() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var handle = groupHandle(executor, coordinator); int initial = permits(executor);
        when(coordinator.shouldStop(any())).thenReturn(false, true); // Ownership is lost after construction, before run().
        when(handle.finished(true)).thenReturn(true);
        try (var engine = mockConstruction(MigrationService.class, receipt(new CompletableFuture<>()))) {
            assertTrue(executor.submit(31L));
            assertEquals(1, engine.constructed().size()); verify(engine.constructed().getFirst(), never()).run();
            verify(handle).finished(true);
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 5), eq(false), eq(RunProtocol.Effect.UNKNOWN));
            assertTrue(running(executor).isEmpty()); assertEquals(initial, permits(executor));
        }
    }

    @Test void crashedEngineKeepsRunningBindingAndCannotInventCompletion() {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        when(coordinator.recordSyncBinding(any(), any())).thenReturn(true);
        try (var engine = mockConstruction(MigrationService.class, (mock, context) -> {
            when(mock.run()).thenThrow(new IllegalStateException("private-driver-secret"));
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            executor.submit(31L);
            assertEquals(1, engine.constructed().size());
            verify(coordinator).finish(any(), argThat(run -> run.getRunStatus() == 5
                    && run.getSyncEvidence().contains("RUNNING") && !run.getSyncEvidence().contains("private-driver-secret")),
                    eq(false), eq(RunProtocol.Effect.UNKNOWN));
        }
    }

    @Test void unconfirmedReturnedBatchCannotReleaseAsCompleteOrStartAnotherEngine() throws Exception {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        when(coordinator.recordSyncBinding(any(), any())).thenReturn(true);
        var result = AccessSyncEvidenceTest.success();
        result.setTermination(MigrationTermination.UNCONFIRMED);
        result.getIfAllSuccess().set(false); result.getTableResultList().getFirst().getIfSuccess().set(false);
        result.getTotalTableSuccessCount().set(0); result.getTotalTableFailCount().set(1);
        try (var engine = mockConstruction(MigrationService.class, (mock, context) -> {
            when(mock.run()).thenReturn(result);
            when(mock.termination()).thenReturn(MigrationTermination.UNCONFIRMED);
        })) {
            assertTrue(executor.submit(31L));
            verify(coordinator).finish(any(), argThat(run -> {
                var evidence = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(run.getSyncEvidence(),
                        cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO.class);
                return run.getRunStatus() == 5 && !evidence.manifestComplete()
                        && "UNCONFIRMED".equals(evidence.termination()) && !run.getSyncEvidence().contains("private-");
            }), eq(false), eq(RunProtocol.Effect.UNKNOWN));
            when(coordinator.claim(eq(31L), anyString())).thenReturn(Optional.empty());
            assertFalse(executor.submit(31L));
            assertEquals(1, engine.constructed().size()); verify(engine.constructed().getFirst(), times(1)).run();
        }
    }

    @Test void multiSourceCannotBorrowFirstSourceBinding() {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var run = coordinator.run(31L);
        run.setConfigJson(run.getConfigJson().replace("\"source\":[{}]", "\"source\":[{},{}]"));
        try (var engine = mockConstruction(MigrationService.class)) {
            assertTrue(executor.submit(31L)); assertTrue(engine.constructed().isEmpty());
            verify(coordinator, never()).startEffects(any(), any());
            verify(coordinator, never()).recordSyncBinding(any(), any());
            verify(coordinator).finish(any(), argThat(result -> result.getSyncEvidence() == null
                    && result.getRunStatus() == 4), eq(true), eq(RunProtocol.Effect.NONE));
        }
    }

    @Test void invalidSnapshotVersionFinishesWithoutEffectsOrLeakingLocalCapacity() {
        var coordinator = mock(AccessRunCoordinator.class); var executor = dbswitchFixture(coordinator);
        var run = coordinator.run(31L);
        run.setConfigJson(run.getConfigJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        var capacity = (Semaphore) ReflectionTestUtils.getField(executor, "capacity");
        int permits = capacity.availablePermits();
        try (var engine = mockConstruction(MigrationService.class)) {
            assertTrue(executor.submit(31L));
            assertTrue(engine.constructed().isEmpty());
            verify(coordinator, never()).startEffects(any(), any());
            verify(coordinator, never()).recordSyncBinding(any(), any());
            verify(coordinator).finish(any(), argThat(result -> result.getRunStatus() == 4
                    && result.getSyncEvidence() == null), eq(true), eq(RunProtocol.Effect.NONE));
            assertEquals(permits, capacity.availablePermits());
        }
    }

    private static AccessGroupRewriteCoordinator.Handle groupHandle(DataAccessExecutor executor, AccessRunCoordinator coordinator) {
        when(coordinator.recordSyncBinding(any(), any())).thenReturn(true);
        var groups = (AccessGroupRewriteCoordinator) ReflectionTestUtils.getField(executor, "groupRewriteCoordinator");
        var handle = mock(AccessGroupRewriteCoordinator.Handle.class);
        when(groups.prepare(any(), any(), any(), any(), any(), any(), any())).thenReturn(handle);
        return handle;
    }

    /** Serves the engine's physical-exit receipt by name, so the original executor can run the same RED assertions. */
    private static org.mockito.MockSettings receipt(CompletableFuture<Void> exit) {
        return withSettings().defaultAnswer(inv -> "physicalExit".equals(inv.getMethod().getName())
                ? exit.minimalCompletionStage() : RETURNS_DEFAULTS.answer(inv));
    }

    private static int permits(DataAccessExecutor executor) { return ((Semaphore) ReflectionTestUtils.getField(executor, "capacity")).availablePermits(); }
    private static java.util.Map<?, ?> running(DataAccessExecutor executor) { return (java.util.Map<?, ?>) ReflectionTestUtils.getField(executor, "runningTasks"); }
    private static void monitor(DataAccessExecutor executor) { ReflectionTestUtils.invokeMethod(executor, "monitorLocalRuns"); }

    private DataAccessExecutor dbswitchFixture(AccessRunCoordinator coordinator) {
        var executor = fixture(coordinator); var run = coordinator.run(31L);
        run.setAttemptCount(1);
        run.setConfigJson("{\"id\":10,\"creator\":\"99\",\"engineType\":1,\"sourceDatabaseId\":10,\"targetDatabaseId\":20,"
                + "\"dataAccessJson\":{\"schemaVersion\":1,\"source\":[{\"sourceTableNames\":[\"orders\"]}],"
                + "\"target\":{\"writeMode\":\"CREATE_NEW\",\"syncExist\":false}}}");
        var resolver = mock(AccessDatabaseResolver.class);
        when(resolver.resolve(eq(10L), any())).thenReturn(AccessSyncEvidenceTest.connection(10));
        when(resolver.resolve(eq(20L), any())).thenReturn(AccessSyncEvidenceTest.connection(20));
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "migrationAuthorization", mock(AccessMigrationAuthorization.class));
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime",
                mock(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class));
        var checkpoints = mock(AccessCursorCheckpointStore.class);
        when(checkpoints.prepare(any(), any(), any(), any(), any(), any(), any())).thenReturn(java.util.Map.of());
        ReflectionTestUtils.setField(executor, "cursorCheckpointStore", checkpoints);
        ReflectionTestUtils.setField(executor, "groupRewriteCoordinator", mock(AccessGroupRewriteCoordinator.class));
        when(coordinator.startEffects(any(), any())).thenReturn(true);
        return executor;
    }

    private DataAccessExecutor fixture(AccessRunCoordinator coordinator) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), DataAccessTaskDO.class);
        assertNotNull(ReflectionUtils.findMethod(DataAccessExecutor.class, "submit", Long.class), "executor must consume a durable record ID");
        TenantContextHolder.setTenantId(1L);
        var executor = new DataAccessExecutor();
        var lease = new RunLease(31L, "token-one", LocalDateTime.now().plusSeconds(90));
        var run = DataAccessTaskDO.builder().id(31L).dataAccessId(10L).protocolVersion(1).runStatus(1).runType(2)
                .executionStage("PREPARE").executionToken("token-one").executionUserId(99L)
                .configJson("{\"id\":10,\"engineType\":2,\"taskName\":\"frozen\",\"configFormat\":\"json\",\"seatunnelConfig\":\"{\\\"env\\\":{}}\"}").build();
        when(coordinator.run(31L)).thenReturn(run);
        when(coordinator.requireRun(31L)).thenReturn(run);
        when(coordinator.claim(eq(31L), anyString())).thenReturn(Optional.of(lease));
        when(coordinator.owns(any())).thenReturn(true);
        when(coordinator.writeOwned(any(), any())).thenAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return true; });
        ReflectionTestUtils.setField(executor, "coordinator", coordinator);
        ReflectionTestUtils.setField(executor, "authorization", mock(AccessRunAuthorization.class));
        ReflectionTestUtils.setField(executor, "properties", new RunExecutionProperties());
        ReflectionTestUtils.setField(executor, "taskMapper", mock(cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper.class));
        var pool = mock(ExecutorService.class);
        when(pool.submit(any(Runnable.class))).thenAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return CompletableFuture.completedFuture(null); });
        ReflectionTestUtils.setField(executor, "executor", pool);
        return executor;
    }
}
