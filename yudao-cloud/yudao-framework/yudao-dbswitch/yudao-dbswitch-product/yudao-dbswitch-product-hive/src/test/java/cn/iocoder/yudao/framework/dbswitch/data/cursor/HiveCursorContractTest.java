package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hive cursor contract: exact numeric/temporal domains, text keys native-only, persistable typed tuples. */
class HiveCursorContractTest {
    private static ColumnDescription column(String name, String typeName, int jdbc, int precision, int scale) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setFieldType(jdbc); column.setFieldTypeName(typeName);
        column.setPrecisionSize(precision); column.setScaleSize(scale);
        return column;
    }

    private static HiveCursorMetadata.Shape shape(String physical, Map<String, ColumnDescription> columns) {
        return new HiveCursorMetadata.Shape(columns, physical, List.of());
    }

    @Test void exactNumericAndTemporalDomainsWhileTextKeysStayNativeOnly() throws Exception {
        var columns = new LinkedHashMap<String, ColumnDescription>();
        columns.put("w", column("w", "bigint", Types.BIGINT, 19, 0));
        columns.put("id", column("id", "decimal", Types.DECIMAL, 20, 3));
        columns.put("ts", column("ts", "timestamp", Types.TIMESTAMP, 29, 9));
        columns.put("d", column("d", "date", Types.DATE, 10, 0));
        columns.put("note", column("note", "string", Types.VARCHAR, Integer.MAX_VALUE, 0));
        columns.put("fl", column("fl", "double", Types.DOUBLE, 15, 15));
        columns.put("raw", column("raw", "binary", Types.BINARY, Integer.MAX_VALUE, 0));
        var shape = shape("physical-a", columns);
        var contract = HiveCursorMetadata.keys(shape, List.of("w", "id", "ts", "d", "note"));
        assertEquals(ProductTypeEnum.HIVE, contract.product());
        assertEquals(List.of(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(
                        new BigDecimal("-9223372036854775808"), new BigDecimal("9223372036854775807"), 0)),
                new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-99999999999999999.999"),
                        new BigDecimal("99999999999999999.999"), 3)),
                CursorKeyContract.Domain.nativeOnly(), CursorKeyContract.Domain.nativeOnly(),
                CursorKeyContract.Domain.nativeOnly()), contract.keys());
        // Unordered or unsupported key domains fail before any connection is borrowed.
        for (var key : List.of("fl", "raw"))
            assertThrows(Exception.class, () -> HiveCursorMetadata.keys(shape, List.of("w", key)));
        HiveCursorMetadata.requireWatermark(columns.get("w"));
        HiveCursorMetadata.requireWatermark(columns.get("id"));
        HiveCursorMetadata.requireWatermark(columns.get("ts"));
        HiveCursorMetadata.requireWatermark(columns.get("d"));
        assertThrows(Exception.class, () -> HiveCursorMetadata.requireWatermark(columns.get("note")));
        assertThrows(Exception.class, () -> HiveCursorMetadata.requireWatermark(columns.get("fl")));
    }

    @Test void keyContractFingerprintDependsOnOrderedKeyTypesNotPhysicalIdentity() throws Exception {
        var left = new LinkedHashMap<String, ColumnDescription>();
        left.put("w", column("w", "bigint", Types.BIGINT, 19, 0));
        left.put("id", column("id", "string", Types.VARCHAR, Integer.MAX_VALUE, 0));
        var right = new LinkedHashMap<String, ColumnDescription>();
        right.put("w", column("w", "bigint", Types.BIGINT, 19, 0));
        right.put("id", column("id", "string", Types.VARCHAR, Integer.MAX_VALUE, 0));
        var same = HiveCursorMetadata.keys(shape("location-a", left), List.of("w", "id"));
        assertEquals(same, HiveCursorMetadata.keys(shape("location-b", right), List.of("w", "id")));
        // The ordered key list is part of the replay identity; key order changes the contract.
        var reordered = new LinkedHashMap<String, ColumnDescription>();
        reordered.put("w", column("w", "string", Types.VARCHAR, Integer.MAX_VALUE, 0));
        reordered.put("id", column("id", "bigint", Types.BIGINT, 19, 0));
        assertNotEquals(same, HiveCursorMetadata.keys(shape("location-a", reordered), List.of("w", "id")));
        // A narrower decimal target cannot replay a wider source domain.
        var wide = new LinkedHashMap<String, ColumnDescription>();
        wide.put("w", column("w", "decimal", Types.DECIMAL, 38, 9));
        var narrow = new LinkedHashMap<String, ColumnDescription>();
        narrow.put("w", column("w", "decimal", Types.DECIMAL, 20, 3));
        var source = new CursorKeyContract(ProductTypeEnum.HIVE, "a".repeat(64),
                List.of(CursorKeyContract.Domain.decimal(38, 9, false)));
        assertThrows(IllegalArgumentException.class, () -> source.requireReplayCompatible(
                HiveCursorMetadata.keys(shape("x", wide), List.of("w"))));
        assertThrows(IllegalArgumentException.class, () -> HiveCursorMetadata.keys(shape("x", narrow), List.of("w"))
                .requireReplayCompatible(source));
    }

    @Test void persistedTypedTuplesRoundTripThroughVerifiedHiveBindings() throws Exception {
        var bigint = column("w", "bigint", Types.BIGINT, 19, 0);
        var decimal = column("amt", "decimal", Types.DECIMAL, 20, 3);
        var timestamp = column("ts", "timestamp", Types.TIMESTAMP, 29, 9);
        var date = column("d", "date", Types.DATE, 10, 0);
        var text = column("note", "string", Types.VARCHAR, Integer.MAX_VALUE, 0);
        var charKey = column("code", "char", Types.CHAR, 7, 0);
        assertEquals(Types.VARCHAR, HiveCursorMetadata.persistedType(charKey));
        var value = new TypedCursorValue(Types.VARCHAR, "char", "c7     ");
        assertEquals("c7     ", value.jdbcValue());
        var statement = mock(PreparedStatement.class);
        HiveCursorMetadata.bind(statement, 1, 5L, bigint); verify(statement).setLong(1, 5L);
        HiveCursorMetadata.bind(statement, 2, new BigDecimal("5.125"), decimal); verify(statement).setString(2, "5.125");
        HiveCursorMetadata.bind(statement, 3, LocalDateTime.parse("2026-09-25T13:00:00.123456789"), timestamp);
        verify(statement).setString(3, "2026-09-25 13:00:00.123456789");
        HiveCursorMetadata.bind(statement, 4, LocalDate.parse("2026-09-25"), date); verify(statement).setString(4, "2026-09-25");
        HiveCursorMetadata.bind(statement, 5, "中", text);
        verify(statement).setString(5, java.util.HexFormat.of().formatHex("中".getBytes(StandardCharsets.UTF_8)));
        assertEquals("`amt` = CAST(? AS DECIMAL(20,3))", HiveCursorMetadata.equality(decimal));
        assertEquals("`ts` > CAST(? AS TIMESTAMP)", HiveCursorMetadata.comparison(timestamp, ">"));
        assertEquals("`note` < decode(unhex(?),'UTF-8')", HiveCursorMetadata.comparison(text, "<"));
        assertThrows(Exception.class, () -> HiveCursorMetadata.bind(statement, 1, null, bigint));
    }

    @Test void shapeFingerprintReflectsPhysicalDirectoryIdentityAndColumnShape() {
        var first = new LinkedHashMap<String, ColumnDescription>();
        first.put("w", column("w", "bigint", Types.BIGINT, 19, 0));
        // The physical identity string is materialized by read() from directory evidence plus the ordered column
        // shape; any material change changes the fingerprint. Column-shape sensitivity is proven on the real
        // server in MigrationCursorHiveTest.directoryIdentityChangesWhenTheSourceIsRecreatedOrReshaped.
        assertNotEquals(shape("location\0a", first).fingerprint(), shape("location\0b", first).fingerprint());
        assertEquals(shape("location\0a", first).fingerprint(), shape("location\0a", first).fingerprint());
    }
}
