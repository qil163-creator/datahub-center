package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DiagnosticIoTest {
    @Test void lateConnectionKeepsPermitUntilItsOwnerActuallyCleansIt() throws Exception {
        var budget = new ConnectionBudget(2, 1);
        var release = new CountDownLatch(1); var started = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
        try (var io = new DiagnosticIo(1, 1)) {
            assertThrows(SQLTimeoutException.class, () -> io.call(80, () -> {
                var permit = budget.acquire(ConnectionBudget.Role.METADATA, Duration.ofSeconds(1));
                started.countDown(); release.await(); permit.connected(); return permit;
            }, permit -> { permit.close(); cleaned.countDown(); }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(1, budget.snapshot().creating()); assertEquals(1, budget.snapshot().metadata());
            release.countDown(); assertTrue(cleaned.await(2, TimeUnit.SECONDS));
            assertEquals(0, budget.snapshot().metadata()); assertEquals(0, budget.snapshot().active());
        } finally { release.countDown(); }
    }

    @Test void exhaustedCapacityRejectsAndExpiredQueuedTasksNeverStart() throws Exception {
        var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        var queuedCalls = new AtomicInteger();
        try (var io = new DiagnosticIo(1, 1)) {
            assertThrows(SQLTimeoutException.class, () -> io.call(50, () -> {
                started.countDown(); release.await(); return true;
            }, ignored -> { }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertThrows(SQLTimeoutException.class, () -> io.call(50, () -> queuedCalls.incrementAndGet(), ignored -> { }));
            assertThrows(SQLTransientConnectionException.class, () -> io.call(50, () -> true, ignored -> { }));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (;;) {
                try { assertTrue(io.call(1000, () -> true, ignored -> { })); break; }
                catch (SQLTransientConnectionException busy) {
                    assertTrue(System.nanoTime() < deadline); Thread.sleep(5);
                }
            }
            assertEquals(0, queuedCalls.get());
        } finally { release.countDown(); }
    }

    @Test void networkWorkerDoesNotInheritCallerSecurityThreadLocal() throws Exception {
        var context = new InheritableThreadLocal<String>(); context.set("caller-identity");
        try (var io = new DiagnosticIo(1, 1)) {
            assertNull(io.call(1000, context::get, ignored -> { }));
        } finally { context.remove(); }
    }
}
