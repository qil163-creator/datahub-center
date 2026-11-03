package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.nio.charset.StandardCharsets;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class TidbTextKeyProjectionTidbTest {
    @Test
    void derivedGroupingAndEqualityKeepExactUnicodeBytesUnderCollation() throws Exception {
        String schema = "tidb_text_exact_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci)");
                var values = java.util.List.of("A", "a", "A ", "0009", "9", "中国😀", "é", "e\u0301", "", "a\\' OR 1=1 -- ");
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".sample VALUES(?)")) {
                    insert.setQueryTimeout(15);
                    for (String value : values) { insert.setString(1, value); insert.executeUpdate(); }
                }
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB);
                var source = database.textKeyProjection(null, "id", 256);
                var outer = database.textKeyProjection("k", "id", 256);
                for (String mode : java.util.List.of("ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_BACKSLASH_ESCAPES")) {
                    sql.execute("SET SESSION sql_mode='" + mode + "'");
                    sql.execute("SET SESSION max_sort_length=4");
                    String query = "SELECT " + outer.valueSql() + "," + outer.typeSql() + ",k.n FROM (SELECT id,"
                            + source.groupSql() + " AS identity_key,COUNT(*) AS n FROM " + schema
                            + ".sample GROUP BY id," + source.groupSql() + ") k ORDER BY k.identity_key";
                    var actual = new java.util.ArrayList<String>();
                    try (var rows = sql.executeQuery(query)) {
                        assertEquals(Types.LONGVARCHAR, outer.jdbcType(rows.getMetaData(), 2));
                        while (rows.next()) {
                            actual.add(new String(rows.getBytes(1), StandardCharsets.UTF_8));
                            assertNull(rows.getObject(2)); assertEquals(1, rows.getLong(3));
                        }
                    }
                    var expected = values.stream().sorted((a, b) -> java.util.Arrays.compareUnsigned(
                            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))).toList();
                    assertEquals(expected, actual);
                    for (String value : values) {
                        try (var rows = sql.executeQuery("SELECT id FROM " + schema + ".sample WHERE " + source.equalitySql(value))) {
                            assertTrue(rows.next()); assertEquals(value, rows.getString(1)); assertFalse(rows.next());
                        }
                    }
                }
                sql.execute("DELETE FROM " + schema + ".sample");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(CONCAT(REPEAT('中',85),'x')),(CONCAT(REPEAT('中',85),'xx'))");
                try (var rows = sql.executeQuery("SELECT " + source.valueSql() + " FROM " + schema + ".sample ORDER BY OCTET_LENGTH(id)")) {
                    assertTrue(rows.next()); assertEquals(256, rows.getBytes(1).length);
                    assertTrue(rows.next()); assertNull(rows.getBytes(1)); assertFalse(rows.next());
                }
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 256, 4096})
    void groupProjectionIsNotALobAndOverlongValuesAreNullNotTruncated(int limit) throws Exception {
        String schema = "tidb_text_projection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), ""); var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id LONGTEXT CHARACTER SET utf8mb4) ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(REPEAT('x'," + limit + ")),(REPEAT('x'," + (limit + 1) + "))");
                var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB).textKeyProjection("k", "id", limit);
                sql.execute("SET SESSION max_sort_length=4");
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.groupSql()
                        + " FROM " + schema + ".sample k ORDER BY OCTET_LENGTH(k.id)")) {
                    assertEquals(Types.VARCHAR, rows.getMetaData().getColumnType(2));
                    assertTrue(rows.next()); assertEquals(limit, rows.getBytes(1).length);
                    assertEquals(limit * 2, rows.getString(2).length());
                    assertTrue(rows.next()); assertNull(rows.getBytes(1)); assertNull(rows.getString(2));
                    assertFalse(rows.next());
                }
                String value = "x".repeat(limit);
                try (var rows = sql.executeQuery("SELECT id FROM " + schema + ".sample k WHERE " + projection.equalitySql(value))) {
                    assertTrue(rows.next()); assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), rows.getBytes(1));
                    assertFalse(rows.next());
                }
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "VARCHAR(100)|'a'|12", "TEXT|'a'|-1", "TINYTEXT|'a'|-1", "MEDIUMTEXT|'a'|-1", "LONGTEXT|'a'|-1",
            "CHAR(64)|'a'|0", "BIGINT|9|0", "BIGINT UNSIGNED|18446744073709551615|0",
            "DECIMAL(20,0)|9|0", "VARBINARY(64)|'a'|0", "BLOB|'a'|0", "ENUM('a','b')|'a'|0", "SET('a','b')|'a'|0"
    })
    void nativeTypeWitnessDoesNotTransferOriginalPayload(String definition, String value, int type) throws Exception {
        String schema = "tidb_text_projection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), ""); var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id " + definition + ") ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(" + value + "),(NULL)");
                var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB).textKeyProjection("k", "id", 256);
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql()
                        + " FROM " + schema + ".sample k ORDER BY k.id")) {
                    var metadata = rows.getMetaData();
                    assertEquals(type, projection.jdbcType(metadata, 2), metadata.getColumnTypeName(2));
                    assertTrue(rows.next()); assertNull(rows.getBytes(1)); assertNull(rows.getObject(2));
                    assertTrue(rows.next()); assertTrue(rows.getBytes(1).length <= 256); assertNull(rows.getObject(2));
                    assertFalse(rows.next());
                }
                // The same native witness must survive aggregation, not only a direct table SELECT.
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql()
                        + " FROM (SELECT id FROM " + schema + ".sample GROUP BY id) k ORDER BY k.id")) {
                    assertEquals(type, projection.jdbcType(rows.getMetaData(), 2), rows.getMetaData().getColumnTypeName(2));
                    while (rows.next()) assertNull(rows.getObject(2));
                }
                assertFalse(connection.isClosed()); assertTrue(connection.getAutoCommit());
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
}
