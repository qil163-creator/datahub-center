package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DorisKeyComponentProjectionDorisTest {
    @Test void componentProvesNativeIntegersAndRejectsAmbiguousTextAndDecimals() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String schema = "doris_keys_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT, si SMALLINT, ni INT, txt VARCHAR(512), memo STRING, ch CHAR(12), deci DECIMAL(12,2)) "
                        + "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(-9223372036854775808,-32768,2147483647,'Ab 中国 ','text','fixed',1.25)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DORIS);
                String[] columns = {"id", "si", "ni"};
                int[] types = {Types.BIGINT, Types.SMALLINT, Types.INTEGER};
                String[] values = {"-9223372036854775808", "-32768", "2147483647"};
                for (int i = 0; i < columns.length; i++) {
                    var projection = database.keyComponentProjection("s", columns[i], 256);
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.typeSql()
                            + " FROM " + schema + ".sample s")) {
                        assertTrue(rows.next());
                        assertEquals(values[i], rows.getString(1));
                        assertEquals(types[i], projection.jdbcType(rows.getMetaData(), 2));
                    }
                }
                for (String column : new String[]{"txt", "memo", "ch", "deci"}) {
                    var projection = database.keyComponentProjection("s", column, 256);
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.typeSql()
                            + " FROM " + schema + ".sample s")) {
                        assertEquals(0, projection.jdbcType(rows.getMetaData(), 2), column);
                    }
                }
                var bounded = database.keyComponentProjection("s", "id", 3);
                try (var rows = sql.executeQuery("SELECT " + bounded.valueSql() + " FROM " + schema + ".sample s")) {
                    assertTrue(rows.next()); assertNull(rows.getString(1));
                }
                assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 0));
                assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 257));
            } finally { sql.execute("DROP DATABASE " + schema + " FORCE"); }
        }
    }
}
