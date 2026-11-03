package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseCursorMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure key-domain and typed-cursor contracts for the ClickHouse cursor/UPSERT: no endpoints, no mocks of the server. */
@Timeout(15)
class ClickhouseCursorContractTest {

    @Test void clickhouseKeyDomainsAreExactIntegersDecimalsTemporalsOrNativeText() {
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-128"), new BigDecimal("127"), 0)),
                ClickhouseCursorMetadata.domain(column("i8", "Int8")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(BigDecimal.ZERO, new BigDecimal("255"), 0)),
                ClickhouseCursorMetadata.domain(column("u8", "UInt8")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-9223372036854775808"),
                new BigDecimal("9223372036854775807"), 0)), ClickhouseCursorMetadata.domain(column("i64", "Int64")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(BigDecimal.ZERO, new BigDecimal("18446744073709551615"), 0)),
                ClickhouseCursorMetadata.domain(column("u64", "UInt64")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-99999999999999999999999999999.999999999"),
                new BigDecimal("99999999999999999999999999999.999999999"), 9)),
                ClickhouseCursorMetadata.domain(column("d", "Decimal(38,9)")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), ClickhouseCursorMetadata.domain(column("day", "Date32")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), ClickhouseCursorMetadata.domain(column("at", "DateTime64(6)")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), ClickhouseCursorMetadata.domain(column("s", "String")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), ClickhouseCursorMetadata.domain(column("l", "LowCardinality(String)")));
        for (String kind : List.of("Bool", "Float64", "Nullable(Int64)", "FixedString(4)"))
            assertThrows(IllegalArgumentException.class, () -> ClickhouseCursorMetadata.domain(column(kind, kind)),
                    kind + " is not a stably comparable key domain");
        for (String kind : List.of("Int8", "UInt8", "Int64", "UInt64", "Decimal(38,9)", "Date32", "DateTime64(6)", "String"))
            assertTrue(ClickhouseCursorMetadata.supportedKeyType(column(kind, kind)), kind);
        assertFalse(ClickhouseCursorMetadata.supportedKeyType(column("f", "Float64")));
        assertFalse(ClickhouseCursorMetadata.supportedKeyType(column("n", "Nullable(Int64)")));
    }

    @Test void typedCursorValuesRoundTripExactKeyComponents() {
        assertEquals(5L, ClickhouseCursorMetadata.cursorComponent(column("i64", "Int64"),
                ClickhouseCursorMetadata.cursorValue(column("i64", "Int64"), 5L)));
        assertEquals(new BigInteger("18446744073709551615"), ClickhouseCursorMetadata.cursorComponent(column("u64", "UInt64"),
                ClickhouseCursorMetadata.cursorValue(column("u64", "UInt64"), new BigInteger("18446744073709551615"))));
        assertEquals(new BigDecimal("12.340000000"), ClickhouseCursorMetadata.cursorComponent(column("d", "Decimal(38,9)"),
                ClickhouseCursorMetadata.cursorValue(column("d", "Decimal(38,9)"), new BigDecimal("12.340000000"))));
        assertEquals(LocalDate.of(2026, 10, 1), ClickhouseCursorMetadata.cursorComponent(column("day", "Date32"),
                ClickhouseCursorMetadata.cursorValue(column("day", "Date32"), LocalDate.of(2026, 10, 1))));
        assertEquals(LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000), ClickhouseCursorMetadata.cursorComponent(column("at", "DateTime64(6)"),
                ClickhouseCursorMetadata.cursorValue(column("at", "DateTime64(6)"), LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000))));
        assertEquals("中文", ClickhouseCursorMetadata.cursorComponent(column("s", "String"),
                ClickhouseCursorMetadata.cursorValue(column("s", "String"), "中文")));
        assertEquals(Types.BIGINT, ClickhouseCursorMetadata.cursorValue(column("i64", "Int64"), 5L).jdbcType());
        assertEquals(Types.NUMERIC, ClickhouseCursorMetadata.cursorValue(column("u64", "UInt64"), BigInteger.ONE).jdbcType());
        assertEquals(Types.NUMERIC, ClickhouseCursorMetadata.cursorValue(column("d", "Decimal(38,9)"), new BigDecimal("1.000000000")).jdbcType());
        assertEquals(Types.VARCHAR, ClickhouseCursorMetadata.cursorValue(column("s", "String"), "x").jdbcType());
        // Persisted component validation rejects a changed native type or an out-of-range value.
        var integer = column("i64", "Int64");
        assertThrows(IllegalArgumentException.class, () -> ClickhouseCursorMetadata.cursorComponent(integer, TypedCursorValue.of(Types.BIGINT, "Int32", 5L)));
        assertThrows(IllegalArgumentException.class, () -> ClickhouseCursorMetadata.cursorComponent(integer,
                TypedCursorValue.of(Types.BIGINT, "Int64", new BigDecimal("9223372036854775808"))));
        assertThrows(IllegalArgumentException.class, () -> ClickhouseCursorMetadata.cursorComponent(column("u64", "UInt64"),
                TypedCursorValue.of(Types.NUMERIC, "UInt64", new BigDecimal("-1"))));
    }

    @Test void crossProductReplayAdmitsOnlyLosslessNumericDomains() {
        // Identical products require the identical native comparison contract.
        var clickhouseInt64 = new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, fingerprint("int64"), List.of(CursorKeyContract.Domain.integer(64, false)));
        var clickhouseInt64Again = new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, fingerprint("int64"), List.of(CursorKeyContract.Domain.integer(64, false)));
        var clickhouseInt32 = new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, fingerprint("int32"), List.of(CursorKeyContract.Domain.integer(32, false)));
        assertDoesNotThrow(() -> clickhouseInt64.requireReplayCompatible(clickhouseInt64Again));
        assertThrows(IllegalArgumentException.class, () -> clickhouseInt64.requireReplayCompatible(clickhouseInt32),
                "The same product demands the identical native fingerprint");
        // Different products compare exact numeric domains: ClickHouse Int64 replays into a PostgreSQL bigint.
        var postgresBigint = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.integer(64, false)));
        assertDoesNotThrow(() -> clickhouseInt64.requireReplayCompatible(postgresBigint));
        // ClickHouse UInt64 needs a numeric(20,0) target; a signed bigint would silently wrap.
        var clickhouseUInt64 = new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, fingerprint("uint64"), List.of(CursorKeyContract.Domain.integer(64, true)));
        var postgresNumeric = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("numeric"), List.of(
                new CursorKeyContract.Domain(new CursorKeyContract.Numeric(BigDecimal.ZERO, new BigDecimal("18446744073709551615"), 0))));
        assertDoesNotThrow(() -> clickhouseUInt64.requireReplayCompatible(postgresNumeric));
        assertThrows(IllegalArgumentException.class, () -> clickhouseUInt64.requireReplayCompatible(postgresBigint));
        // Nonnumeric keys replay only under the identical native contract.
        assertThrows(IllegalArgumentException.class, () -> new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, fingerprint("text"), List.of(CursorKeyContract.Domain.nativeOnly()))
                .requireReplayCompatible(new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.nativeOnly()))));
    }

    private static ClickhouseCursorMetadata.Column column(String name, String type) {
        int precision = 0, scale = 0;
        if (type.startsWith("Decimal(")) {
            int comma = type.indexOf(','); precision = Integer.parseInt(type.substring(8, comma).trim());
            scale = Integer.parseInt(type.substring(comma + 1, type.length() - 1).trim());
        }
        return new ClickhouseCursorMetadata.Column(name, type, Types.OTHER, precision, scale,
                type.startsWith("Nullable("), false);
    }
    private static String fingerprint(String text) { return DigestUtil.sha256Hex(text); }
}
