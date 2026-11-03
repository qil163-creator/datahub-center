package cn.iocoder.yudao.framework.dbswitch.data.query;

import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.DatabaseMetaData;
import java.sql.ResultSetMetaData;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class QueryRuntimeTest {
    @Test void completionCannotMissItsDeadlineWhenTheClockIsDelayedOrRewriteAlreadyConfirmedHistory() throws Exception {
        var clockEntered = new CountDownLatch(1); var releaseClock = new CountDownLatch(1);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var queries = runtime.queries();
            var clock = (java.util.concurrent.ScheduledExecutorService)
                    org.springframework.test.util.ReflectionTestUtils.getField(queries, "timer");
            assertNotNull(clock);
            clock.execute(() -> {
                clockEntered.countDown();
                try { releaseClock.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(clockEntered.await(2, TimeUnit.SECONDS));
            var budget = new OperationBudget(Duration.ofMillis(300), Duration.ofMillis(150), Duration.ofMillis(50), 20, 1000, 100, 10);
            var late = queries.register("tenant-clock", "late", UUID.randomUUID(), budget);
            var early = queries.register("tenant-clock", "early", UUID.randomUUID(), budget);
            long registeredAfter = System.nanoTime();
            early.bindWorker(); early.workerExited();
            assertEquals(QueryRuntime.Termination.CONFIRMED, early.termination());
            assertFalse(early.deadlineExpired());
            late.bindWorker();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                    .until(() -> System.nanoTime() - registeredAfter >= budget.total().toNanos());
            assertFalse(late.deadlineExpired(), "The fixture holds the periodic clock before its next tick");
            late.workerExited();
            assertTrue(late.deadlineExpired(), "Actual elapsed deadline must be observed before first confirmed completion");
            assertEquals(QueryRuntime.Termination.CONFIRMED, late.termination());
            assertEquals(0, queries.activeOperations());
            early.workerExited();
            assertEquals(QueryRuntime.Termination.CONFIRMED, early.termination());
            assertFalse(early.deadlineExpired(), "An already confirmed operation retains its historical outcome");
            // Models a clock iteration that captured its reference before registry removal.
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(early, "tick");
            assertFalse(early.deadlineExpired(), "A late clock tick cannot rewrite already confirmed history");
            assertFalse(early.cancellationRequested());
        } finally { releaseClock.countDown(); Thread.interrupted(); }
    }

    @Test void socketFallbackLeavesBoundedTimeForCancellationWithoutChangingTheAbsoluteBudget() throws Exception {
        var raw = mock(Connection.class); var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw); when(physical.hasExited()).thenReturn(true);
        var budget = new OperationBudget(Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(500), 20, 1000, 100, 10);
        try (var runtime = DbSwitchRuntime.standalone(); var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant", "socket-fallback", UUID.randomUUID(), budget);
            operation.bindWorker();
            try {
                operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                        "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD);
                var socketTimeout = org.mockito.ArgumentCaptor.forClass(Integer.class);
                verify(raw).setNetworkTimeout(any(), socketTimeout.capture());
                assertTrue(socketTimeout.getValue() > budget.total().toMillis(), "A socket timeout must not win the absolute cancellation deadline");
                assertTrue(socketTimeout.getValue() <= budget.total().plus(budget.cleanupWait()).plusSeconds(2).toMillis(),
                        "Cleanup grace and second-level JDBC quantization must remain bounded");
                assertEquals(Duration.ofSeconds(2), budget.total());
            } finally { operation.workerExited(); }
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
        } finally { Thread.interrupted(); }
    }

    @Test void statementCancellationCompletesBeforeConnectionAbortCanInvalidateItsDriver() throws Exception {
        var raw = mock(Connection.class); var statement = mock(Statement.class);
        var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        var physicallyClosed = new AtomicBoolean(); var cancelReturned = new AtomicBoolean();
        var abortedBeforeCancelReturned = new AtomicBoolean();
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw);
        when(raw.createStatement()).thenReturn(statement); when(physical.hasExited()).thenAnswer(call -> physicallyClosed.get());
        var cancelEntered = new CountDownLatch(1); var releaseCancel = new CountDownLatch(1); var aborted = new CountDownLatch(1);
        var workerBound = new CountDownLatch(1); var releaseWorker = new CountDownLatch(1);
        doAnswer(call -> { cancelEntered.countDown(); releaseCancel.await(); cancelReturned.set(true); return null; }).when(statement).cancel();
        doAnswer(call -> { abortedBeforeCancelReturned.set(!cancelReturned.get()); aborted.countDown(); return null; }).when(raw).abort(any());
        doAnswer(call -> { physicallyClosed.set(true); return null; }).when(pool).close();
        var budget = new OperationBudget(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 20, 1000, 100, 10);
        try (var runtime = DbSwitchRuntime.standalone(); var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
             var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant", "cancel-before-abort", UUID.randomUUID(), budget);
            var session = operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                    "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD);
            session.connection().createStatement();
            var worker = workers.submit(() -> {
                try {
                    operation.bindWorker(); workerBound.countDown();
                    while (releaseWorker.getCount() != 0) {
                        try { releaseWorker.await(); } catch (InterruptedException ignored) { }
                    }
                } catch (SQLException unexpected) { throw new AssertionError(unexpected); }
                finally { operation.workerExited(); Thread.interrupted(); }
            });
            assertTrue(workerBound.await(2, TimeUnit.SECONDS));
            operation.requestCancel("tenant");
            try {
                assertTrue(cancelEntered.await(2, TimeUnit.SECONDS));
                assertFalse(aborted.await(100, TimeUnit.MILLISECONDS), "Abort must not invalidate a cancellation still sending its request");
            } finally { releaseCancel.countDown(); releaseWorker.countDown(); worker.get(2, TimeUnit.SECONDS); }
            assertTrue(aborted.await(2, TimeUnit.SECONDS));
            assertFalse(abortedBeforeCancelReturned.get());
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
        } finally { releaseCancel.countDown(); releaseWorker.countDown(); Thread.interrupted(); }
    }

    @Test void blockedCancellerEscalatesToAbortWithinItsBudgetButCannotPretendTheControlWorkerExited() throws Exception {
        var raw = mock(Connection.class); var statement = mock(Statement.class);
        var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        var physicallyClosed = new AtomicBoolean();
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw);
        when(raw.createStatement()).thenReturn(statement); when(physical.hasExited()).thenAnswer(call -> physicallyClosed.get());
        var entered = new CountDownLatch(1); var releaseWorker = new CountDownLatch(1);
        var cancelEntered = new CountDownLatch(1); var releaseCancel = new CountDownLatch(1); var aborted = new CountDownLatch(1);
        doAnswer(call -> {
            cancelEntered.countDown();
            while (releaseCancel.getCount() != 0) {
                try { releaseCancel.await(); } catch (InterruptedException ignored) { }
            }
            return null;
        }).when(statement).cancel();
        doAnswer(call -> { aborted.countDown(); releaseWorker.countDown(); return null; }).when(raw).abort(any());
        when(statement.executeQuery("SELECT blocked_fixture")).thenAnswer(call -> {
            entered.countDown();
            while (releaseWorker.getCount() != 0) {
                try { releaseWorker.await(); } catch (InterruptedException ignored) { }
            }
            throw new SQLException("fixture cancelled", "57014");
        });
        doAnswer(call -> { physicallyClosed.set(true); return null; }).when(pool).close();
        var budget = new OperationBudget(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(80), 20, 1000, 100, 10);
        try (var runtime = DbSwitchRuntime.standalone(); var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
             var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant", "blocked-canceller", UUID.randomUUID(), budget);
            var session = operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                    "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD);
            var worker = workers.submit(() -> {
                try {
                    operation.bindWorker();
                    try { session.connection().createStatement().executeQuery("SELECT blocked_fixture"); }
                    catch (SQLException expected) { }
                } catch (SQLException unexpected) { throw new AssertionError(unexpected); }
                finally { session.close(); operation.workerExited(); Thread.interrupted(); }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                operation.requestCancel("tenant");
                assertTrue(cancelEntered.await(2, TimeUnit.SECONDS)); assertTrue(aborted.await(2, TimeUnit.SECONDS));
                worker.get(2, TimeUnit.SECONDS);
                assertEquals(QueryRuntime.Termination.UNCONFIRMED, operation.awaitTermination());
                assertEquals(1, runtime.queries().activeOperations());
                verify(raw, never()).close(); verify(pool, never()).close();
            } finally { releaseCancel.countDown(); releaseWorker.countDown(); }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() ->
                    operation.termination() == QueryRuntime.Termination.CONFIRMED);
            verify(raw, times(1)).close(); verify(pool, times(1)).close();
            assertEquals(0, runtime.queries().activeOperations());
        } finally { releaseCancel.countDown(); releaseWorker.countDown(); Thread.interrupted(); }
    }

    @Test void cancellationKeepsTheBorrowedConnectionUntilTheJdbcWorkerActuallyExits() throws Exception {
        var raw = mock(Connection.class); var statement = mock(Statement.class);
        var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        var physicallyClosed = new AtomicBoolean();
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw);
        when(raw.createStatement()).thenReturn(statement); when(physical.hasExited()).thenAnswer(call -> physicallyClosed.get());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1); var aborted = new CountDownLatch(1);
        doAnswer(call -> { cancelled.countDown(); return null; }).when(statement).cancel();
        doAnswer(call -> { aborted.countDown(); return null; }).when(raw).abort(any());
        when(statement.executeQuery("SELECT blocked_fixture")).thenAnswer(call -> {
            entered.countDown();
            // Models a driver that acknowledges cancel/abort but has not returned from its call.
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            throw new SQLException("fixture cancelled", "57014");
        });
        doAnswer(call -> { physicallyClosed.set(true); return null; }).when(pool).close();
        var budget = new OperationBudget(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(40), 20, 1000, 100, 10);
        try (var runtime = DbSwitchRuntime.standalone(); var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
             var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant", "late-jdbc-exit", UUID.randomUUID(), budget);
            var session = operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                    "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD);
            var worker = workers.submit(() -> {
                try {
                    operation.bindWorker();
                    try { session.connection().createStatement().executeQuery("SELECT blocked_fixture"); }
                    catch (SQLException expected) { }
                } catch (SQLException unexpected) { throw new AssertionError(unexpected); }
                finally { session.close(); operation.workerExited(); Thread.interrupted(); }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(QueryRuntime.CancelResult.REQUESTED, operation.requestCancel("tenant"));
                assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertTrue(aborted.await(2, TimeUnit.SECONDS));
                assertEquals(QueryRuntime.Termination.UNCONFIRMED, operation.awaitTermination());
                assertEquals(1, runtime.queries().activeOperations());
                verify(raw, never()).close(); verify(pool, never()).close();
            } finally { release.countDown(); }
            worker.get(2, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() ->
                    operation.termination() == QueryRuntime.Termination.CONFIRMED);
            verify(raw, times(1)).close(); verify(pool, times(1)).close();
            assertEquals(0, runtime.queries().activeOperations());
        } finally { release.countDown(); Thread.interrupted(); }
    }

    @Test void saturatedControlQueuesRetryAfterCapacityReturnsWithoutCallerRunsOrPrematureRelease() throws Exception {
        var raw = mock(Connection.class); var statement = mock(Statement.class);
        var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        var physicallyClosed = new AtomicBoolean();
        var cancellationThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var cleanupThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw);
        when(raw.createStatement()).thenReturn(statement); when(physical.hasExited()).thenAnswer(call -> physicallyClosed.get());
        doAnswer(call -> { cancellationThread.set(Thread.currentThread()); return null; }).when(statement).cancel();
        doAnswer(call -> { cleanupThread.set(Thread.currentThread()); physicallyClosed.set(true); return null; }).when(pool).close();
        var releaseControls = new CountDownLatch(1); var controlsStarted = new CountDownLatch(8);
        try (var runtime = DbSwitchRuntime.standalone(); var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var queries = runtime.queries();
            for (String field : new String[]{"cancellation", "cleanup"}) {
                var executor = (java.util.concurrent.ThreadPoolExecutor)
                        org.springframework.test.util.ReflectionTestUtils.getField(queries, field);
                assertNotNull(executor);
                for (int i = 0; i < 4; i++) executor.execute(() -> {
                    controlsStarted.countDown();
                    try { releaseControls.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                });
                for (int i = 0; i < 128; i++) executor.execute(() -> { });
            }
            assertTrue(controlsStarted.await(2, TimeUnit.SECONDS));
            var budget = new OperationBudget(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(20), 20, 1000, 100, 10);
            var operation = queries.register("tenant-a", "rejected-cleanup", UUID.randomUUID(), budget);
            operation.bindWorker();
            var session = operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                    "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD);
            session.connection().createStatement();
            operation.requestCancel("tenant-a"); operation.workerExited();
            assertEquals(QueryRuntime.Termination.UNCONFIRMED, operation.awaitTermination());
            assertEquals(1, queries.activeOperations());
            verify(statement, never()).cancel(); verify(raw, never()).close(); verify(pool, never()).close();
            releaseControls.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
                    operation.termination() == QueryRuntime.Termination.CONFIRMED && cancellationThread.get() != null);
            assertEquals(0, queries.activeOperations());
            verify(statement, times(1)).cancel(); verify(raw, times(1)).close(); verify(pool, times(1)).close();
            assertNotSame(Thread.currentThread(), cancellationThread.get());
            assertNotSame(Thread.currentThread(), cleanupThread.get());
        } finally { releaseControls.countDown(); Thread.interrupted(); }
    }

    @Test void oneQuotaOwnerCannotConsumeAllCapacityOrReleaseSlotsBeforeTermination() {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var queries = runtime.queries();
            var first = queries.register("tenant-a:record-1", "tenant-a", "1", UUID.randomUUID(), OperationBudget.defaults());
            var second = queries.register("tenant-a:record-2", "tenant-a", "2", UUID.randomUUID(), OperationBudget.defaults());
            assertThrows(IllegalStateException.class, () -> queries.register("tenant-a:record-3", "tenant-a", "3",
                    UUID.randomUUID(), OperationBudget.defaults()));
            first.requestCancel("tenant-a:record-1");
            assertThrows(IllegalStateException.class, () -> queries.register("tenant-a:record-3", "tenant-a", "3",
                    UUID.randomUUID(), OperationBudget.defaults()));
            var other = queries.register("tenant-b:record-1", "tenant-b", "1", UUID.randomUUID(), OperationBudget.defaults());
            assertEquals(3, queries.activeOperations());
            first.workerExited();
            var replacement = queries.register("tenant-a:record-3", "tenant-a", "3", UUID.randomUUID(), OperationBudget.defaults());
            first.workerExited();
            assertThrows(IllegalStateException.class, () -> queries.register("tenant-a:record-4", "tenant-a", "4",
                    UUID.randomUUID(), OperationBudget.defaults()));
            second.workerExited(); other.workerExited(); replacement.workerExited();
            assertEquals(0, queries.activeOperations());
            assertEquals(3, queries.metrics().rejectedAdmissions());
            assertEquals(1, queries.metrics().snapshot(QueryMetrics.Phase.CANCEL_TO_EXIT, QueryMetrics.Outcome.SUCCESS).count());
        }
    }

    @Test void queryStagesCountFailuresWithoutSensitiveTags() throws Exception {
        var raw = mock(Connection.class); var statement = mock(Statement.class);
        var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw);
        when(physical.hasExited()).thenReturn(true); when(raw.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("fixture_secret", "08006"));
        try (var runtime = DbSwitchRuntime.standalone(); var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant-a", "metrics", UUID.randomUUID(), OperationBudget.defaults());
            operation.bindWorker();
            try (var session = operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                    "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD)) {
                assertThrows(SQLException.class, () -> session.connection().createStatement().executeQuery("SELECT 1"));
            }
            operation.workerExited(); assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
            var metrics = runtime.queries().metrics();
            assertEquals(1, metrics.snapshot(QueryMetrics.Phase.QUEUE, QueryMetrics.Outcome.SUCCESS).count());
            assertEquals(1, metrics.snapshot(QueryMetrics.Phase.CONNECT, QueryMetrics.Outcome.SUCCESS).count());
            assertEquals(1, metrics.snapshot(QueryMetrics.Phase.EXECUTE, QueryMetrics.Outcome.FAILURE).count());
            assertEquals(1, metrics.snapshot(QueryMetrics.Phase.CLEANUP, QueryMetrics.Outcome.SUCCESS).count());
        }
    }

    @Test void cancelBeforeWorkerRegistrationCannotBeLostOrPretendToHaveTerminated() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var operation = runtime.queries().register("tenant-a", "record-1", UUID.randomUUID(), OperationBudget.defaults());
            assertEquals(QueryRuntime.CancelResult.REQUESTED, operation.requestCancel("tenant-a"));
            assertEquals(QueryRuntime.Termination.RUNNING, operation.termination());
            assertThrows(SQLException.class, operation::bindWorker);
            operation.workerExited();
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
            assertEquals(0, runtime.queries().activeOperations());
        } finally { Thread.interrupted(); }
    }

    @Test void authorizationAndRegistrationGenerationPreventCrossScopeAndOldFinallyRemoval() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var first = runtime.queries().register("tenant-a", "same-record", UUID.randomUUID(), OperationBudget.defaults());
            assertThrows(SecurityException.class, () -> first.requestCancel("tenant-b"));
            assertFalse(first.cancellationRequested());
            assertThrows(IllegalStateException.class, () -> runtime.queries().register("tenant-a", "same-record", UUID.randomUUID(), OperationBudget.defaults()));
            first.workerExited();
            var second = runtime.queries().register("tenant-a", "same-record", UUID.randomUUID(), OperationBudget.defaults());
            first.workerExited();
            assertEquals(1, runtime.queries().activeOperations());
            assertEquals(QueryRuntime.Termination.RUNNING, second.termination());
            second.workerExited();
        }
    }

    @Test void blockedCloseAndPhysicalExitEvidenceKeepTheOperationUnconfirmed() throws Exception {
        var connection = mock(Connection.class); var pool = mock(HikariDataSource.class);
        var physical = mock(InvisibleDataSource.class); var exited = new AtomicBoolean();
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(connection);
        when(physical.hasExited()).thenAnswer(call -> exited.get());
        var closeEntered = new CountDownLatch(1); var releaseClose = new CountDownLatch(1);
        doAnswer(call -> { closeEntered.countDown(); releaseClose.await(); exited.set(true); return null; }).when(pool).close();
        var budget = new OperationBudget(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(100), 20, 1000, 100, 10);
        try (var runtime = DbSwitchRuntime.standalone(); var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant-a", "record", UUID.randomUUID(), budget);
            operation.bindWorker();
            operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL, "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD);
            operation.workerExited();
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            assertEquals(QueryRuntime.Termination.UNCONFIRMED, operation.awaitTermination());
            assertEquals(1, runtime.queries().activeOperations());
            releaseClose.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> operation.termination() == QueryRuntime.Termination.CONFIRMED);
            assertEquals(0, runtime.queries().activeOperations());
        } finally { releaseClose.countDown(); Thread.interrupted(); }
    }

    @Test void budgetExpiryDoesNotConfirmAWorkerThatHasNotExited() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var budget = new OperationBudget(Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofMillis(20), 20, 1000, 100, 10);
            var operation = runtime.queries().register("tenant-a", "record", UUID.randomUUID(), budget);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(operation::deadlineExpired);
            assertEquals(QueryRuntime.Termination.UNCONFIRMED, operation.awaitTermination());
            assertThrows(java.sql.SQLTimeoutException.class, operation::bindWorker);
            operation.workerExited();
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
        } finally { Thread.interrupted(); }
    }

    public interface VendorApi { }

    @Test void publicJdbcFacadesCannotUnwrapNativeOwnersOrReturnUnboundedStreams() throws Exception {
        var raw = mock(Connection.class, withSettings().extraInterfaces(VendorApi.class));
        var statement = mock(Statement.class, withSettings().extraInterfaces(VendorApi.class));
        var rows = mock(ResultSet.class, withSettings().extraInterfaces(VendorApi.class));
        var metadata = mock(DatabaseMetaData.class, withSettings().extraInterfaces(VendorApi.class));
        var columns = mock(ResultSetMetaData.class, withSettings().extraInterfaces(VendorApi.class));
        var pool = mock(HikariDataSource.class); var physical = mock(InvisibleDataSource.class);
        when(pool.getDataSource()).thenReturn(physical); when(pool.getConnection()).thenReturn(raw);
        when(physical.hasExited()).thenReturn(true);
        when(raw.createStatement()).thenReturn(statement); when(raw.getMetaData()).thenReturn(metadata);
        when(statement.executeQuery("SELECT fixture")).thenReturn(rows);
        when(rows.getStatement()).thenReturn(statement); when(rows.getMetaData()).thenReturn(columns);
        var vendorValue = new org.postgresql.util.PGobject(); vendorValue.setType("jsonb"); vendorValue.setValue("{\"n\":1}");
        when(rows.getObject(1)).thenReturn(vendorValue);
        var stream = mock(java.io.InputStream.class); when(rows.getObject(2)).thenReturn(stream);
        try (var runtime = DbSwitchRuntime.standalone(); var factory = mockStatic(DriverPoolFactory.class)) {
            factory.when(() -> DriverPoolFactory.create(any(), any(), any(), any())).thenReturn(pool);
            var operation = runtime.queries().register("tenant", "facades", UUID.randomUUID(), OperationBudget.defaults());
            operation.bindWorker();
            try (var session = operation.openSession(new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL,
                    "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD)) {
                Connection bounded = session.connection();
                assertSame(bounded, bounded.unwrap(Connection.class));
                assertFalse(bounded.isWrapperFor(VendorApi.class));
                assertThrows(SQLFeatureNotSupportedException.class, () -> bounded.unwrap(VendorApi.class));
                assertThrows(SQLFeatureNotSupportedException.class, () -> bounded.unwrap(
                        cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.ConnectionAccess.class));
                assertThrows(SQLFeatureNotSupportedException.class, () -> bounded.setReadOnly(false));
                assertThrows(SQLFeatureNotSupportedException.class, bounded::commit);
                var boundedStatement = bounded.createStatement();
                assertThrows(SQLFeatureNotSupportedException.class, () -> boundedStatement.unwrap(VendorApi.class));
                assertSame(bounded, boundedStatement.getConnection());
                try (var boundedRows = boundedStatement.executeQuery("SELECT fixture")) {
                    assertThrows(SQLFeatureNotSupportedException.class, () -> boundedRows.unwrap(VendorApi.class));
                    assertNotSame(statement, boundedRows.getStatement());
                    assertSame(bounded, boundedRows.getStatement().getConnection());
                    assertThrows(SQLFeatureNotSupportedException.class, () -> boundedRows.getBinaryStream(1));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> boundedRows.getCharacterStream(1));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> boundedRows.getBlob(1));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> boundedRows.getArray(1));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> boundedRows.getMetaData().unwrap(VendorApi.class));
                    assertEquals("{\"n\":1}", boundedRows.getObject(1));
                    assertThrows(SQLException.class, () -> boundedRows.getObject(2));
                    verify(stream).close();
                }
                assertSame(bounded, bounded.getMetaData().getConnection());
                assertThrows(SQLFeatureNotSupportedException.class, () -> bounded.getMetaData().unwrap(VendorApi.class));
                bounded.close(); verify(raw, never()).close();
                verify(raw, never()).unwrap(any()); verify(statement, never()).unwrap(any());
                verify(rows, never()).unwrap(any()); verify(rows, never()).getBinaryStream(anyInt());
                verify(rows, never()).getCharacterStream(anyInt()); verify(rows, never()).getBlob(anyInt());
            } finally { operation.workerExited(); }
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
        } finally { Thread.interrupted(); }
    }

    @Test void aConfirmedOperationCannotReopenJdbcResources() {
        try (var runtime = DbSwitchRuntime.standalone(); var factory = mockStatic(DriverPoolFactory.class)) {
            var operation = runtime.queries().register("tenant", "ended", UUID.randomUUID(), OperationBudget.defaults());
            operation.workerExited();
            assertThrows(SQLException.class, () -> operation.openSession(new QueryRuntime.ConnectionSpec(
                    ProductTypeEnum.POSTGRESQL, "jdbc:fixture:test", null, null, null, "v1"), ConnectionBudget.Role.WORKLOAD));
            factory.verifyNoInteractions();
        }
    }
}
