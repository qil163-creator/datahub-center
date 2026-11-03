package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class MysqlKeyComponentProjectionMySqlTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "SMALLINT|-32768|5", "INT|-2147483648|4", "BIGINT|-9223372036854775808|-5",
            "VARCHAR(500)|'中国😀'|12", "TEXT|'a '|-1", "TINYTEXT|'a'|-1", "MEDIUMTEXT|'a'|-1", "LONGTEXT|'a'|-1",
            "CHAR(64)|'a'|0", "BIGINT UNSIGNED|18446744073709551615|0", "DECIMAL(20,0)|9|0",
            "VARBINARY(64)|'a'|0", "BLOB|'a'|0", "ENUM('a','b')|'a'|0", "SET('a','b')|'a'|0"
    })
    void originalTypesAreProvenFromDerivedTableAndNeverFromConvertedBytes(String definition, String value, int type) throws Exception {
        String schema = "component_projection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), ""); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id " + definition + ") ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(" + value + "),(NULL)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MYSQL);
                var source = database.keyComponentProjection(null, "id", 256);
                var projection = database.keyComponentProjection("k", "id", 256);
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql()
                        + " FROM (SELECT id," + source.groupSql() + " AS exactKey FROM " + schema
                        + ".sample GROUP BY id,exactKey) k ORDER BY k.exactKey")) {
                    assertEquals(type, projection.jdbcType(rows.getMetaData(), 2), rows.getMetaData().getColumnTypeName(2));
                    assertTrue(rows.next()); assertNull(rows.getBytes(1)); assertNull(rows.getObject(2));
                    assertTrue(rows.next()); assertNull(rows.getObject(2));
                    if (type != 0) {
                        String expected = value.startsWith("'") ? value.substring(1, value.length() - 1) : value;
                        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), rows.getBytes(1));
                    }
                    assertFalse(rows.next());
                }
            } finally { sql.execute("DROP DATABASE " + schema); }
            assertFalse(connection.isClosed()); assertTrue(connection.getAutoCommit());
        }
    }
}
