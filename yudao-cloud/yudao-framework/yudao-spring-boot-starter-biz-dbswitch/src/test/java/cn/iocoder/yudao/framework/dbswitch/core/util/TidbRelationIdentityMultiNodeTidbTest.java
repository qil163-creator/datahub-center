package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Two SQL servers in ONE disposable TiDB/PD/TiKV cluster; not application-node/HA acceptance. */
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.tidb.second-url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class TidbRelationIdentityMultiNodeTidbTest {
    @Test void twoNativeSqlNodesAgreeBeforeAndAfterDataAndGenerationChanges() throws Exception {
        String first = System.getProperty("datahub.test.tidb.url");
        String second = System.getProperty("datahub.test.tidb.second-url");
        assertNotEquals(first, second, "Two different loopback SQL endpoints are required");
        String schema = "tidb_multinode_" + UUID.randomUUID().toString().replace("-", "");
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB);
        try (var a = DriverManager.getConnection(first, "root", "");
             var b = DriverManager.getConnection(second, "root", "");
             var sql = a.createStatement()) {
            sql.setQueryTimeout(15);
            assertNotEquals(nodeHostname(a), nodeHostname(b), "Endpoints must not forward to the same SQL server");
            sql.execute("CREATE DATABASE " + schema);
            try {
                a.setCatalog(schema); b.setCatalog(schema);
                try (var setup = a.createStatement()) {
                    setup.setQueryTimeout(15);
                    setup.execute("CREATE TABLE sample(id BIGINT PRIMARY KEY,val VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci)");
                    setup.execute("INSERT INTO sample VALUES(1,'A'),(2,'a'),(3,'A ')");
                }
                String before = database.queryRelationFingerprint(a, "sample");
                assertEquals(before, database.queryRelationFingerprint(b, "sample"));
                assertEquals(List.of("A", "A ", "a"), values(a)); assertEquals(values(a), values(b));
                try (var write = b.createStatement()) {
                    write.setQueryTimeout(15); write.execute("UPDATE sample SET val='中国😀' WHERE id=1");
                    assertEquals(before, database.queryRelationFingerprint(a, "sample"));
                    assertEquals(before, database.queryRelationFingerprint(b, "sample"));
                    assertEquals(values(a), values(b));
                    write.execute("TRUNCATE TABLE sample");
                }
                String after = database.queryRelationFingerprint(a, "sample");
                assertNotEquals(before, after); assertEquals(after, database.queryRelationFingerprint(b, "sample"));
                assertTrue(values(a).isEmpty()); assertTrue(values(b).isEmpty());
                assertTrue(a.getAutoCommit()); assertTrue(b.getAutoCommit());
            } finally {
                a.setCatalog("information_schema"); b.setCatalog("information_schema");
                try (var cleanup = a.createStatement()) {
                    cleanup.setQueryTimeout(15); cleanup.execute("DROP DATABASE " + schema);
                }
            }
        }
    }

    private static String nodeHostname(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(15);
            try (var rows = sql.executeQuery("SELECT @@hostname")) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static List<String> values(Connection connection) throws Exception {
        var projection = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB).textKeyProjection("k", "val", 256);
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.setMaxRows(10);
            try (var rows = sql.executeQuery("SELECT " + projection.valueSql() + "," + projection.typeSql()
                    + " FROM sample k ORDER BY " + projection.groupSql())) {
                assertEquals(java.sql.Types.VARCHAR, projection.jdbcType(rows.getMetaData(), 2));
                List<String> values = new ArrayList<>();
                while (rows.next()) { values.add(new String(rows.getBytes(1), java.nio.charset.StandardCharsets.UTF_8)); assertNull(rows.getObject(2)); }
                return values;
            }
        }
    }
}
