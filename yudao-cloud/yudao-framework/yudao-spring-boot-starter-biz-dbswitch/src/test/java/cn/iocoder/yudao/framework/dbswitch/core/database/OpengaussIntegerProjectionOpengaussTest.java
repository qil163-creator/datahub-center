package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.opengauss.url", matches = "jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/(?:postgres|dbswitch_test)")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OPENGAUSS_TEST_PASSWORD", matches = ".+")
class OpengaussIntegerProjectionOpengaussTest {
    @Test void nativeTypesNotCastResultProveIntegerEligibility() throws Exception {
        String schema = "og_projection_" + UUID.randomUUID().toString().replace("-", "");
        Class.forName("org.opengauss.Driver");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.opengauss.url"),
                System.getProperty("datahub.test.opengauss.user", "codex_test"), System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD")); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE SCHEMA " + schema);
            try {
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OPENGAUSS);
                String[] types = {"SMALLINT", "INTEGER", "BIGINT", "NUMERIC(19,0)", "VARCHAR(100)", "CHAR(5)", "TEXT"};
                int[] expected = {Types.SMALLINT, Types.INTEGER, Types.BIGINT, 0, 0, 0, 0};
                for (int i = 0; i < types.length; i++) {
                    String table = schema + ".sample_" + i;
                    sql.execute("CREATE TABLE " + table + "(id " + types[i] + ")");
                    sql.execute("INSERT INTO " + table + " VALUES('123')");
                    var projection = database.keyComponentProjection("k", "id", 256);
                    try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + ", " + projection.typeSql() + " FROM " + table + " k")) {
                        assertEquals(expected[i], projection.jdbcType(rows.getMetaData(), 2), types[i]);
                        assertTrue(rows.next());
                        if (expected[i] != 0) assertEquals("123", rows.getString(1));
                        assertNull(rows.getObject(2));
                    }
                }
                var tiny = database.keyComponentProjection("k", "id", 1);
                try (var rows = sql.executeQuery("SELECT " + tiny.valueSql() + " FROM " + schema + ".sample_0 k")) {
                    assertTrue(rows.next()); assertNull(rows.getString(1)); // Never use a truncated identity.
                }
                assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 0));
                assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 4097));
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
