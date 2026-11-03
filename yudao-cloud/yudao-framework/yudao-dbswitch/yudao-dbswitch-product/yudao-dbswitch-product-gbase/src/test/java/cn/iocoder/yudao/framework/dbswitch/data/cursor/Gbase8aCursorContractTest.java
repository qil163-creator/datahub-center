package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure key-domain and typed-cursor contracts for the GBase 8a cursor: no endpoints, no mocks of the server. */
@Timeout(15)
class Gbase8aCursorContractTest {

    @Test void gbase8aKeyDomainsAreExactIntegersDecimalsTemporalsOrVarchar() {
        // GBase 8a BIGINT is two short of the standard signed 64-bit bounds on the verified 8.6.2.43 server.
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-9223372036854775806"),
                        new BigDecimal("9223372036854775806"), 0)),
                Gbase8aCursorMetadata.domain(column("k", "bigint(20)", "bigint", 19, 0)));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-128"), new BigDecimal("127"), 0)),
                Gbase8aCursorMetadata.domain(column("t", "tinyint(4)", "tinyint", 3, 0)));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-2147483648"), new BigDecimal("2147483647"), 0)),
                Gbase8aCursorMetadata.domain(column("i", "int(11)", "int", 10, 0)));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-999999999999999999999999999999.99999999"),
                        new BigDecimal("999999999999999999999999999999.99999999"), 8)),
                Gbase8aCursorMetadata.domain(column("d", "decimal(38,8)", "decimal", 38, 8)));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), Gbase8aCursorMetadata.domain(column("at", "datetime", "datetime", 0, 0)));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), Gbase8aCursorMetadata.domain(column("day", "date", "date", 0, 0)));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), Gbase8aCursorMetadata.domain(column("s", "varchar(64)", "varchar", 64, 0)));
        for (String kind : List.of("char(4)", "text", "float", "double", "time", "blob"))
            assertThrows(IllegalArgumentException.class, () -> Gbase8aCursorMetadata.domain(column(kind, kind, kind, 0, 0)),
                    kind + " is not a stably comparable GBase 8a key domain");
        for (String kind : List.of("tinyint", "smallint", "int", "bigint", "decimal", "datetime", "date", "varchar"))
            assertTrue(Gbase8aCursorMetadata.supportedKeyType(column(kind, kind, kind, 0, 0)), kind);
        assertFalse(Gbase8aCursorMetadata.supportedKeyType(column("f", "float", "float", 0, 0)));
        assertFalse(Gbase8aCursorMetadata.supportedKeyType(column("t", "text", "text", 0, 0)));
    }

    @Test void typedCursorValuesRoundTripExactKeyComponents() {
        assertEquals(5L, Gbase8aCursorMetadata.cursorComponent(column("k", "bigint(20)", "bigint", 19, 0),
                Gbase8aCursorMetadata.cursorValue(column("k", "bigint(20)", "bigint", 19, 0), 5L)));
        assertEquals(new BigDecimal("12.34000000"), Gbase8aCursorMetadata.cursorComponent(column("d", "decimal(38,8)", "decimal", 38, 8),
                Gbase8aCursorMetadata.cursorValue(column("d", "decimal(38,8)", "decimal", 38, 8), new BigDecimal("12.34000000"))));
        assertEquals(LocalDate.of(2026, 10, 1), Gbase8aCursorMetadata.cursorComponent(column("day", "date", "date", 0, 0),
                Gbase8aCursorMetadata.cursorValue(column("day", "date", "date", 0, 0), LocalDate.of(2026, 10, 1))));
        assertEquals(LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000), Gbase8aCursorMetadata.cursorComponent(
                column("at", "datetime", "datetime", 0, 0),
                Gbase8aCursorMetadata.cursorValue(column("at", "datetime", "datetime", 0, 0),
                        LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000))));
        assertEquals("中文", Gbase8aCursorMetadata.cursorComponent(column("s", "varchar(64)", "varchar", 64, 0),
                Gbase8aCursorMetadata.cursorValue(column("s", "varchar(64)", "varchar", 64, 0), "中文")));
        assertEquals(Types.BIGINT, Gbase8aCursorMetadata.cursorValue(column("k", "bigint(20)", "bigint", 19, 0), 5L).jdbcType());
        assertEquals(Types.NUMERIC, Gbase8aCursorMetadata.cursorValue(column("d", "decimal(38,8)", "decimal", 38, 8), new BigDecimal("1.00000000")).jdbcType());
        assertEquals(Types.VARCHAR, Gbase8aCursorMetadata.cursorValue(column("s", "varchar(64)", "varchar", 64, 0), "x").jdbcType());
        // Persisted component validation rejects a changed native type or an out-of-range value.
        var bigint = column("k", "bigint(20)", "bigint", 19, 0);
        assertThrows(IllegalArgumentException.class, () -> Gbase8aCursorMetadata.cursorComponent(bigint, TypedCursorValue.of(Types.BIGINT, "int(11)", 5L)));
        assertThrows(IllegalArgumentException.class, () -> Gbase8aCursorMetadata.cursorComponent(bigint,
                TypedCursorValue.of(Types.BIGINT, "bigint(20)", new BigDecimal("9223372036854775807"))));
        var decimal = column("d", "decimal(38,8)", "decimal", 38, 8);
        assertThrows(IllegalArgumentException.class, () -> Gbase8aCursorMetadata.cursorComponent(decimal,
                TypedCursorValue.of(Types.NUMERIC, "decimal(38,8)", new BigDecimal("1.123456789"))));
    }

    @Test void crossProductReplayAdmitsOnlyLosslessNumericDomains() {
        // Identical products require the identical native comparison contract.
        var gbaseBigint = new CursorKeyContract(ProductTypeEnum.GBASE8A, fingerprint("bigint"), List.of(
                new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-9223372036854775806"),
                        new BigDecimal("9223372036854775806"), 0))));
        var gbaseBigintAgain = new CursorKeyContract(ProductTypeEnum.GBASE8A, fingerprint("bigint"), List.of(
                new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-9223372036854775806"),
                        new BigDecimal("9223372036854775806"), 0))));
        var gbaseInt = new CursorKeyContract(ProductTypeEnum.GBASE8A, fingerprint("int"), List.of(CursorKeyContract.Domain.integer(32, false)));
        assertDoesNotThrow(() -> gbaseBigint.requireReplayCompatible(gbaseBigintAgain));
        assertThrows(IllegalArgumentException.class, () -> gbaseBigint.requireReplayCompatible(gbaseInt),
                "The same product demands the identical native fingerprint");
        // GBase 8a bigint replays into a PostgreSQL bigint whose domain is wider.
        var postgresBigint = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.integer(64, false)));
        assertDoesNotThrow(() -> gbaseBigint.requireReplayCompatible(postgresBigint));
        // A PostgreSQL bigint does not replay into GBase 8a: the extreme signed values cannot round-trip.
        assertThrows(IllegalArgumentException.class, () -> postgresBigint.requireReplayCompatible(gbaseBigint));
        // Nonnumeric keys replay only under the identical native contract.
        assertThrows(IllegalArgumentException.class, () -> new CursorKeyContract(ProductTypeEnum.GBASE8A, fingerprint("text"), List.of(CursorKeyContract.Domain.nativeOnly()))
                .requireReplayCompatible(new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.nativeOnly()))));
    }

    @Test void invalidKeyColumnsAndBigIntegerComponentsAreRejected() {
        var bigint = column("k", "bigint(20)", "bigint", 19, 0);
        assertEquals(Long.valueOf("-9223372036854775806"), Gbase8aCursorMetadata.cursorComponent(bigint,
                TypedCursorValue.of(Types.BIGINT, "bigint(20)", new BigInteger("-9223372036854775806"))));
        assertThrows(IllegalArgumentException.class, () -> Gbase8aCursorMetadata.cursorComponent(bigint,
                TypedCursorValue.of(Types.BIGINT, "bigint(20)", new BigInteger("9223372036854775807"))));
    }

    private static Gbase8aCursorMetadata.Column column(String name, String nativeType, String dataType, int precision, int scale) {
        return new Gbase8aCursorMetadata.Column(name, nativeType, dataType, false, precision == 0 ? null : precision,
                precision == 0 ? null : scale, "utf8mb4", "utf8mb4_bin", "", null, null);
    }
    private static String fingerprint(String text) { return DigestUtil.sha256Hex(text); }
}
