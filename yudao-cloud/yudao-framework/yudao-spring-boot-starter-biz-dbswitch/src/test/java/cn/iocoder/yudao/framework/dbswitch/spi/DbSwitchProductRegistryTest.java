package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbSwitchProductRegistryTest {
    @TempDir Path temporary;

    @Test
    void productResolutionRequiresInstalledProductAndNeverInfersIdentityFromDriverOrProtocol() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(ProductTypeEnum.POSTGRESQL, registry.resolve("pg").orElseThrow());
        assertEquals(ProductTypeEnum.KINGBASE, registry.resolve("Kingbase-ES").orElseThrow());
        assertEquals(ProductTypeEnum.SQLSERVER, registry.resolve("ms_sql").orElseThrow());
        assertEquals(ProductTypeEnum.GAUSSDB, registry.resolve("GAUSSDB").orElseThrow());
        assertTrue(registry.resolve("org.postgresql.Driver").isEmpty());
        assertTrue(registry.resolve("jdbc:postgresql:").isEmpty());
        var onlyMysql = new DbSwitchProductRegistry(List.of(registry.product(ProductTypeEnum.MYSQL)));
        assertTrue(onlyMysql.resolve("PG").isEmpty());
    }

    @Test
    void discoversEveryExistingProductWithoutCallingFactoriesOrOpeningConnections() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(36, registry.products().size());
        for (var type : ProductTypeEnum.values()) {
            if (type != ProductTypeEnum.UNKNOWN) {
                assertEquals(type, registry.product(type).product());
                assertEquals(DatabaseOperation.values().length, registry.product(type).capabilities().size());
                assertTrue(registry.product(type).capabilities().values().stream()
                        .noneMatch(value -> value.status() == OperationCapability.Status.VERIFIED));
            }
        }
    }

    @Test
    void duplicateProductsFailBeforeAnyRuntimeFactoryIsInvoked() {
        var calls = new AtomicInteger();
        var descriptor = DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL).metadata(() -> {
            calls.incrementAndGet();
            return mock(AbstractDatabase.class);
        }).build();
        assertThrows(IllegalArgumentException.class,
                () -> new DbSwitchProductRegistry(List.of(descriptor, descriptor)));
        assertEquals(0, calls.get());
    }

    @Test
    void registriesOwnIndependentImmutableSnapshots() {
        var first = mock(AbstractDatabase.class);
        var second = mock(AbstractDatabase.class);
        var mutable = new ArrayList<DbSwitchProductDescriptor>();
        mutable.add(DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL).metadata(() -> first).build());
        var left = new DbSwitchProductRegistry(mutable);
        mutable.clear();
        var right = new DbSwitchProductRegistry(List.of(
                DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL).metadata(() -> second).build()));
        assertSame(first, left.metadata(ProductTypeEnum.MYSQL));
        assertSame(second, right.metadata(ProductTypeEnum.MYSQL));
        assertThrows(UnsupportedOperationException.class, () -> left.products().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> left.product(ProductTypeEnum.MYSQL).capabilities().clear());
    }

    @Test
    void writerSelectionComesFromDescriptorIncludingPostgresCopy() {
        var source = mock(DataSource.class);
        var insert = mock(IDatabaseWriter.class);
        var copy = mock(IDatabaseWriter.class);
        var registry = new DbSwitchProductRegistry(List.of(
                DbSwitchProductDescriptor.builder(ProductTypeEnum.POSTGRESQL)
                        .writer(WriteMode.INSERT, ignored -> insert)
                        .writer(WriteMode.COPY, ignored -> copy).build()));
        assertSame(insert, registry.writer(source, ProductTypeEnum.POSTGRESQL, WriteMode.INSERT));
        assertSame(copy, registry.writer(source, ProductTypeEnum.POSTGRESQL, WriteMode.COPY));
        verifyNoInteractions(source);
    }

    @Test
    void missingWriteModeNeverFallsBackToAnotherProtocol() {
        var source = mock(DataSource.class);
        var registry = new DbSwitchProductRegistry(List.of(
                DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL)
                        .writer(WriteMode.INSERT, ignored -> mock(IDatabaseWriter.class)).build()));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.writer(source, ProductTypeEnum.MYSQL, WriteMode.COPY));
        verifyNoInteractions(source);
    }

    @Test
    void nativeDifferenceFactoriesAreExplicitAndUnavailableProductsNeverFallback() {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        var expected = java.util.Map.of(
                ProductTypeEnum.MONGODB, cn.iocoder.yudao.framework.dbswitch.dbsynch.mongodb.MongoDatabaseSyncImpl.class,
                ProductTypeEnum.ELASTICSEARCH, cn.iocoder.yudao.framework.dbswitch.dbsynch.elasticsearch.ElasticsearchDatabaseSyncImpl.class,
                ProductTypeEnum.HIVE, cn.iocoder.yudao.framework.dbswitch.dbsynch.hive.HiveDatabaseSyncImpl.class,
                ProductTypeEnum.TDENGINE, cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine.TdengineDatabaseSyncImpl.class);
        for (var item : expected.entrySet()) {
            assertInstanceOf(item.getValue(), registry.synchronizer(source, item.getKey()));
            assertEquals(OperationCapability.Status.DECLARED, registry.product(item.getKey()).capabilities().get(DatabaseOperation.SYNC_DIFF).status());
        }
        // InfluxDB3 has no row-level mutation protocol; its DIFF is a scoped, durable whole-table rewrite
        // and the scope is part of the public capability contract, not an unqualified "supported".
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbsynch.influxdb.InfluxdbDatabaseSyncImpl.class,
                registry.synchronizer(source, ProductTypeEnum.INFLUXDB3));
        var influxDiff = registry.product(ProductTypeEnum.INFLUXDB3).capabilities().get(DatabaseOperation.SYNC_DIFF);
        assertEquals(OperationCapability.Status.DECLARED, influxDiff.status());
        assertEquals("DURABLE_BOUNDED_WHOLE_TABLE_REWRITE_WITH_EXCLUSIVE_OWNERSHIP", influxDiff.reason());
        // A product registered without a synchronizer must fail closed instead of borrowing another protocol.
        var withoutSynchronizer = new DbSwitchProductRegistry(List.of(
                DbSwitchProductDescriptor.builder(ProductTypeEnum.INFLUXDB3)
                        .writer(WriteMode.INSERT, ignored -> mock(IDatabaseWriter.class)).build()));
        assertThrows(UnsupportedOperationException.class, () -> withoutSynchronizer.synchronizer(source, ProductTypeEnum.INFLUXDB3));
        assertEquals(OperationCapability.Status.UNSUPPORTED,
                withoutSynchronizer.product(ProductTypeEnum.INFLUXDB3).capabilities().get(DatabaseOperation.SYNC_DIFF).status());
        verifyNoInteractions(source);
    }

    @Test
    void oceanbaseUpsertFactoryIsProductOwnedAndNeverGuessesTheTenantModeAtConstruction() {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        // The compatibility mode is proven on each actual write connection, not cached from a factory-time probe,
        // and the MySQL/TiDB writer is not borrowed for the OceanBase declaration.
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseModeUpsertWriter.class,
                registry.upsertWriter(source, ProductTypeEnum.OCEANBASE));
        assertEquals("OCEANBASE_TENANT_MODE_SPECIFIC_EXACT_VALUE_UPSERT",
                registry.product(ProductTypeEnum.OCEANBASE).capabilities().get(DatabaseOperation.WRITE_UPSERT).reason());
        verifyNoInteractions(source);
    }

    @Test
    void declarationWithoutFactoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL)
                .declare(DatabaseOperation.READ).build());
        assertThrows(IllegalArgumentException.class, () -> DbSwitchProductDescriptor.builder(ProductTypeEnum.UNKNOWN).build());
        assertThrows(IllegalArgumentException.class, () -> new DbSwitchProductRegistry(List.of()));
    }

    @Test
    void malformedServiceRegistrationFailsClosedAndDoesNotPoisonLaterDiscovery() throws Exception {
        var service = temporary.resolve("META-INF/services/" + DbSwitchProductProvider.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, "missing.provider.MustNotBeIgnored\n");
        try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, getClass().getClassLoader())) {
            assertThrows(java.util.ServiceConfigurationError.class, () -> DbSwitchProductRegistry.discover(loader));
        }
        assertEquals(36, DbSwitchProductRegistry.discover().products().size());
    }
}
