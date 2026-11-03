package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CursorProductPairCatalogTest {
    @Test
    void shippedDirectionsHaveSourceAndTargetImplementationsButDoNotClaimUniversalCertification() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(Set.of(ProductTypeEnum.GBASE8A, ProductTypeEnum.POSTGRESQL), registry.cursorTargets(ProductTypeEnum.GBASE8A));
        assertEquals(Set.of(ProductTypeEnum.CLICKHOUSE), registry.cursorTargets(ProductTypeEnum.CLICKHOUSE));
        assertEquals(Set.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.TDENGINE), registry.cursorTargets(ProductTypeEnum.TDENGINE));
        assertEquals(Set.of(ProductTypeEnum.OSCAR), registry.cursorTargets(ProductTypeEnum.OSCAR));
        assertEquals(Set.of(ProductTypeEnum.SQLSERVER2000), registry.cursorTargets(ProductTypeEnum.SQLSERVER2000));
        for (var source : registry.products()) {
            for (var target : registry.cursorTargets(source.product())) {
                assertDoesNotThrow(() -> registry.requireCursorPair(source.product(), target));
                assertNotEquals(OperationCapability.Status.UNSUPPORTED,
                        registry.product(target).capabilities().get(DatabaseOperation.WRITE_UPSERT).status());
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> registry.requireCursorPair(ProductTypeEnum.GBASE8A, ProductTypeEnum.MYSQL));
        assertThrows(IllegalArgumentException.class,
                () -> registry.requireCursorPair(ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.MYSQL));
    }

    @Test
    void protocolSimilarityOrFactoriesAloneNeverCreateAnImplicitDirection() {
        var descriptor = source(ProductTypeEnum.MYSQL).build();
        var registry = new DbSwitchProductRegistry(List.of(descriptor));
        assertTrue(registry.cursorTargets(ProductTypeEnum.MYSQL).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> registry.requireCursorPair(ProductTypeEnum.MYSQL, ProductTypeEnum.MYSQL));
    }

    @Test
    void omittedOptionalTargetModuleAndMissingUpsertAreFilteredWithoutOpeningAConnection() {
        var source = source(ProductTypeEnum.POSTGRESQL).cursorTargets(ProductTypeEnum.MYSQL).build();
        var missing = new DbSwitchProductRegistry(List.of(source));
        assertTrue(missing.cursorTargets(ProductTypeEnum.POSTGRESQL).isEmpty());
        var unsupported = DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL).build();
        var registry = new DbSwitchProductRegistry(List.of(source, unsupported));
        assertTrue(registry.cursorTargets(ProductTypeEnum.POSTGRESQL).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.requireCursorPair(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL));
    }

    @Test
    void descriptorSnapshotAndMapperCopiesRetainImmutableDirections() {
        var builder = source(ProductTypeEnum.POSTGRESQL).cursorTargets(ProductTypeEnum.POSTGRESQL);
        var first = builder.build();
        builder.cursorTargets(ProductTypeEnum.MYSQL);
        assertEquals(Set.of(ProductTypeEnum.POSTGRESQL), first.cursorTargets());
        assertThrows(UnsupportedOperationException.class, () -> first.cursorTargets().clear());
        var mapper = mock(LogicalTypeMapper.class);
        when(mapper.product()).thenReturn(ProductTypeEnum.POSTGRESQL);
        assertEquals(first.cursorTargets(), first.withTypeMapper(mapper).cursorTargets());
    }

    @Test
    void unknownDuplicateOrSourceWithoutCursorDeclarationsFailAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> source(ProductTypeEnum.MYSQL).cursorTargets(ProductTypeEnum.UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> source(ProductTypeEnum.MYSQL).cursorTargets(ProductTypeEnum.MYSQL, ProductTypeEnum.MYSQL));
        assertThrows(IllegalArgumentException.class,
                () -> DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL)
                        .cursorTargets(ProductTypeEnum.MYSQL).build());
    }

    private static DbSwitchProductDescriptor.Builder source(ProductTypeEnum product) {
        return DbSwitchProductDescriptor.builder(product)
                .reader(ignored -> mock(IDatabaseOperator.class))
                .synchronizer(ignored -> mock(IDatabaseSynchronize.class))
                .upsert(ignored -> mock(IDatabaseUpsertWriter.class))
                .declare(DatabaseOperation.SYNC_CURSOR);
    }
}
