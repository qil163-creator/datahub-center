package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.module.dataforge.service.execution.DistributedRunFaultSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cleanup regression tests only: real isolated PG, controlled Process doubles, no child JVM starts. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
class DistributedRunFaultCleanupTest {
    @TempDir Path scratch;
    private DistributedRunFaultSupport fixture;
    private final List<ControlledProcess> processes = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        fixture = new DistributedRunFaultSupport(System.getProperty("datahub.test.postgres.url"));
    }

    @AfterEach void cleanup() throws Exception {
        // A failed assertion must not leave even the reproducer's owned schema behind.
        for (var process : processes) {
            process.alive = false;
            process.destroyFailure = null;
            process.waitFailure = null;
        }
        try { if (fixture != null) fixture.close(); }
        finally { clearIdentity(); }
    }

    @Test void heldTableLockBoundsDropAndRetainsOwnedSchemaForRetry() throws Exception {
        var identityCleared = new AtomicBoolean();
        var workers = Executors.newSingleThreadExecutor();
        var properties = new Properties();
        properties.setProperty("user", "dataforge_test");
        properties.setProperty("password", "");
        properties.setProperty("currentSchema", fixture.schema);
        properties.setProperty("connectTimeout", "2");
        properties.setProperty("socketTimeout", "3");
        properties.setProperty("options", "-c statement_timeout=2000 -c lock_timeout=500");
        try (var blocker = DriverManager.getConnection(fixture.url, properties)) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement()) {
                    statement.setQueryTimeout(2);
                    statement.execute("LOCK TABLE dh_dataforge_access IN ACCESS SHARE MODE");
                }
                var result = workers.submit(() -> {
                    identity(123L);
                    TenantContextHolder.setIgnore(true);
                    try {
                        fixture.close();
                        return (Exception) null;
                    } catch (Exception failure) {
                        return failure;
                    } finally {
                        identityCleared.set(identityIsClear());
                    }
                });
                // Future.get supplies the experiment's independent deadline even before Q1 is fixed.
                // The finally below releases the lock on BOTH RED and GREEN; no interrupt-only JDBC assumption.
                Exception failure = assertDoesNotThrow(() -> result.get(4, TimeUnit.SECONDS),
                        "Owned DROP must return its database lock-timeout failure within four seconds");
                assertInstanceOf(DataAccessException.class, failure);
                assertEquals("55P03", sqlState(failure), "Must actually time out on the held PostgreSQL lock");
                assertTrue(schemaExists(), "A failed DROP must retain the owned schema for a bounded retry");
                assertTrue(identityCleared.get(), "Failed DROP must clear worker tenant/security context");
            } finally {
                blocker.rollback();
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Cleanup probe did not drain after lock release");
        }
        fixture.close();
        assertFalse(schemaExists());
    }

    @Test void adminAndSchemaConnectionsHaveExplicitSocketQueryStatementAndLockBounds() throws Exception {
        for (var jdbc : List.of(fixture.admin, fixture.db)) {
            var source = assertInstanceOf(DriverManagerDataSource.class, jdbc.getDataSource());
            var properties = source.getConnectionProperties();
            assertNotNull(properties, "Driver connection/socket bounds must be explicit for every connection");
            assertEquals("5", properties.getProperty("connectTimeout"));
            assertEquals("10", properties.getProperty("socketTimeout"));
            assertEquals(6, jdbc.getQueryTimeout());
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(2);
                try (var values = statement.executeQuery("SELECT name,setting FROM pg_settings WHERE name IN ('statement_timeout','lock_timeout') ORDER BY name")) {
                    assertTrue(values.next()); assertEquals("lock_timeout", values.getString(1)); assertEquals("1000", values.getString(2));
                    assertTrue(values.next()); assertEquals("statement_timeout", values.getString(1)); assertEquals("5000", values.getString(2));
                    assertFalse(values.next());
                }
            }
        }
    }

    @Test void firstProcessExitTimeoutStillAttemptsLaterProcessAndClearsIdentityWithoutDrop() throws Exception {
        var first = addProcess(false);
        var second = addProcess(true);
        identity(123L);
        TenantContextHolder.setIgnore(true);
        assertThrows(AssertionError.class, fixture::close);
        assertEquals(1, first.destroyCalls);
        assertEquals(1, second.destroyCalls, "First exit timeout must not abandon another owned process");
        assertFalse(second.alive);
        assertTrue(schemaExists(), "Never DROP while an owned process remains alive");
        assertFalse(fixture.runtime.app.isActive(), "Parent Spring runtime must still be closed");
        assertTrue(identityIsClear());
        assertTrue(Files.exists(first.args), "Keep the live child's argument file for diagnosis");
        assertFalse(Files.exists(second.args));
    }

    @Test void processExceptionAndAssertionAreAggregatedAndOriginalFailureIsRethrown() throws Exception {
        var first = addProcess(false);
        var original = new IllegalStateException("first process cleanup failure");
        first.destroyFailure = original;
        var second = addProcess(false);
        var assertion = new AssertionError("second process wait failure");
        second.waitFailure = assertion;
        var third = addProcess(true);
        identity(123L);
        assertSame(original, assertThrows(IllegalStateException.class, fixture::close));
        assertTrue(Arrays.asList(original.getSuppressed()).contains(assertion));
        assertEquals(1, second.destroyCalls); assertEquals(1, third.destroyCalls);
        assertTrue(schemaExists()); assertFalse(third.alive);
        assertTrue(identityIsClear());
    }

    @Test void allProcessesDeadAllowsDropButStillReportsOriginalAssertion() throws Exception {
        var first = addProcess(true);
        var original = new AssertionError("exit observer failed after process exited");
        first.waitFailure = original;
        var second = addProcess(true);
        identity(123L);
        assertSame(original, assertThrows(AssertionError.class, fixture::close));
        assertEquals(1, second.destroyCalls);
        assertFalse(schemaExists(), "A nonfatal cleanup diagnostic does not prevent safe remaining cleanup");
        assertFalse(fixture.runtime.app.isActive());
        assertTrue(identityIsClear());
    }

    @Test void vmFatalErrorIsNotConvertedOrSwallowedButIdentityFinallyStillRuns() throws Exception {
        var first = addProcess(false);
        var fatal = new TestVmFailure();
        first.destroyFailure = fatal;
        identity(123L);
        TenantContextHolder.setIgnore(true);
        assertSame(fatal, assertThrows(TestVmFailure.class, fixture::close));
        assertTrue(identityIsClear());
        assertTrue(schemaExists());
    }

    @Test void runtimePollingReusesBoundedConnectionsAndClosesItsPool() throws Exception {
        var backends = new java.util.HashSet<Integer>();
        for (int i = 0; i < 100; i++) backends.add(fixture.runtime.db.queryForObject("SELECT pg_backend_pid()", Integer.class));
        assertTrue(backends.size() <= 4, "Polling must not open one physical TCP connection per query");
        var pool = assertInstanceOf(com.zaxxer.hikari.HikariDataSource.class, fixture.runtime.db.getDataSource());
        assertEquals(4, pool.getMaximumPoolSize());
        fixture.close();
        assertTrue(pool.isClosed());
    }

    @Test void successfulCleanupIsIdempotentAndClearsEveryOwnedProcessAndSchema() throws Exception {
        var first = addProcess(true);
        var second = addProcess(true);
        identity(123L);
        fixture.close();
        fixture.close();
        assertEquals(1, first.destroyCalls); assertEquals(1, second.destroyCalls);
        assertFalse(schemaExists());
        assertFalse(Files.exists(first.args)); assertFalse(Files.exists(second.args));
        assertTrue(identityIsClear());
    }

    private ControlledProcess addProcess(boolean exits) throws Exception {
        var process = new ControlledProcess(exits);
        process.args = Files.createTempFile(scratch, "owned-child-", ".args");
        processes.add(process);
        fixture.nodes.add(fixture.new Node("controlled-" + processes.size(), process, scratch.resolve("child.log"), process.args));
        return process;
    }
    private boolean schemaExists() {
        return fixture.admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Long.class, fixture.schema) == 1;
    }
    private static boolean identityIsClear() {
        return TenantContextHolder.getTenantId() == null && !TenantContextHolder.isIgnore()
                && SecurityContextHolder.getContext().getAuthentication() == null;
    }
    private static String sqlState(Exception failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof SQLException sql) return sql.getSQLState();
        fail("Expected PostgreSQL exception in cleanup failure");
        return null;
    }

    /** No OS process starts here; only the fixture's failure-cleanup branch is under test. */
    private static final class ControlledProcess extends Process {
        final boolean exits;
        boolean alive = true;
        int destroyCalls;
        Error waitFailure;
        Throwable destroyFailure;
        Path args;
        ControlledProcess(boolean exits) { this.exits = exits; }
        @Override public Process destroyForcibly() {
            destroyCalls++;
            if (destroyFailure instanceof Error error) throw error;
            if (destroyFailure instanceof RuntimeException failure) throw failure;
            return this;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            assertTrue(timeout > 0 && unit.toSeconds(timeout) <= 10, "Process wait must have a finite budget");
            if (exits) alive = false;
            if (waitFailure != null) throw waitFailure;
            return exits;
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public int waitFor() { throw new AssertionError("Unbounded process wait is forbidden"); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    }
    private static final class TestVmFailure extends VirtualMachineError { }
}
