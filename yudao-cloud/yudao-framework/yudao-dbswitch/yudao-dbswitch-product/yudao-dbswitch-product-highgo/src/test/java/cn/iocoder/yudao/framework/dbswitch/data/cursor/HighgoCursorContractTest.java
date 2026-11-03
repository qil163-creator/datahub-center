package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.HighgoCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.highgo.HighgoUpsertWriter;
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

class HighgoCursorContractTest {
    private static HighgoCursorMetadata.Column column(int number, String name, String type, int modifier, boolean notNull) {
        return new HighgoCursorMetadata.Column(number, name, type, modifier, notNull);
    }

    @Test void numericKeyDomainsCoverVerifiedHighgoIntegerAndDecimalTypesOnly() throws Exception {
        assertEquals(CursorKeyContract.Domain.integer(16, false), HighgoCursorMetadata.key(column(1, "k", "int2", -1, true)));
        assertEquals(CursorKeyContract.Domain.integer(32, false), HighgoCursorMetadata.key(column(1, "k", "int4", -1, true)));
        assertEquals(CursorKeyContract.Domain.integer(64, false), HighgoCursorMetadata.key(column(1, "k", "int8", -1, true)));
        assertEquals(CursorKeyContract.Domain.decimal(38, 18, false), HighgoCursorMetadata.key(column(1, "k", "numeric", 4 + (38 << 16) + 18, true)));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), HighgoCursorMetadata.key(column(1, "k", "varchar", 24, true)));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), HighgoCursorMetadata.key(column(1, "k", "date", -1, true)));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), HighgoCursorMetadata.key(column(1, "k", "timestamp", 6, true)));
        for (String type : List.of("bool", "bytea", "time", "timestamptz"))
            assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.key(column(1, "k", type, -1, true)));
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.key(column(1, "k", "numeric", 4 + (1001 << 16), true)));
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.key(column(1, "k", "int8", -1, false)));
    }

    @Test void watermarkRefusesTextButAcceptsNumericAndTemporalColumns() throws Exception {
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.watermark(column(1, "wm", "varchar", 24, true)));
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.watermark(column(1, "wm", "text", -1, true)));
        assertDoesNotThrow(() -> HighgoCursorMetadata.watermark(column(1, "wm", "int8", -1, true)));
        assertDoesNotThrow(() -> HighgoCursorMetadata.watermark(column(1, "wm", "numeric", 4 + (38 << 16), true)));
        assertDoesNotThrow(() -> HighgoCursorMetadata.watermark(column(1, "wm", "date", -1, true)));
        assertDoesNotThrow(() -> HighgoCursorMetadata.watermark(column(1, "wm", "timestamp", 6, true)));
    }

    @Test void contractFingerprintIsStableAndReplaysOnlyIdenticalHighgoKeyDomains() throws Exception {
        Map<String, HighgoCursorMetadata.Column> columns = new LinkedHashMap<>();
        columns.put("K1", column(2, "K1", "int8", -1, true));
        columns.put("K2", column(1, "K2", "numeric", 4 + (38 << 16), true));
        var shape = new HighgoCursorMetadata.Shape("a".repeat(64), "b".repeat(64), columns, List.of("K2", "K1"),
                List.of(List.of("K2", "K1")), "c".repeat(64));
        var contract = HighgoCursorMetadata.contract(shape, List.of("K2", "K1"));
        assertEquals(ProductTypeEnum.HIGHGO, contract.product());
        assertEquals(64, contract.nativeFingerprint().length());
        assertEquals(contract.nativeFingerprint(), HighgoCursorMetadata.contract(shape, List.of("K2", "K1")).nativeFingerprint());
        assertDoesNotThrow(() -> contract.requireReplayCompatible(contract));
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.contract(shape, List.of("K1", "K2")));
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.contract(shape, List.of("K1")));
        var postgres = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, "d".repeat(64),
                List.of(CursorKeyContract.Domain.decimal(38, 0, false), CursorKeyContract.Domain.integer(64, false)));
        assertDoesNotThrow(() -> contract.requireReplayCompatible(postgres));
        Map<String, HighgoCursorMetadata.Column> narrower = new LinkedHashMap<>();
        narrower.put("K1", column(2, "K1", "int4", -1, true));
        narrower.put("K2", column(1, "K2", "numeric", 4 + (38 << 16), true));
        var otherShape = new HighgoCursorMetadata.Shape("a".repeat(64), "b".repeat(64), narrower, List.of("K2", "K1"),
                List.of(List.of("K2", "K1")), "c".repeat(64));
        var other = HighgoCursorMetadata.contract(otherShape, List.of("K2", "K1"));
        assertThrows(IllegalArgumentException.class, () -> contract.requireReplayCompatible(other));
    }

    @Test void textKeyContractsStayNativeOnlyAndNeverReplayCrossProduct() throws Exception {
        Map<String, HighgoCursorMetadata.Column> columns = new LinkedHashMap<>();
        columns.put("TK", column(1, "TK", "varchar", 24, true));
        columns.put("WM", column(2, "WM", "int8", -1, true));
        var shape = new HighgoCursorMetadata.Shape("a".repeat(64), "b".repeat(64), columns, List.of("TK"),
                List.of(List.of("TK")), "p".repeat(64));
        var contract = HighgoCursorMetadata.contract(shape, List.of("TK"));
        assertNull(contract.keys().getFirst().numeric());
        assertDoesNotThrow(() -> contract.requireReplayCompatible(contract));
        var postgresText = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, "e".repeat(64),
                List.of(CursorKeyContract.Domain.nativeOnly()));
        assertThrows(IllegalArgumentException.class, () -> contract.requireReplayCompatible(postgresText));
        var sameButOtherProfile = new CursorKeyContract(ProductTypeEnum.HIGHGO, "f".repeat(64),
                List.of(CursorKeyContract.Domain.nativeOnly()));
        assertThrows(IllegalArgumentException.class, () -> contract.requireReplayCompatible(sameButOtherProfile));
    }

    @Test void typedCursorValuesCarryExactHighgoNumerics() {
        var value = TypedCursorValue.of(Types.NUMERIC, "numeric", new BigDecimal("-1E-18"));
        assertEquals("-0.000000000000000001", value.value());
        assertEquals(new BigDecimal("-1E-18"), value.jdbcValue());
        assertEquals(new BigDecimal("-1E-18"), TypedCursorValue.of(Types.NUMERIC, "numeric", new BigDecimal("-1E-18")).jdbcValue());
        assertThrows(IllegalArgumentException.class, () -> TypedCursorValue.of(Types.NUMERIC, "numeric", null));
        assertEquals(java.time.LocalDateTime.parse("2026-01-02T03:04:05.123456"),
                TypedCursorValue.of(Types.TIMESTAMP, "timestamp", java.time.LocalDateTime.parse("2026-01-02T03:04:05.123456")).jdbcValue());
    }

    @Test void pgModeExactnessKeepsEmptyTextButRejectsZeroContainingText() throws Exception {
        var text = column(1, "v", "text", -1, true);
        assertEquals("ok", HighgoCursorMetadata.exact("ok", text));
        assertEquals("", HighgoCursorMetadata.exact("", text));
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.exact("a\0", text));
        var ts = column(1, "ts", "timestamp", 6, true);
        assertThrows(java.sql.SQLException.class, () -> HighgoCursorMetadata.exact(
                java.time.LocalDateTime.parse("2026-01-02T03:04:05.123456789"), ts));
    }

    @Test void highgoDescriptorCarriesProductOwnedCursorAndUpsertFactories() {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(javax.sql.DataSource.class);
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIGHGO_6_0_4_2_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET"),
                registry.product(ProductTypeEnum.HIGHGO).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIGHGO_6_0_4_2_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED"),
                registry.product(ProductTypeEnum.HIGHGO).capabilities().get(DatabaseOperation.WRITE_UPSERT));
        assertInstanceOf(HighgoUpsertWriter.class, registry.upsertWriter(source, ProductTypeEnum.HIGHGO));
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.HighgoDatabaseOperator.class,
                registry.reader(source, ProductTypeEnum.HIGHGO));
        assertThrows(UnsupportedOperationException.class, () -> registry.atomicBatchWriter(source, ProductTypeEnum.HIGHGO));
        verifyNoInteractions(source);
    }

    @Test void writerNeedsAPreparedTargetBeforeKeyEvidence() {
        var writer = new HighgoUpsertWriter(new DriverManagerDataSource());
        assertThrows(IllegalStateException.class, writer::keyContract);
    }
}
