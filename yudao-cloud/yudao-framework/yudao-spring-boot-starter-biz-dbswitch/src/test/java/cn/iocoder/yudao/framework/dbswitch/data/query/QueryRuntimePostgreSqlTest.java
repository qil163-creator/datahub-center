package cn.iocoder.yudao.framework.dbswitch.data.query;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QueryRuntimePostgreSqlTest {
    private QueryRuntime.ConnectionSpec spec() {
        return new QueryRuntime.ConnectionSpec(ProductTypeEnum.POSTGRESQL, System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "", null, "fixture-v1");
    }
    @Test void boundedCursorPreservesValuesAndRejectsCrossScopeAndForgedCursor() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var operation = runtime.queries().register("tenant-a", "record", UUID.randomUUID(), OperationBudget.defaults());
            operation.bindWorker();
            try (var session = operation.openSession(spec(), ConnectionBudget.Role.WORKLOAD);
                 var cursor = session.query("tenant-a", "SELECT 12345678901234567890.123456789::numeric AS precise, NULL::text AS missing", List.of())) {
                assertThrows(SecurityException.class, () -> cursor.fetch("tenant-b", cursor.id(), 1));
                assertThrows(SecurityException.class, () -> cursor.fetch("tenant-a", UUID.randomUUID(), 1));
                var page = cursor.fetch("tenant-a", cursor.id(), 1);
                assertEquals("precise", page.columns().getFirst().getLabelName());
                assertEquals(Types.NUMERIC, page.columns().getFirst().getFieldType());
                assertEquals(new BigDecimal("12345678901234567890.123456789"), page.rows().getFirst().getFirst());
                assertNull(page.rows().getFirst().get(1));
                assertTrue(cursor.fetch("tenant-a", cursor.id(), 1).exhausted());
            } finally { operation.workerExited(); }
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
            assertEquals(0, runtime.drivers().runtimes().connections().snapshot().active());
        } finally { Thread.interrupted(); }
    }

    @RepeatedTest(12) void cancelBlockedJdbcQueryWaitsForActualWorkerAndPhysicalConnectionExit() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone(); var workers = Executors.newSingleThreadExecutor()) {
            var operation = runtime.queries().register("tenant-a", "record", UUID.randomUUID(), OperationBudget.defaults());
            var backend = new CompletableFuture<Integer>(); var failure = new AtomicReference<Throwable>();
            Future<?> worker = workers.submit(() -> {
                try {
                    operation.bindWorker();
                    try (var session = operation.openSession(spec(), ConnectionBudget.Role.WORKLOAD)) {
                        try (var pid = session.query("tenant-a", "SELECT pg_backend_pid()", List.of())) {
                            backend.complete(((Number) pid.fetch("tenant-a", pid.id(), 1).rows().getFirst().getFirst()).intValue());
                        }
                        try (var blocked = session.query("tenant-a", "SELECT pg_sleep(20)", List.of())) {
                            blocked.fetch("tenant-a", blocked.id(), 1);
                        }
                    }
                } catch (Throwable error) { failure.set(error); backend.completeExceptionally(error); }
                finally { operation.workerExited(); Thread.interrupted(); }
            });
            int pid = backend.get(5, TimeUnit.SECONDS);
            try (var control = DriverManager.getConnection(spec().url(), spec().username(), spec().password())) {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
                    try (var statement = control.prepareStatement("SELECT wait_event FROM pg_stat_activity WHERE pid=?")) {
                        statement.setInt(1, pid);
                        try (var rows = statement.executeQuery()) { return rows.next() && "PgSleep".equals(rows.getString(1)); }
                    }
                });
                assertEquals(QueryRuntime.CancelResult.REQUESTED, operation.requestCancel("tenant-a"));
                worker.get(10, TimeUnit.SECONDS);
                assertNotNull(failure.get());
                assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination(),
                        () -> terminationEvidence(operation, runtime));
                assertEquals(0, runtime.drivers().runtimes().connections().snapshot().workload());
                // Client close has returned; server backend removal is observable asynchronously.
                // A still-running pg_sleep(20) must fail this bounded five-second confirmation.
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    try (var statement = control.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid=?")) {
                        statement.setQueryTimeout(2);
                        statement.setInt(1, pid);
                        try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
                    }
                });
            }
        } finally { Thread.interrupted(); }
    }

    private String terminationEvidence(QueryRuntime.Operation operation, DbSwitchRuntime runtime) {
        var evidence = new ArrayList<String>();
        evidence.add("workerExited=" + org.springframework.test.util.ReflectionTestUtils.getField(operation, "workerExited"));
        evidence.add("inFlight=" + org.springframework.test.util.ReflectionTestUtils.getField(operation, "inFlight"));
        @SuppressWarnings("unchecked")
        var sessions = (Set<QueryRuntime.Session>) org.springframework.test.util.ReflectionTestUtils.getField(operation, "sessions");
        for (var session : Objects.requireNonNull(sessions)) {
            var physical = (cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource)
                    org.springframework.test.util.ReflectionTestUtils.getField(session, "physical");
            evidence.add("closing=" + org.springframework.test.util.ReflectionTestUtils.getField(session, "closing")
                    + ",cleanupReturned=" + org.springframework.test.util.ReflectionTestUtils.getField(session, "cleanupReturned")
                    + ",physicalExited=" + Objects.requireNonNull(physical).hasExited());
        }
        evidence.add("connectionBudget=" + runtime.drivers().runtimes().connections().snapshot());
        return String.join("; ", evidence);
    }

    @Test void cancellationOfAnObservedTableLockWaitConfirmsWorkerAndServerExit() throws Exception {
        String schema = "query_lock_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(spec().url(), spec().username(), spec().password());
             var locker = DriverManager.getConnection(spec().url(), spec().username(), spec().password());
             var runtime = DbSwitchRuntime.standalone(); var workers = Executors.newSingleThreadExecutor()) {
            try (var setup = control.createStatement()) {
                setup.execute("CREATE SCHEMA " + schema);
                setup.execute("CREATE TABLE " + schema + ".blocked (id bigint PRIMARY KEY)");
            }
            try {
                locker.setAutoCommit(false);
                try (var lock = locker.createStatement()) { lock.execute("LOCK TABLE " + schema + ".blocked IN ACCESS EXCLUSIVE MODE"); }
                var operation = runtime.queries().register("tenant-lock", "record", UUID.randomUUID(), OperationBudget.defaults());
                var backend = new CompletableFuture<Integer>(); var failure = new AtomicReference<Throwable>();
                var worker = startBlockedWorker(workers, operation, "tenant-lock", "SELECT id FROM " + schema + ".blocked", backend, failure);
                int pid = backend.get(5, TimeUnit.SECONDS);
                awaitWaitEvent(control, pid, "Lock", null);
                assertEquals(QueryRuntime.CancelResult.REQUESTED, operation.requestCancel("tenant-lock"));
                worker.get(5, TimeUnit.SECONDS);
                assertNotNull(failure.get());
                assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination(), () -> terminationEvidence(operation, runtime));
                assertEquals(0, runtime.drivers().runtimes().connections().snapshot().workload());
                awaitBackendExit(control, pid);
            } finally {
                locker.rollback();
                try (var cleanup = control.createStatement()) { cleanup.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        } finally { Thread.interrupted(); }
    }

    @RepeatedTest(6) void absoluteDeadlineStopsAnObservedSlowQueryAndReleasesOnlyAfterPhysicalExit() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone(); var workers = Executors.newSingleThreadExecutor();
             var control = DriverManager.getConnection(spec().url(), spec().username(), spec().password())) {
            var budget = new OperationBudget(Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(5), 20, 1000, 100, 10);
            long started = System.nanoTime();
            var operation = runtime.queries().register("tenant-deadline", "record", UUID.randomUUID(), budget);
            var backend = new CompletableFuture<Integer>(); var failure = new AtomicReference<Throwable>();
            var worker = startBlockedWorker(workers, operation, "tenant-deadline", "SELECT pg_sleep(20)", backend, failure);
            int pid = backend.get(5, TimeUnit.SECONDS);
            awaitWaitEvent(control, pid, null, "PgSleep");
            org.awaitility.Awaitility.await().atMost(budget.total().plusSeconds(1)).until(operation::deadlineExpired);
            worker.get(budget.cleanupWait().toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(failure.get());
            assertTrue(operation.deadlineExpired());
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination(), () -> terminationEvidence(operation, runtime));
            assertEquals(0, runtime.drivers().runtimes().connections().snapshot().workload());
            awaitBackendExit(control, pid);
            assertTrue(System.nanoTime() - started < budget.total().plus(budget.cleanupWait()).toNanos(),
                    "Observed deadline, worker exit, connection release and actual backend exit must fit the original total plus cleanup budget");
        } finally { Thread.interrupted(); }
    }

    @Test void expiredConnectionWaitRetainsItsOwnerUntilLateCreationExitsAndPreservesMetadataReserve() throws Exception {
        var budget = new ConnectionBudget(2, 1);
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(), Map.of(), budget);
             var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
             var workers = Executors.newSingleThreadExecutor()) {
            var holder = runtime.openConnection(spec().product(), null, spec().url(), spec().username(), spec().password());
            try {
                var operationBudget = new OperationBudget(Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofMillis(50), 20, 1000, 100, 10);
                var operation = runtime.queries().register("tenant-wait", "record", UUID.randomUUID(), operationBudget);
                var failure = new AtomicReference<Throwable>();
                var worker = workers.submit(() -> {
                    try {
                        operation.bindWorker();
                        try (var session = operation.openSession(spec(), ConnectionBudget.Role.WORKLOAD)) {
                            fail("A workload connection cannot bypass the reserved metadata capacity");
                        }
                    } catch (Throwable expected) { failure.set(expected); }
                    finally { operation.workerExited(); Thread.interrupted(); }
                });
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> budget.snapshot().waiting() == 1);
                try (var metadata = cn.iocoder.yudao.framework.dbswitch.driver.DriverConnections.open(spec().product(), null,
                        drivers.runtimes(), spec().url(), spec().username(), spec().password(), ConnectionBudget.Role.METADATA)) {
                    assertEquals(1, budget.snapshot().metadata()); assertEquals(1, budget.snapshot().workload());
                }
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(operation::deadlineExpired);
                worker.get(5, TimeUnit.SECONDS);
                assertInstanceOf(SQLException.class, failure.get());
                assertTrue(operation.deadlineExpired());
                assertEquals(QueryRuntime.Termination.UNCONFIRMED, operation.awaitTermination());
                assertEquals(1, runtime.queries().activeOperations());
                holder.close();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                        operation.termination() == QueryRuntime.Termination.CONFIRMED);
                assertEquals(0, runtime.queries().activeOperations());
                var exited = budget.snapshot();
                assertEquals(0, exited.workload()); assertEquals(0, exited.metadata());
                assertEquals(0, exited.creating()); assertEquals(0, exited.quarantined()); assertEquals(0, exited.waiting());
            } finally { holder.close(); }
        } finally { Thread.interrupted(); }
    }

    private Future<?> startBlockedWorker(ExecutorService workers, QueryRuntime.Operation operation, String scope, String sql,
            CompletableFuture<Integer> backend, AtomicReference<Throwable> failure) {
        return workers.submit(() -> {
            try {
                operation.bindWorker();
                try (var session = operation.openSession(spec(), ConnectionBudget.Role.WORKLOAD)) {
                    try (var pid = session.query(scope, "SELECT pg_backend_pid()", List.of())) {
                        backend.complete(((Number) pid.fetch(scope, pid.id(), 1).rows().getFirst().getFirst()).intValue());
                    }
                    try (var blocked = session.query(scope, sql, List.of())) { blocked.fetch(scope, blocked.id(), 1); }
                }
            } catch (Throwable error) { failure.set(error); backend.completeExceptionally(error); }
            finally { operation.workerExited(); Thread.interrupted(); }
        });
    }

    private void awaitWaitEvent(Connection control, int pid, String type, String event) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            try (var statement = control.prepareStatement("SELECT wait_event_type,wait_event FROM pg_stat_activity WHERE pid=?")) {
                statement.setQueryTimeout(2); statement.setInt(1, pid);
                try (var rows = statement.executeQuery()) {
                    return rows.next() && (type == null || type.equals(rows.getString(1))) && (event == null || event.equals(rows.getString(2)));
                }
            }
        });
    }

    private void awaitBackendExit(Connection control, int pid) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (var statement = control.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid=?")) {
                statement.setQueryTimeout(2); statement.setInt(1, pid);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            }
        });
    }

    @Test void rowLimitFailsExplicitlyInsteadOfReturningTruncatedSuccess() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var budget = new OperationBudget(Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(3), 2, 1000, 100, 2);
            var operation = runtime.queries().register("tenant-a", "record", UUID.randomUUID(), budget);
            operation.bindWorker();
            try (var session = operation.openSession(spec(), ConnectionBudget.Role.WORKLOAD)) {
                var cursor = session.query("tenant-a", "SELECT n FROM generate_series(1,3) n", List.of());
                assertEquals(2, cursor.fetch("tenant-a", cursor.id(), 2).rows().size());
                assertThrows(SQLException.class, () -> cursor.fetch("tenant-a", cursor.id(), 1));
                assertTrue(operation.deadlineExpired());
                try { cursor.close(); } catch (SQLException ignored) { }
            } finally { operation.workerExited(); }
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
        } finally { Thread.interrupted(); }
    }

    @Test void postgresNativeValuesAndJdbcHandlesStayBehindThePublicQueryBoundary() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var operation = runtime.queries().register("tenant-a", "public-boundary", UUID.randomUUID(), OperationBudget.defaults());
            operation.bindWorker();
            try (var session = operation.openSession(spec(), ConnectionBudget.Role.WORKLOAD)) {
                var connection = session.connection();
                assertFalse(connection.isWrapperFor(org.postgresql.PGConnection.class));
                assertThrows(SQLFeatureNotSupportedException.class, () -> connection.unwrap(org.postgresql.PGConnection.class));
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT '{\"n\":1}'::jsonb AS payload")) {
                    assertTrue(rows.next());
                    assertInstanceOf(String.class, rows.getObject(1));
                    assertSame(connection, rows.getStatement().getConnection());
                    assertThrows(SQLFeatureNotSupportedException.class, () -> rows.getCharacterStream(1));
                    assertFalse(rows.getMetaData().isWrapperFor(org.postgresql.PGResultSetMetaData.class));
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> rows.getMetaData().unwrap(org.postgresql.PGResultSetMetaData.class));
                }
            } finally { operation.workerExited(); }
            assertEquals(QueryRuntime.Termination.CONFIRMED, operation.awaitTermination());
            assertEquals(0, runtime.drivers().runtimes().connections().snapshot().active());
        } finally { Thread.interrupted(); }
    }
}
