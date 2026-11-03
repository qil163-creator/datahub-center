package cn.iocoder.yudao.framework.dbswitch.driver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class NativeCallControlTest {
    @Test void cancelledFutureAndClosedConnectionDoNotReleaseBudgetUntilUninterruptibleWorkerExits() throws Exception {
        var budget = new ConnectionBudget(2, 1); var permit = budget.acquire(ConnectionBudget.Role.WORKLOAD, Duration.ofSeconds(1)); permit.connected();
        var connection = mock(Connection.class); var physicallyClosed = new AtomicBoolean();
        when(connection.isClosed()).thenAnswer(call -> physicallyClosed.get());
        doAnswer(call -> { physicallyClosed.set(true); return null; }).when(connection).close();
        var releaseWorker = new CountDownLatch(1); var terminated = new CountDownLatch(1);
        try (var owner = new NativeCallControl(2)) {
            var session = owner.bind(connection, getClass().getClassLoader(), permit::quarantine, () -> { permit.close(); terminated.countDown(); });
            long started = System.nanoTime();
            assertThrows(SQLTimeoutException.class, () -> session.invoke(() -> { awaitIgnoringInterrupt(releaseWorker); return "late value"; }, 100));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
            await(() -> physicallyClosed.get());
            assertEquals(1, budget.snapshot().quarantined()); assertEquals(1, budget.snapshot().workload());
            assertFalse(session.isTerminated());
            assertThrows(SQLTimeoutException.class, session::closeConnection);
            assertThrows(SQLException.class, () -> session.invoke(() -> "must not run", 100));
            releaseWorker.countDown(); assertTrue(terminated.await(2, TimeUnit.SECONDS));
            assertEquals(0, budget.snapshot().workload()); assertEquals(0, budget.snapshot().quarantined());
            session.closeConnection(); verify(connection, times(1)).close(); verify(connection, never()).abort(any());
        } finally { releaseWorker.countDown(); }
    }

    @Test void workerExitWithoutPhysicalCloseConfirmationKeepsConnectionCharged() throws Exception {
        var budget = new ConnectionBudget(2, 1); var permit = budget.acquire(ConnectionBudget.Role.WORKLOAD, Duration.ofSeconds(1)); permit.connected();
        var connection = mock(Connection.class); var closeStarted = new CountDownLatch(1); var releaseClose = new CountDownLatch(1);
        var physical = new AtomicBoolean(); var terminated = new CountDownLatch(1);
        when(connection.isClosed()).thenAnswer(call -> physical.get());
        doAnswer(call -> { closeStarted.countDown(); awaitIgnoringInterrupt(releaseClose); physical.set(true); return null; }).when(connection).close();
        try (var owner = new NativeCallControl(2)) {
            var session = owner.bind(connection, getClass().getClassLoader(), permit::quarantine, () -> { permit.close(); terminated.countDown(); });
            assertThrows(SQLTimeoutException.class, () -> session.invoke(() -> { new CountDownLatch(1).await(); return null; }, 100));
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, budget.snapshot().quarantined()); assertFalse(session.isTerminated());
            releaseClose.countDown(); assertTrue(terminated.await(2, TimeUnit.SECONDS));
            assertEquals(0, budget.snapshot().workload());
        } finally { releaseClose.countDown(); }
    }

    @Test void nativeWorkerCapacityHasNoUnboundedQueueOrThreadFallback() throws Exception {
        var release = new CountDownLatch(1); var firstConnection = mock(Connection.class); var secondConnection = mock(Connection.class);
        when(firstConnection.isClosed()).thenReturn(true); when(secondConnection.isClosed()).thenReturn(true);
        try (var owner = new NativeCallControl(1)) {
            var first = owner.bind(firstConnection, getClass().getClassLoader(), () -> { }, () -> { });
            var second = owner.bind(secondConnection, getClass().getClassLoader(), () -> { }, () -> { });
            assertThrows(SQLTimeoutException.class, () -> first.invoke(() -> { awaitIgnoringInterrupt(release); return null; }, 100));
            assertThrows(SQLTransientConnectionException.class, () -> second.invoke(() -> fail("No capacity must reject before execution"), 100));
            second.closeConnection(); release.countDown(); await(first::isTerminated);
        } finally { release.countDown(); }
    }

    @Test void successfulCallsAreSequentialAndUseTheDriverLoaderWithoutLeakingVendorFailureDetails() throws Exception {
        var connection = mock(Connection.class); when(connection.isClosed()).thenReturn(true);
        var pinned = new ClassLoader(getClass().getClassLoader()) { };
        var callerLoader = Thread.currentThread().getContextClassLoader();
        try (var owner = new NativeCallControl(1)) {
            var session = owner.bind(connection, pinned, () -> { }, () -> { });
            for (int i = 0; i < 50; i++) assertSame(pinned, session.invoke(() -> Thread.currentThread().getContextClassLoader(), 1000));
            var failure = assertThrows(SQLException.class, () -> session.invoke(() -> { throw new SQLException("secret payload"); }, 1000));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("secret"));
            assertSame(callerLoader, Thread.currentThread().getContextClassLoader());
            session.closeConnection(); assertTrue(session.isTerminated());
        }
    }

    @Test void callerInterruptionPreservesTheInterruptAndChargesTheUnfinishedWorker() throws Exception {
        var budget = new ConnectionBudget(2, 1); var permit = budget.acquire(ConnectionBudget.Role.WORKLOAD, Duration.ofSeconds(1)); permit.connected();
        var connection = mock(Connection.class); when(connection.isClosed()).thenReturn(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>(); var callerInterrupted = new AtomicBoolean();
        try (var owner = new NativeCallControl(2)) {
            var session = owner.bind(connection, getClass().getClassLoader(), permit::quarantine, () -> { permit.close(); exited.countDown(); });
            var caller = Thread.ofPlatform().start(() -> {
                try { session.invoke(() -> { entered.countDown(); awaitIgnoringInterrupt(release); return null; }, 5000); }
                catch (Throwable failure) { observed.set(failure); callerInterrupted.set(Thread.currentThread().isInterrupted()); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS)); caller.interrupt(); caller.join(1500);
            assertFalse(caller.isAlive()); assertInstanceOf(SQLTimeoutException.class, observed.get()); assertTrue(callerInterrupted.get());
            assertEquals(1, budget.snapshot().quarantined()); assertFalse(session.isTerminated());
            release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS)); assertEquals(0, budget.snapshot().workload());
        } finally { release.countDown(); }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) try { latch.await(); break; } catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
