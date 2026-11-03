package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class ClickhouseTextProjectionClickhouseTest {
    @org.junit.jupiter.api.BeforeAll
    static void loadFixtureDriver() throws Exception {
        // DriverManager may have been initialized with another test's isolated context loader.
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
    }

    @ParameterizedTest @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "String|'a'|12|12", "Nullable(String)|'a'|12|12", "LowCardinality(String)|'a'|12|12",
            "LowCardinality(Nullable(String))|'a'|12|12", "Nullable(String)|NULL|12|12",
            "FixedString(4)|'a'|0|0", "Enum8('a'=1,'b'=2)|'a'|0|0", "Int64|9|0|-5", "Int32|9|0|4",
            "Int16|9|0|5", "Nullable(Int64)|NULL|0|-5", "UInt64|9|0|0", "Decimal(20,0)|9|0|0",
            "Int128|9|0|0", "Float64|9|0|0"})
    void nativeTypesRemainVisibleThroughDerivedGrouping(String type, String value, int textType, int mixedType) throws Exception {
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE);
            var text = database.textKeyProjection("s", "id", 256);
            var mixed = database.textAndIntegerKeyComponentProjection(connection, "s", "id", 256);
            for (String source : List.of("SELECT CAST(" + value + " AS " + type + ") AS id",
                    "SELECT id FROM (SELECT CAST(" + value + " AS " + type + ") AS id) GROUP BY id")) {
                try (var rows = sql.executeQuery("SELECT " + text.valueSql() + "," + text.typeSql() + " FROM (" + source + ") s")) {
                    assertTrue(rows.next());
                    assertEquals(textType, text.jdbcType(rows.getMetaData(), 2), rows.getMetaData().getColumnTypeName(2));
                    assertEquals(mixedType, mixed.jdbcType(rows.getMetaData(), 2));
                    assertNull(rows.getObject(2)); // Native witness never transmits the original value.
                }
            }
            assertFalse(connection.isClosed());
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 256, 4096})
    void fullByteLimitIsNeverTruncatedAndInvalidUtf8IsNotDecoded(int bound) throws Exception {
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            var p = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE).textKeyProjection("s", "id", bound);
            for (String expression : List.of("repeat('x'," + bound + ")", "repeat('x'," + (bound + 1) + ")", "unhex('FF')")) {
                try (var rows = sql.executeQuery("SELECT " + p.valueSql() + "," + p.groupSql() + "," + p.typeSql()
                        + " FROM (SELECT " + expression + " AS id) s")) {
                    assertTrue(rows.next()); assertEquals(Types.VARCHAR, p.jdbcType(rows.getMetaData(), 3));
                    if (expression.equals("repeat('x'," + bound + ")")) {
                        assertEquals(bound, rows.getBytes(1).length); assertEquals(bound * 2, rows.getString(2).length());
                    } else { assertNull(rows.getBytes(1)); assertNull(rows.getString(2)); }
                }
            }
        }
    }

    @Test void equalityPreservesUnicodeSpacesQuotesBackslashAndCase() throws Exception {
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            var p = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE).textKeyProjection("s", "id", 256);
            var values = List.of("A", "a", "A ", "0009", "9", "中国😀", "e\u0301", "é", "a\\' OR 1=1 -- ", "", "a\0b");
            String source = String.join(" UNION ALL ", values.stream().map(value ->
                    "SELECT unhex('" + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)) + "') AS id").toList());
            for (String value : values) {
                try (var rows = sql.executeQuery("SELECT " + p.valueSql() + " FROM (" + source + ") s WHERE " + p.equalitySql(value))) {
                    assertTrue(rows.next()); assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), rows.getBytes(1)); assertFalse(rows.next());
                }
            }
            assertThrows(IllegalArgumentException.class, () -> p.equalitySql("x".repeat(257)));
            assertThrows(IllegalArgumentException.class, () -> p.equalitySql("\uD800"));
        }
    }
}
