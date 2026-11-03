package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.GreenplumCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GreenplumCursorContractTest {
    private static GreenplumCursorMetadata.Column column(int number, String name, String type, int modifier, boolean notNull) {
        return new GreenplumCursorMetadata.Column(number, name, type, modifier, notNull);
    }

    @Test void numericKeyDomainsCoverVerifiedGreenplumIntegerAndDecimalTypesOnly() throws Exception {
        assertEquals(CursorKeyContract.Domain.integer(16, false), GreenplumCursorMetadata.numeric(column(1, "k", "int2", -1, true)));
        assertEquals(CursorKeyContract.Domain.integer(32, false), GreenplumCursorMetadata.numeric(column(1, "k", "int4", -1, true)));
        assertEquals(CursorKeyContract.Domain.integer(64, false), GreenplumCursorMetadata.numeric(column(1, "k", "int8", -1, true)));
        assertEquals(CursorKeyContract.Domain.decimal(38, 18, false), GreenplumCursorMetadata.numeric(column(1, "k", "numeric", 4 + (38 << 16) + 18, true)));
        for (String type : List.of("varchar", "text", "date", "timestamp", "time", "bool", "bytea", "timestamptz"))
            assertThrows(java.sql.SQLException.class, () -> GreenplumCursorMetadata.numeric(column(1, "k", type, -1, true)));
        assertThrows(java.sql.SQLException.class, () -> GreenplumCursorMetadata.numeric(column(1, "k", "numeric", 4 + (1001 << 16), true)));
    }

    @Test void contractFingerprintIsStableAndReplaysOnlyIdenticalGreenplumKeyDomains() throws Exception {
        Map<String, GreenplumCursorMetadata.Column> columns = new LinkedHashMap<>();
        columns.put("K1", column(2, "K1", "int8", -1, true));
        columns.put("K2", column(1, "K2", "numeric", 4 + (38 << 16), true));
        var shape = new GreenplumCursorMetadata.Shape("a".repeat(64), "b".repeat(64), columns, List.of("K2", "K1"), List.of("p", "1 2"));
        var contract = GreenplumCursorMetadata.contract(shape, List.of("K2", "K1"));
        assertEquals(ProductTypeEnum.GREENPLUM, contract.product());
        assertEquals(64, contract.nativeFingerprint().length());
        assertEquals(contract.nativeFingerprint(), GreenplumCursorMetadata.contract(shape, List.of("K2", "K1")).nativeFingerprint());
        assertDoesNotThrow(() -> contract.requireReplayCompatible(contract));
        assertThrows(java.sql.SQLException.class, () -> GreenplumCursorMetadata.contract(shape, List.of("K1", "K2")));
        Map<String, GreenplumCursorMetadata.Column> narrower = new LinkedHashMap<>();
        narrower.put("K1", column(2, "K1", "int4", -1, true));
        narrower.put("K2", column(1, "K2", "numeric", 4 + (38 << 16), true));
        var otherShape = new GreenplumCursorMetadata.Shape("a".repeat(64), "b".repeat(64), narrower, List.of("K2", "K1"), List.of("p", "1 2"));
        var other = GreenplumCursorMetadata.contract(otherShape, List.of("K2", "K1"));
        assertThrows(IllegalArgumentException.class, () -> contract.requireReplayCompatible(other));
        var postgres = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, "c".repeat(64),
                List.of(CursorKeyContract.Domain.decimal(38, 0, false), CursorKeyContract.Domain.integer(64, false)));
        assertDoesNotThrow(() -> contract.requireReplayCompatible(postgres));
        assertThrows(java.sql.SQLException.class, () -> GreenplumCursorMetadata.contract(shape, List.of("K1")));
    }

    @Test void typedCursorValuesCarryExactGreenplumNumerics() {
        var value = TypedCursorValue.of(Types.NUMERIC, "numeric", new BigDecimal("-1E-18"));
        assertEquals("-0.000000000000000001", value.value());
        assertEquals(new BigDecimal("-1E-18"), value.jdbcValue());
        assertEquals(new BigDecimal("-1E-18"), TypedCursorValue.of(Types.NUMERIC, "numeric", new BigDecimal("-1E-18")).jdbcValue());
        assertThrows(IllegalArgumentException.class, () -> TypedCursorValue.of(Types.NUMERIC, "numeric", null));
    }

    @Test void greenplumDescriptorCarriesProductOwnedCursorAndUpsertFactories() {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(javax.sql.DataSource.class);
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GREENPLUM_HEAP_HASH_PINNED_GENERATION_REPEATABLE_READ_NUMERIC_KEYSET"),
                registry.product(ProductTypeEnum.GREENPLUM).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GREENPLUM_HEAP_HASH_ON_CONFLICT_EXACT_NUMERIC_PRIMARY_KEY_COMMIT_RETURNED"),
                registry.product(ProductTypeEnum.GREENPLUM).capabilities().get(DatabaseOperation.WRITE_UPSERT));
        assertInstanceOf(GreenplumUpsertWriter.class, registry.upsertWriter(source, ProductTypeEnum.GREENPLUM));
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.GreenplumDatabaseOperator.class,
                registry.reader(source, ProductTypeEnum.GREENPLUM));
        assertThrows(UnsupportedOperationException.class, () -> registry.atomicBatchWriter(source, ProductTypeEnum.GREENPLUM));
        verifyNoInteractions(source);
    }

    @Test void writerNeedsAPreparedTargetBeforeKeyEvidence() {
        var writer = new GreenplumUpsertWriter(new DriverManagerDataSource());
        assertThrows(IllegalStateException.class, writer::keyContract);
    }
}
