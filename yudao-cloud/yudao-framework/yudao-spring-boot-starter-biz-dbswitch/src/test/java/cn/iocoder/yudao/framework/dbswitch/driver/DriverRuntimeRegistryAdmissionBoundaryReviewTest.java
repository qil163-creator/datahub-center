package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Native SQLite evidence for the distinction between global shutdown and one pool's local drain. */
@Timeout(15)
class DriverRuntimeRegistryAdmissionBoundaryReviewTest {

    @Test void registryShutdownRejectsNewPhysicalConnectionsFromAnAlreadyAcquiredLiveLease() throws Exception {
        try (var registry = new DriverRuntimeRegistry();
             var lease = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var alreadyBorrowed = lease.connect("jdbc:sqlite::memory:", new Properties())) {
            registry.close();
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
            assertEquals(1, registry.connections().snapshot().active());

            assertThrows(SQLException.class, () -> {
                try (var refused = lease.connect("jdbc:sqlite::memory:", new Properties())) {
                    fail("A retained live lease admitted a new physical connection after global shutdown");
                }
            });
            assertEquals(1, registry.connections().snapshot().active(), "The refused attempt must not consume a permit");
            try (var statement = alreadyBorrowed.createStatement(); var rows = statement.executeQuery("SELECT 42")) {
                assertTrue(rows.next()); assertEquals(42, rows.getInt(1), "Previously admitted work remains able to exit normally");
            }
            alreadyBorrowed.close();
            assertEquals(0, registry.connections().snapshot().active());
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            lease.close();
            assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
        }
    }

    @Test void oneSourcePoolDrainingDoesNotRejectNewBorrowsFromItsStillLiveTargetSiblingLease() throws Exception {
        try (var registry = new DriverRuntimeRegistry();
             var source = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var target = registry.acquireApplication(ProductTypeEnum.SQLITE3, null);
             var sourceConnection = source.connect("jdbc:sqlite::memory:", new Properties())) {
            source.markDraining(); source.close();
            assertEquals(DriverRuntimeRegistry.State.DRAINING, registry.statuses().getFirst().state());
            assertThrows(IllegalStateException.class, () -> registry.acquireApplication(ProductTypeEnum.SQLITE3, null));
            try (var targetConnection = target.connect("jdbc:sqlite::memory:", new Properties())) {
                assertEquals(2, registry.connections().snapshot().active());
                sourceConnection.close();
                assertEquals(DriverRuntimeRegistry.State.READY, registry.statuses().getFirst().state());
                try (var statement = targetConnection.createStatement(); var rows = statement.executeQuery("SELECT 43")) {
                    assertTrue(rows.next()); assertEquals(43, rows.getInt(1));
                }
            }
            assertEquals(0, registry.connections().snapshot().active());
        }
    }
}
