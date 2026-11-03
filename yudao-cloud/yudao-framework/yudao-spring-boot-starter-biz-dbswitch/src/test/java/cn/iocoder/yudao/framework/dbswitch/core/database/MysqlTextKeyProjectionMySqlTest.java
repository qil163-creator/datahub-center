package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.nio.charset.StandardCharsets;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class MysqlTextKeyProjectionMySqlTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 256, 4096})
    void groupProjectionIsNotALobAndOverlongValuesAreNullNotTruncated(int limit) throws Exception {
        String schema = "text_projection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), ""); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id LONGTEXT CHARACTER SET utf8mb4) ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(REPEAT('x'," + limit + ")),(REPEAT('x'," + (limit + 1) + "))");
                var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MYSQL).textKeyProjection("k", "id", limit);
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
        String schema = "text_projection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), ""); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id " + definition + ") ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(" + value + "),(NULL)");
                var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MYSQL).textKeyProjection("k", "id", 256);
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql()
                        + " FROM " + schema + ".sample k ORDER BY k.id")) {
                    var metadata = rows.getMetaData();
                    assertEquals(type, projection.jdbcType(metadata, 2), metadata.getColumnTypeName(2));
                    assertTrue(rows.next()); assertNull(rows.getBytes(1)); assertNull(rows.getObject(2));
                    assertTrue(rows.next()); assertTrue(rows.getBytes(1).length <= 256); assertNull(rows.getObject(2));
                    assertFalse(rows.next());
                }
                assertFalse(connection.isClosed()); assertTrue(connection.getAutoCommit());
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
}
