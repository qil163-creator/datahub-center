package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class MysqlIntegerKeyProjectionMySqlTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SMALLINT|32767|5", "INT|2147483647|4", "BIGINT|-9223372036854775808|-5",
            "BIGINT|9223372036854775807|-5", "BIGINT UNSIGNED|18446744073709551615|0",
            "INT UNSIGNED|4294967295|0", "SMALLINT UNSIGNED|65535|0",
            "DECIMAL(20,0)|9223372036854775808|0", "DOUBLE|1.5|0", "TINYINT(1)|1|0",
            "MEDIUMINT|123|0", "VARCHAR(5000)|REPEAT('9',4000)|0", "LONGBLOB|REPEAT('1',4000)|0"
    })
    void verifiesOriginalTypeInTheSameResultWithoutTransferringUnboundedValues(
            String definition, String value, int expectedType) throws Exception {
        String schema = "key_projection_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id " + definition + ") ENGINE=InnoDB");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(" + value + "),(NULL)");
                var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MYSQL).integerKeyProjection("k", "id");
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql()
                        + " FROM " + schema + ".sample k ORDER BY k.id")) {
                    assertEquals(expectedType, projection.jdbcType(rows.getMetaData(), 2));
                    assertTrue(rows.next()); assertNull(rows.getString(1)); assertNull(rows.getObject(2));
                    assertTrue(rows.next());
                    assertTrue(rows.getString(1).length() <= 21);
                    assertNull(rows.getObject(2));
                    if (expectedType != 0) assertEquals(value, rows.getString(1));
                    assertFalse(rows.next());
                }
                assertFalse(connection.isClosed()); assertTrue(connection.getAutoCommit());
            } finally {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }
}
