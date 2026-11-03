package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure key-domain and typed-cursor contracts for the OLAP cursor/UPSERT: no endpoints, no mocks of the servers. */
@Timeout(15)
class OlapCursorContractTest {

    @Test void dorisKeyDomainsAreExactIntegersDecimalsTemporalsOrNativeText() {
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-128"), new BigDecimal("127"), 0)),
                DorisCursorMetadata.domain(doris("t", "tinyint")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-9223372036854775808"), new BigDecimal("9223372036854775807"), 0)),
                DorisCursorMetadata.domain(doris("b", "bigint")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-999999999999999999999999999999999999999"),
                new BigDecimal("999999999999999999999999999999999999999"), 0)), DorisCursorMetadata.domain(doris("l", "largeint")),
                "LARGEINT is decimal(39,0): wider integer targets stay possible, narrower ones are rejected");
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-99999999999999999999.999999999999999999"),
                new BigDecimal("99999999999999999999.999999999999999999"), 18)), DorisCursorMetadata.domain(doris("d", "decimal(38,18)")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), DorisCursorMetadata.domain(doris("day", "date")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), DorisCursorMetadata.domain(doris("at", "datetime(6)")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), DorisCursorMetadata.domain(doris("s", "string")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), DorisCursorMetadata.domain(doris("v", "varchar(20)")));
        for (String kind : List.of("boolean", "float", "double"))
            assertThrows(IllegalArgumentException.class, () -> DorisCursorMetadata.domain(doris(kind, kind)),
                    kind + " is not a stably comparable key domain");
        for (String kind : List.of("tinyint", "bigint", "largeint", "decimal(38,18)", "date", "datetime(6)", "varchar(20)"))
            assertTrue(DorisCursorMetadata.supportedKeyType(doris(kind, kind)), kind);
        assertFalse(DorisCursorMetadata.supportedKeyType(doris("f", "float")));
    }

    @Test void starrocksKeyDomainsAreExactIntegersDecimalsTemporalsOrNativeText() {
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-9223372036854775808"), new BigDecimal("9223372036854775807"), 0)),
                StarrocksCursorMetadata.domain(starrocks("b", "bigint")));
        assertEquals(new CursorKeyContract.Domain(new CursorKeyContract.Numeric(new BigDecimal("-999999999999999999999999999999999999999"),
                new BigDecimal("999999999999999999999999999999999999999"), 0)), StarrocksCursorMetadata.domain(starrocks("l", "largeint")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), StarrocksCursorMetadata.domain(starrocks("day", "date")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), StarrocksCursorMetadata.domain(starrocks("at", "datetime")));
        assertEquals(CursorKeyContract.Domain.nativeOnly(), StarrocksCursorMetadata.domain(starrocks("v", "varchar(20)")));
        for (String kind : List.of("boolean", "float", "double", "varbinary"))
            assertThrows(IllegalArgumentException.class, () -> StarrocksCursorMetadata.domain(starrocks(kind, kind)),
                    kind + " is not a stably comparable key domain");
    }

    @Test void typedCursorValuesRoundTripExactKeyComponents() {
        assertEquals(5, ((Number) DorisCursorMetadata.cursorComponent(doris("b", "bigint"), DorisCursorMetadata.cursorValue(doris("b", "bigint"), 5L))).longValue());
        assertEquals(new BigDecimal("12.340000000000000000"), DorisCursorMetadata.cursorComponent(doris("d", "decimal(38,18)"),
                DorisCursorMetadata.cursorValue(doris("d", "decimal(38,18)"), new BigDecimal("12.340000000000000000"))));
        assertEquals("170141183460469231731687303715884105727", DorisCursorMetadata.cursorComponent(doris("l", "largeint"),
                DorisCursorMetadata.cursorValue(doris("l", "largeint"), new BigDecimal("170141183460469231731687303715884105727"))).toString());
        assertEquals(LocalDate.of(2026, 10, 1), DorisCursorMetadata.cursorComponent(doris("day", "date"),
                DorisCursorMetadata.cursorValue(doris("day", "date"), LocalDate.of(2026, 10, 1))));
        assertEquals(LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000), DorisCursorMetadata.cursorComponent(doris("at", "datetime(6)"),
                DorisCursorMetadata.cursorValue(doris("at", "datetime(6)"), LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000))));
        assertEquals("中文", DorisCursorMetadata.cursorComponent(doris("v", "varchar(20)"),
                DorisCursorMetadata.cursorValue(doris("v", "varchar(20)"), "中文")));
        assertEquals(Types.BIGINT, DorisCursorMetadata.cursorValue(doris("b", "bigint"), 5L).jdbcType());
        assertEquals(Types.NUMERIC, DorisCursorMetadata.cursorValue(doris("d", "decimal(38,18)"), BigDecimal.ONE).jdbcType());
        assertEquals(Types.VARCHAR, DorisCursorMetadata.cursorValue(doris("s", "string"), "x").jdbcType());
        // Persisted component validation rejects a changed native type or an out-of-range value.
        var column = doris("b", "bigint");
        assertThrows(IllegalArgumentException.class, () -> DorisCursorMetadata.cursorComponent(column, TypedCursorValue.of(Types.BIGINT, "int", 5L)));
        assertThrows(IllegalArgumentException.class, () -> DorisCursorMetadata.cursorComponent(doris("d", "decimal(3,2)"),
                TypedCursorValue.of(Types.NUMERIC, "decimal(3,2)", new BigDecimal("12.34"))));
    }

    @Test void crossProductReplayAdmitsOnlyLosslessNumericDomains() {
        // Identical products require the identical native comparison contract.
        var dorisBigint = new CursorKeyContract(ProductTypeEnum.DORIS, fingerprint("bigint"), List.of(CursorKeyContract.Domain.integer(64, false)));
        var dorisBigintAgain = new CursorKeyContract(ProductTypeEnum.DORIS, fingerprint("bigint"), List.of(CursorKeyContract.Domain.integer(64, false)));
        var dorisInt = new CursorKeyContract(ProductTypeEnum.DORIS, fingerprint("int"), List.of(CursorKeyContract.Domain.integer(32, false)));
        assertDoesNotThrow(() -> dorisBigint.requireReplayCompatible(dorisBigintAgain));
        assertThrows(IllegalArgumentException.class, () -> dorisBigint.requireReplayCompatible(dorisInt),
                "The same product demands the identical native fingerprint");
        // Different products compare exact numeric domains: Doris bigint replays into a PostgreSQL bigint, not into a 32-bit key.
        var postgresBigint = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.integer(64, false)));
        var postgresInt = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.integer(32, false)));
        assertDoesNotThrow(() -> dorisBigint.requireReplayCompatible(postgresBigint));
        assertThrows(IllegalArgumentException.class, () -> dorisBigint.requireReplayCompatible(postgresInt));
        assertThrows(IllegalArgumentException.class, () -> new CursorKeyContract(ProductTypeEnum.DORIS, fingerprint("date"), List.of(CursorKeyContract.Domain.nativeOnly()))
                .requireReplayCompatible(new CursorKeyContract(ProductTypeEnum.POSTGRESQL, fingerprint("pg"), List.of(CursorKeyContract.Domain.nativeOnly()))),
                "Nonnumeric keys replay only under the identical native contract");
    }

    private static DorisNativeSchema.Column doris(String name, String type) {
        return new DorisNativeSchema.Column(name, type, false, null, "");
    }
    private static StarrocksJdbcSupport.Column starrocks(String name, String type) {
        String kind = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
        int precision = type.startsWith("decimal(") ? Integer.parseInt(type.substring(8, type.indexOf(','))) : 0;
        int scale = type.startsWith("decimal(") ? Integer.parseInt(type.substring(type.indexOf(',') + 1, type.length() - 1)) : 0;
        return new StarrocksJdbcSupport.Column(name, type, StarrocksJdbcSupport.jdbcType(kind), precision, scale, false, "");
    }
    private static String fingerprint(String text) { return DigestUtil.sha256Hex(text); }
}
