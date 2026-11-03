package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure contract evidence for the ES watermark domain: classification, exact tuple round trips, bounds and order. */
class ElasticsearchCursorContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void watermarkFamiliesAreExactlyTheSortableDocValuesTypes() {
        assertEquals(ElasticsearchCursorKeys.Family.KEYWORD, family("keyword"));
        assertEquals(ElasticsearchCursorKeys.Family.INTEGER, family("byte"));
        assertEquals(ElasticsearchCursorKeys.Family.INTEGER, family("short"));
        assertEquals(ElasticsearchCursorKeys.Family.INTEGER, family("integer"));
        assertEquals(ElasticsearchCursorKeys.Family.LONG, family("long"));
        assertEquals(ElasticsearchCursorKeys.Family.UNSIGNED_LONG, family("unsigned_long"));
        assertEquals(ElasticsearchCursorKeys.Family.DATE, family("date"));
        assertEquals(ElasticsearchCursorKeys.Family.DATE_NANOS, family("date_nanos"));
        for (String type : List.of("text", "wildcard", "constant_keyword", "ip", "boolean", "float", "half_float", "double", "geo_point"))
            assertNull(ElasticsearchCursorKeys.familyOf(field(type)), type);
        assertNull(ElasticsearchCursorKeys.familyOf(null));
    }

    @Test void tupleValuesRoundTripExactlyForEveryFamily() throws Exception {
        roundTrip(ElasticsearchCursorKeys.Family.KEYWORD, "中文-value");
        roundTrip(ElasticsearchCursorKeys.Family.INTEGER, Integer.MIN_VALUE);
        roundTrip(ElasticsearchCursorKeys.Family.INTEGER, Integer.MAX_VALUE);
        roundTrip(ElasticsearchCursorKeys.Family.LONG, Long.MIN_VALUE);
        roundTrip(ElasticsearchCursorKeys.Family.LONG, Long.MAX_VALUE);
        roundTrip(ElasticsearchCursorKeys.Family.UNSIGNED_LONG, ElasticsearchCursorKeys.UNSIGNED_LONG_LIMIT.subtract(BigInteger.ONE));
        roundTrip(ElasticsearchCursorKeys.Family.DATE, Instant.ofEpochMilli(1_704_067_200_500L));
        roundTrip(ElasticsearchCursorKeys.Family.DATE_NANOS, Instant.ofEpochSecond(1_704_067_200L, 123_456_789L));
    }

    @Test void tupleTypeDriftAndOutOfRangeUnsignedValuesFailClosed() throws Exception {
        var family = ElasticsearchCursorKeys.Family.LONG;
        assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.decode(family,
                TypedCursorValue.of(Types.BIGINT, "unsigned_long", 1L)));
        assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.decode(family,
                TypedCursorValue.of(Types.INTEGER, "long", 1)));
        assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.decode(ElasticsearchCursorKeys.Family.UNSIGNED_LONG,
                TypedCursorValue.of(Types.NUMERIC, "unsigned_long", new java.math.BigDecimal("-1"))));
        assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.decode(ElasticsearchCursorKeys.Family.UNSIGNED_LONG,
                TypedCursorValue.of(Types.NUMERIC, "unsigned_long", new java.math.BigDecimal(ElasticsearchCursorKeys.UNSIGNED_LONG_LIMIT))));
    }

    @Test void serverSortValuesAreDecodedExactlyAndNullSortsFailClosed() throws Exception {
        assertEquals("alpha", ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.KEYWORD, JSON.readTree("\"alpha\"")));
        assertEquals(10, ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.INTEGER, JSON.readTree("10")));
        assertEquals(Long.MAX_VALUE, ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.LONG, JSON.readTree("9223372036854775807")));
        assertEquals(new BigInteger("18446744073709551615"), ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.UNSIGNED_LONG, JSON.readTree("18446744073709551615")));
        assertEquals(Instant.ofEpochMilli(500L), ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.DATE, JSON.readTree("500")));
        assertEquals(Instant.ofEpochSecond(1L, 123_456_789L), ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.DATE_NANOS, JSON.readTree("1123456789")));
        assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.KEYWORD, JSON.readTree("null")));
        assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.sortValue(ElasticsearchCursorKeys.Family.KEYWORD, JSON.readTree("1")));
    }

    @Test void rangeBoundsUseExactNativeCarriers() throws Exception {
        assertEquals("alpha", ElasticsearchCursorKeys.bound(ElasticsearchCursorKeys.Family.KEYWORD, "alpha"));
        assertEquals(7, ElasticsearchCursorKeys.bound(ElasticsearchCursorKeys.Family.INTEGER, 7));
        assertEquals("18446744073709551615", ElasticsearchCursorKeys.bound(ElasticsearchCursorKeys.Family.UNSIGNED_LONG,
                new BigInteger("18446744073709551615")));
        assertEquals("2024-01-01T00:00:00.123456789Z", ElasticsearchCursorKeys.bound(ElasticsearchCursorKeys.Family.DATE_NANOS,
                Instant.ofEpochSecond(1_704_067_200L, 123_456_789L)));
    }

    @Test void keywordOrderMatchesLuceneUtf8ByteOrder() {
        assertTrue(ElasticsearchCursorKeys.compare(ElasticsearchCursorKeys.Family.KEYWORD, "ä", "z") > 0);
        assertTrue(ElasticsearchCursorKeys.compare(ElasticsearchCursorKeys.Family.KEYWORD, "a", "b") < 0);
        assertEquals(0, ElasticsearchCursorKeys.compare(ElasticsearchCursorKeys.Family.KEYWORD, "中文", "中文"));
        assertTrue(ElasticsearchCursorKeys.compareId("a", "aa") < 0);
        assertEquals(0, ElasticsearchCursorKeys.compareId("id", "id"));
    }

    @Test void watermarkFieldPreflightRejectsPartialOrNormalizedIndexation() throws Exception {
        ElasticsearchCursorKeys.requireWatermarkField(JSON.readTree("{\"type\":\"keyword\"}"));
        ElasticsearchCursorKeys.requireWatermarkField(JSON.readTree("{\"type\":\"long\",\"doc_values\":true}"));
        for (String definition : List.of("{\"type\":\"keyword\",\"ignore_above\":64}",
                "{\"type\":\"keyword\",\"normalizer\":\"lower\"}", "{\"type\":\"long\",\"ignore_malformed\":true}",
                "{\"type\":\"long\",\"index\":false}", "{\"type\":\"long\",\"doc_values\":false}",
                "{\"type\":\"keyword\",\"time_series_metric\":\"counter\"}"))
            assertThrows(SQLException.class, () -> ElasticsearchCursorKeys.requireWatermarkField(JSON.readTree(definition)), definition);
    }

    @Test void mappingFingerprintIsDeterministicAndOrderInsensitive() throws Exception {
        String first = "{\"dynamic\":\"strict\",\"properties\":{\"a\":{\"type\":\"long\"},\"b\":{\"type\":\"keyword\"}}}";
        String second = "{\"properties\":{\"b\":{\"type\":\"keyword\"},\"a\":{\"type\":\"long\"}},\"dynamic\":\"strict\"}";
        assertEquals(ElasticsearchCursorKeys.mappingFingerprint(JSON.readTree(first)),
                ElasticsearchCursorKeys.mappingFingerprint(JSON.readTree(second)));
        assertNotEquals(ElasticsearchCursorKeys.mappingFingerprint(JSON.readTree(first)),
                ElasticsearchCursorKeys.mappingFingerprint(JSON.readTree("{\"dynamic\":\"strict\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"keyword\"}}}")));
    }

    @Test void idKeyContractBindsOnlyTheIdenticalNativeContract() {
        var contract = ElasticsearchCursorKeys.keyContract();
        assertEquals(ProductTypeEnum.ELASTICSEARCH, contract.product());
        assertEquals(64, contract.nativeFingerprint().length());
        assertEquals(1, contract.keys().size());
        assertNull(contract.keys().getFirst().numeric());
        contract.requireReplayCompatible(ElasticsearchCursorKeys.keyContract());
        var mongo = new CursorKeyContract(ProductTypeEnum.MONGODB,
                cn.hutool.crypto.digest.DigestUtil.sha256Hex("MONGODB_ID_KEY_V1"), List.of(CursorKeyContract.Domain.nativeOnly()));
        assertThrows(IllegalArgumentException.class, () -> contract.requireReplayCompatible(mongo));
    }

    @Test void typedCursorTupleCarriesTheWatermarkWithoutDelimiterConcatenation() {
        var tuple = new TypedCursor(1, List.of(ElasticsearchCursorKeys.value(ElasticsearchCursorKeys.Family.LONG, 42L)));
        assertEquals(1, tuple.version()); assertEquals(1, tuple.values().size());
        assertEquals("42", tuple.values().getFirst().value()); assertEquals("long", tuple.values().getFirst().typeName());
    }

    private static void roundTrip(ElasticsearchCursorKeys.Family family, Object value) throws Exception {
        TypedCursorValue tuple = ElasticsearchCursorKeys.value(family, value);
        assertEquals(family.jdbcType(), tuple.jdbcType()); assertEquals(family.typeName(), tuple.typeName());
        assertEquals(value, ElasticsearchCursorKeys.decode(family, tuple));
    }

    private static ElasticsearchCursorKeys.Family family(String type) { return ElasticsearchCursorKeys.familyOf(field(type)); }
    private static ElasticsearchMapping.Field field(String type) {
        int jdbc = switch (type) {
            case "byte", "short", "integer" -> Types.INTEGER;
            case "long" -> Types.BIGINT;
            case "unsigned_long" -> Types.NUMERIC;
            case "boolean" -> Types.BOOLEAN;
            case "float", "half_float", "double" -> Types.DOUBLE;
            default -> Types.VARCHAR;
        };
        Class<?> java = switch (type) {
            case "byte", "short", "integer" -> Integer.class;
            case "long" -> Long.class;
            case "unsigned_long" -> BigInteger.class;
            case "boolean" -> Boolean.class;
            case "float", "half_float", "double" -> Double.class;
            default -> String.class;
        };
        return new ElasticsearchMapping.Field("f", type, jdbc, java);
    }
}
