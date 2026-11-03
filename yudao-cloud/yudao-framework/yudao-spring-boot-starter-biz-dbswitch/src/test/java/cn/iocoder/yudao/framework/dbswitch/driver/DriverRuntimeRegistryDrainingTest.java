package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** A source pool's physical exit must restore the shared handle while a target lease remains live. */
@Timeout(15)
class DriverRuntimeRegistryDrainingTest {
    @TempDir Path directory;

    @Test void retainedLiveLeaseCannotCreateAPhysicalConnectionAfterRegistryShutdown() throws Exception {
        try (var registry = new DriverRuntimeRegistry(); var lease = registry.acquireApplication(ProductTypeEnum.SQLITE3, null)) {
            registry.close();
            var before = registry.connections().snapshot();
            assertThrows(SQLException.class, () -> {
                try (var unexpected = lease.connect("jdbc:sqlite::memory:", new Properties())) {
                    fail("Global shutdown must reject a new physical connection, even from a retained lease");
                }
            });
            assertEquals(before, registry.connections().snapshot(), "Refusal must not allocate or consume a physical permit");
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            lease.close(); assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
        }
    }

    @Test void alreadyAdmittedBlockedConnectRetainsItsLeaseAndPermitUntilLatePhysicalClose() throws Exception {
        // Reuse the existing verified-bundle fixture; its late-connect/close signals are deterministic.
        // Reflection avoids changing the independently owned fixture test's private implementation API.
        var existing = new DriverPoolTerminationTest(); existing.directory = directory;
        Object fixture = ReflectionTestUtils.invokeMethod(existing, "fixture", ProductTypeEnum.HIVE);
        try (var resources = (AutoCloseable) fixture) {
            DriverRuntimeRegistry registry = ReflectionTestUtils.invokeMethod(fixture, "registry");
            InvisibleDataSource source = ReflectionTestUtils.invokeMethod(fixture, "source");
            HikariDataSource pool = ReflectionTestUtils.invokeMethod(fixture, "pool");
            Class<?> driver = ReflectionTestUtils.invokeMethod(fixture, "driver");
            ((AtomicBoolean) driver.getField("blockConnect").get(null)).set(true);
            ((AtomicBoolean) driver.getField("blockClose").get(null)).set(true);
            var entered = (CountDownLatch) driver.getField("connectEntered").get(null);
            var allowConnect = (CountDownLatch) driver.getField("allowConnect").get(null);
            var closeEntered = (CountDownLatch) driver.getField("closeEntered").get(null);
            var allowClose = (CountDownLatch) driver.getField("allowClose").get(null);
            var worker = Executors.newSingleThreadExecutor();
            var receipt = DriverPoolFactory.physicalTermination(pool);
            Future<Connection> connecting = worker.submit((Callable<Connection>) source::getConnection);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(1, registry.connections().snapshot().creating());
                assertTimeout(Duration.ofSeconds(1), registry::close, "Vendor connect must not hold the registry monitor");
                pool.close();
                assertFalse(connecting.isDone()); assertFalse(receipt.toCompletableFuture().isDone());
                assertEquals(1, registry.statuses().getFirst().leases());
                assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
                assertEquals(1, registry.connections().snapshot().creating());
                assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
                allowConnect.countDown();
                assertTrue(closeEntered.await(3, TimeUnit.SECONDS));
                assertFalse(receipt.toCompletableFuture().isDone());
                assertEquals(1, registry.connections().snapshot().quarantined());
                assertEquals(1, registry.statuses().getFirst().leases());
                allowClose.countDown();
                var refused = assertThrows(ExecutionException.class, () -> connecting.get(3, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, refused.getCause());
                receipt.toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(0, registry.connections().snapshot().workload());
                assertEquals(0, registry.statuses().getFirst().leases());
                assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
            } finally {
                allowConnect.countDown(); allowClose.countDown(); worker.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void drainedSourceLeaseRestoresHandleWithoutClosingItsLiveTargetSibling() throws Exception {
        try (var registry = new DriverRuntimeRegistry(); var target = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var source = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var targetConnection = target.connect("jdbc:sqlite::memory:", new Properties());
             var connection = source.connect("jdbc:sqlite::memory:", new Properties())) {
            source.markDraining(); source.markDraining(); source.close(); source.close();
            assertEquals(1, registry.statuses().getFirst().leases());
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertEquals(2, registry.connections().snapshot().active(), "Both open physical connections still own their permits");
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
            assertThrows(java.sql.SQLException.class, () -> source.connect("jdbc:sqlite::memory:", new Properties()));
            try (var statement = targetConnection.createStatement(); var rows = statement.executeQuery("SELECT 42")) {
                assertTrue(rows.next()); assertEquals(42, rows.getInt(1), "The already borrowed target connection remains usable");
            }
            connection.close();
            assertEquals(1, registry.connections().snapshot().workload());
            assertEquals(DriverRuntimeRegistry.State.READY, registry.statuses().getFirst().state());
            try (var nextSource = registry.acquireApplication(ProductTypeEnum.SQLITE3, null)) {
                assertSame(target.classLoader(), nextSource.classLoader());
                assertEquals(2, registry.statuses().getFirst().leases());
            }
            registry.close();
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
            target.close(); assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertEquals(1, registry.connections().snapshot().workload());
            targetConnection.close(); assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
        }
    }

    @Test void oneSourceExitCannotClearAnotherSourcesUnconfirmedPhysicalState() throws Exception {
        try (var registry = new DriverRuntimeRegistry(); var target = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var first = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var second = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var firstConnection = first.connect("jdbc:sqlite::memory:", new Properties());
             var secondConnection = second.connect("jdbc:sqlite::memory:", new Properties())) {
            first.markDraining(); second.markDraining(); first.close(); second.close();
            firstConnection.close();
            assertEquals(1, registry.connections().snapshot().active());
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
            secondConnection.close();
            assertEquals(DriverRuntimeRegistry.State.READY, registry.statuses().getFirst().state());
            assertEquals(1, registry.statuses().getFirst().leases());
            assertEquals(0, registry.connections().snapshot().workload());
        }
    }

    @Test void registryShutdownNeverRestoresAdmissionBeforeAllLeasesAndPhysicalConnectionsExit() throws Exception {
        try (var registry = new DriverRuntimeRegistry(); var lease = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var connection = lease.connect("jdbc:sqlite::memory:", new Properties())) {
            lease.markDraining(); registry.close(); lease.close();
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertEquals(1, registry.connections().snapshot().workload());
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
            connection.close();
            assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
            assertEquals(0, registry.connections().snapshot().workload());
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
        }
    }
}
