package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
class OceanbaseKeyComponentProjectionOceanbaseTest {
    @Test void nativeSignedTypesAreProvedWithoutTransmittingTheTypeWitnessValue() throws Exception {
        Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName());
        String schema = "ob_keys_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), "root@test", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,si SMALLINT,ni INT,"
                        + "txt VARCHAR(512),memo TEXT,ch CHAR(12),deci DECIMAL(12,2),ui BIGINT UNSIGNED,"
                        + "tiny TINYINT,medium MEDIUMINT,bitval BIT(1),floatval DOUBLE,enumval ENUM('9','10'))");
                sql.execute("INSERT INTO " + schema + ".sample VALUES"
                        + "(-9223372036854775808,-32768,2147483647,'Ab 中国 ','text','fixed',1.25,"
                        + "18446744073709551615,9,9,b'1',1.25,'9')");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OCEANBASE);
                String[] columns = {"id", "si", "ni"};
                int[] types = {Types.BIGINT, Types.SMALLINT, Types.INTEGER};
                String[] values = {"-9223372036854775808", "-32768", "2147483647"};
                for (int i = 0; i < columns.length; i++) {
                    var projection = database.keyComponentProjection(connection, "s", columns[i], 256);
                    assertFalse(database.integerKeyProjection(connection, "s", columns[i]).requiresExplicitOuterLimit());
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.typeSql()
                            + " FROM " + schema + ".sample s")) {
                        assertTrue(rows.next());
                        assertEquals(values[i], rows.getString(1));
                        assertNull(rows.getObject(2));
                        assertEquals(types[i], projection.jdbcType(rows.getMetaData(), 2));
                    }
                }
                for (String column : new String[]{"txt", "memo", "ch", "deci", "ui", "tiny", "medium", "bitval", "floatval", "enumval"}) {
                    var projection = database.keyComponentProjection(connection, "s", column, 256);
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.typeSql()
                            + " FROM " + schema + ".sample s")) {
                        assertEquals(0, projection.jdbcType(rows.getMetaData(), 2), column);
                    }
                }
                var bounded = database.keyComponentProjection(connection, "s", "id", 3);
                try (var rows = sql.executeQuery("SELECT " + bounded.valueSql() + " FROM " + schema + ".sample s")) {
                    assertTrue(rows.next()); assertNull(rows.getString(1));
                }
                assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(connection, null, "id", 0));
                assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(connection, null, "id", 4097));
                assertThrows(UnsupportedOperationException.class, () -> database.textKeyProjection("s", "txt", 256));
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
}
