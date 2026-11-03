package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres\\?sslmode=disable")
@EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
class GreenplumTextProjectionGreenplumTest {
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.greenplum.url"), "gpadmin",
                System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"));
    }

    @ParameterizedTest @CsvSource(delimiter = '|', quoteCharacter = '~', value = {"VARCHAR(500)|'a'|12|12", "TEXT|'a'|-1|-1", "TEXT|NULL|-1|-1",
            "CHAR(5)|'a'|0|0", "SMALLINT|9|0|5", "INTEGER|9|0|4", "BIGINT|9|0|-5", "NUMERIC(20,0)|9|0|0",
            "FLOAT8|9|0|0", "DATE|'2026-09-30'|0|0", "BOOLEAN|true|0|0"})
    void nativeTypesSurviveDirectAndGroupedDerivedReads(String type, String value, int textType, int mixedType) throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.GREENPLUM);
            var text = database.textKeyProjection("s", "id", 256);
            var mixed = database.textAndIntegerKeyComponentProjection(connection, "s", "id", 256);
            for (String source : List.of("SELECT CAST(" + value + " AS " + type + ") AS id",
                    "SELECT id FROM (SELECT CAST(" + value + " AS " + type + ") AS id) r GROUP BY id")) {
                try (var rows = sql.executeQuery("SELECT " + text.valueSql() + "," + text.typeSql() + " FROM (" + source + ") s")) {
                    assertTrue(rows.next());
                    assertEquals(textType, text.jdbcType(rows.getMetaData(), 2), rows.getMetaData().getColumnTypeName(2));
                    assertEquals(mixedType, mixed.jdbcType(rows.getMetaData(), 2));
                    assertNull(rows.getObject(2));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 256, 4096})
    void byteBudgetNeverTruncatesIdentity(int bound) throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.GREENPLUM).textKeyProjection("s", "id", bound);
            for (int length : new int[]{bound, bound + 1}) {
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.groupSql()
                        + " FROM (SELECT pg_catalog.repeat('x'," + length + ") AS id) s")) {
                    assertTrue(rows.next());
                    if (length == bound) {
                        assertEquals(bound, rows.getBytes(1).length); assertArrayEquals(rows.getBytes(1), rows.getBytes(2));
                    } else { assertNull(rows.getBytes(1)); assertNull(rows.getBytes(2)); }
                }
            }
        }
    }

    @Test void filteringKeepsCaseSpacesUnicodeAndEscapeModesExact() throws Exception {
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.GREENPLUM).textKeyProjection("s", "id", 256);
            var values = List.of("A", "a", "A ", "0009", "9", "中国😀", "e\u0301", "é", "a\\' OR 1=1 -- ");
            String source = String.join(" UNION ALL ", values.stream().map(value -> "SELECT pg_catalog.convert_from(pg_catalog.decode('"
                    + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)) + "','hex'),'UTF8') AS id").toList());
            for (String mode : List.of("on", "off")) {
                sql.execute("SET standard_conforming_strings=" + mode);
                for (String value : values) {
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + " FROM (" + source + ") s WHERE " + projection.equalitySql(value))) {
                        assertTrue(rows.next()); assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), rows.getBytes(1)); assertFalse(rows.next());
                    }
                }
            }
        }
    }
}
