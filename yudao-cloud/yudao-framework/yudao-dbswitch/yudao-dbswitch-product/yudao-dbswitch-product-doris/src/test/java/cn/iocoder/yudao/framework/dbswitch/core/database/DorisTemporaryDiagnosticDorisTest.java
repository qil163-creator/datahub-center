package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Doris 的诊断只创建随机独立库及会话临时对象，最终强制清理随机库。 */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DorisTemporaryDiagnosticDorisTest {
    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.doris.url"),
                System.getenv().getOrDefault("DORIS_DIAGNOSTIC_USER", "root"),
                System.getenv().getOrDefault("DORIS_DIAGNOSTIC_PASSWORD", ""));
    }

    @Test void nativeTemporaryWritesDdlIsolationAndExplicitCleanup() throws Exception {
        var product = new DatabaseDorisImpl();
        String schema = "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", "");
        var plan = product.temporaryTableDiagnosticPlan("dbswitch_diag_" + UUID.randomUUID().toString().replace("-", ""));
        try (var control = open(); var statement = control.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute("CREATE DATABASE `" + schema + "`");
            try {
                try (var session = open(); var writer = session.createStatement()) {
                    writer.setQueryTimeout(10);
                    product.validateTemporaryTableDiagnosticConnection(session, 10);
                    product.probeNamespace(session, schema, 10);
                    writer.execute(plan.createSql());
                    String table = plan.tableReference();
                    writer.executeUpdate("INSERT INTO " + table + " VALUES(1,'diagnostic')");
                    try (var batch = session.prepareStatement("INSERT INTO " + table + " VALUES (?,?)")) {
                        batch.setQueryTimeout(10);
                        for (int id = 2; id <= 3; id++) {
                            batch.setLong(1, id); batch.setString(2, "batch"); batch.addBatch();
                        }
                        assertEquals(2, batch.executeBatch().length);
                    }
                    assertEquals(3, count(session, table, "1=1"));
                    assertEquals(1, writer.executeUpdate("UPDATE " + table + " SET probe_value='updated' WHERE probe_id=1"));
                    assertEquals(1, count(session, table, "probe_id=1 AND probe_value='updated'"));
                    assertEquals(1, writer.executeUpdate("DELETE FROM " + table + " WHERE probe_id=1"));
                    assertEquals(0, count(session, table, "probe_id=1"));
                    writer.executeUpdate("INSERT INTO " + table + " VALUES(4,'initial'),(5,'initial')");
                    writer.executeUpdate(plan.upsertSql());
                    assertEquals(1, count(session, table, "probe_id=4 AND probe_value='upserted'"));
                    writer.executeUpdate(plan.mergeSql());
                    assertEquals(2, count(session, table,
                            "(probe_id=5 AND probe_value='merged') OR (probe_id=6 AND probe_value='inserted')"));
                    assertThrows(SQLException.class, () -> statement.executeQuery(
                            "SELECT * FROM `" + schema + "`." + table));
                    var alter = assertThrows(SQLException.class, () -> writer.execute(
                            "ALTER TABLE " + table + " ADD COLUMN probe_extra INT NULL"));
                    assertTrue(alter.getMessage().contains("Do not support alter temporary table"));
                    var rename = assertThrows(SQLException.class, () -> writer.execute(
                            "ALTER TABLE " + table + " RENAME renamed_probe"));
                    assertTrue(rename.getMessage().contains("Do not support alter temporary table"));
                    writer.execute(plan.truncateSql());
                    assertEquals(0, count(session, table, "1=1"));
                    session.setAutoCommit(false);
                    try {
                        writer.executeUpdate("INSERT INTO " + table + " VALUES(9,'rollback')");
                        session.rollback();
                        // Connector/J 的 autocommit 开关并未开启 Doris 原生事务，不能据此报告回滚成功。
                        assertEquals(1, count(session, table, "probe_id=9"));
                    } finally {
                        session.rollback();
                        session.setAutoCommit(true);
                    }
                    writer.executeUpdate("DELETE FROM " + table + " WHERE probe_id=9");
                    writer.execute("BEGIN");
                    try {
                        writer.executeUpdate("INSERT INTO " + table + " VALUES(9,'rollback')");
                    } finally {
                        writer.execute("ROLLBACK");
                    }
                    assertEquals(0, count(session, table, "probe_id=9"));
                    writer.execute(plan.dropSql());
                    assertEquals(0, physicalTables(control, schema));
                    writer.execute(plan.createSql());
                    assertEquals(1, physicalTables(control, schema));
                }
                // 不依赖自动清理的文档声明：从独立连接观察物理目录，证明关闭会话后对象消失。
                assertEquals(0, physicalTables(control, schema));
                var cdc = product.probeCdcReadiness(control, 10);
                assertTrue(statement.execute("ADMIN SHOW FRONTEND CONFIG LIKE 'experimental_enable_feature_binlog'"));
                try (var rows = statement.getResultSet()) {
                    assertTrue(rows.next());
                    assertEquals(Boolean.parseBoolean(rows.getString("Value")), cdc.logConfigurationReady());
                }
            } finally {
                statement.execute("DROP DATABASE `" + schema + "` FORCE");
            }
            try (var rows = statement.executeQuery("SHOW DATABASES LIKE '" + schema + "'")) {
                assertFalse(rows.next());
            }
        }
    }

    private int count(Connection c, String table, String condition) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(10);
            try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + condition)) {
                assertTrue(r.next()); return r.getInt(1);
            }
        }
    }

    private int physicalTables(Connection c, String schema) throws SQLException {
        try (var s = c.prepareStatement("SELECT COUNT(TABLE_ID) FROM internal.information_schema.metadata_name_ids "
                + "WHERE DATABASE_NAME=?")) {
            s.setQueryTimeout(10); s.setString(1, schema);
            try (var r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
        }
    }
}
