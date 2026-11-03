package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/")
class MariaDbKeyProjectionMariaDbTest {
    @Test void nativeConnectorProvesIntegersButRejectsAmbiguousTextAndEnums() throws Exception {
        String schema = "maria_keys_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT, si SMALLINT, ni INT, txt VARCHAR(512), memo TEXT, ch CHAR(12), "
                        + "deci DECIMAL(20,0), uns BIGINT UNSIGNED, en ENUM('a','b'), bin VARBINARY(50)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(-9223372036854775808,-32768,2147483647,'Ab 中国 ','text','fixed',9,18446744073709551615,'a','9')");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB);
                String[] columns = {"id", "si", "ni", "txt", "memo", "ch", "deci", "uns", "en", "bin"};
                int[] types = {Types.BIGINT, Types.SMALLINT, Types.INTEGER, 0, 0, 0, 0, 0, 0, 0};
                String[] values = {"-9223372036854775808", "-32768", "2147483647"};
                var mismatches = new java.util.ArrayList<String>();
                for (int i = 0; i < columns.length; i++) {
                    var projection = database.keyComponentProjection("s", columns[i], 256);
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.typeSql() + " FROM " + schema + ".sample s")) {
                        assertTrue(rows.next());
                        if (types[i] != projection.jdbcType(rows.getMetaData(), 2)) mismatches.add(columns[i]
                                + ":jdbc=" + rows.getMetaData().getColumnType(2) + ",native=" + rows.getMetaData().getColumnTypeName(2)
                                + ",accepted=" + projection.jdbcType(rows.getMetaData(), 2));
                        if (i < values.length) assertEquals(values[i], rows.getString(1));
                    }
                }
                assertEquals(java.util.List.of(), mismatches);
                assertThrows(UnsupportedOperationException.class, () -> database.textKeyProjection("s", "txt", 256));
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
}
