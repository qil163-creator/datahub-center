package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DiagnosticCloseLifecycleTest {
    @Test void asynchronousCloseWithoutAnyPriorNativeOperationReleasesPhysicalPermitAndOwnerLease() throws Exception {
        try (var registry = new DriverRuntimeRegistry()) {
            var connection = DriverConnections.open(ProductTypeEnum.SQLITE3, null, registry,
                    "jdbc:sqlite::memory:", null, null, ConnectionBudget.Role.METADATA);
            assertEquals(1, registry.connections().snapshot().metadata());
            DriverNativeAccess.requestClose(connection).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, registry.connections().snapshot().metadata());
            assertEquals(0, registry.connections().snapshot().quarantined());
            assertEquals(0, registry.statuses().getFirst().leases());
        }
    }

    @Test void registryShutdownRetainsClosureWorkersUntilAdmittedConnectionExits() throws Exception {
        try (var registry = new DriverRuntimeRegistry()) {
            var connection = DriverConnections.open(ProductTypeEnum.SQLITE3, null, registry,
                    "jdbc:sqlite::memory:", null, null, ConnectionBudget.Role.METADATA);
            registry.close();
            DriverNativeAccess.requestClose(connection).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, registry.connections().snapshot().metadata());
            assertEquals(DriverRuntimeRegistry.State.CLOSED, registry.statuses().getFirst().state());
        }
    }
}
