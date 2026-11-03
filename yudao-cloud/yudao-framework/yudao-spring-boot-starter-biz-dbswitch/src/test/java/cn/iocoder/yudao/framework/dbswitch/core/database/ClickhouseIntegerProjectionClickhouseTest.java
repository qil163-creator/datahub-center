package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class ClickhouseIntegerProjectionClickhouseTest {
    @org.junit.jupiter.api.BeforeAll
    static void loadFixtureDriver() throws Exception {
        // DriverManager may have been initialized with another test's isolated context loader.
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
    }

    @Test void nativeIntegerWitnessCannotBeReplacedByUnsignedDecimalOrText() throws Exception {
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE);
            String[] types = {"Int16", "Int32", "Int64", "Nullable(Int64)", "UInt64", "Decimal(20,0)", "String", "Int128"};
            String[] values = {"-32768", "-2147483648", "-9223372036854775808", "9223372036854775807", "18446744073709551615", "9", "9", "9"};
            int[] jdbc = {Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.BIGINT, 0, 0, 0, 0};
            for (int i = 0; i < types.length; i++) {
                var projection = database.keyComponentProjection("s", "id", 256);
                try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql()
                        + " FROM (SELECT CAST('" + values[i] + "' AS " + types[i] + ") AS id) s")) {
                    assertTrue(rows.next());
                    assertEquals(jdbc[i], projection.jdbcType(rows.getMetaData(), 2), rows.getMetaData().getColumnTypeName(2));
                    if (jdbc[i] != 0) assertEquals(values[i], rows.getString(1));
                }
            }
        }
    }
}
