package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** DM8 0x7000d at CASE_SENSITIVE=1. Password comes only from the environment. */
@EnabledIfSystemProperty(named = "datahub.test.dm.url", matches = "jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_DM_TEST_PASSWORD", matches = ".+")
class DmRelationIdentityDmTest {
    @Test void ordinaryRowTableChangesOnTruncateAndRecreationButNotOnWrites() throws Exception {
        String schema = "q11d" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = open(); var sql = statement(connection)) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                String table = schema + ".sample";
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DM);
                String before = database.queryRelationFingerprint(connection, table);
                assertTrue(before.matches("[a-f0-9]{64}"));
                sql.execute("ALTER SESSION SET CURRENT_SCHEMA=" + schema);
                try { assertEquals(before, database.queryRelationFingerprint(connection, "sample")); }
                finally { sql.execute("ALTER SESSION SET CURRENT_SCHEMA=SYSDBA"); }
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("UPDATE " + table + " SET amount=2");
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("TRUNCATE TABLE " + table);
                String truncated = database.queryRelationFingerprint(connection, table);
                assertNotEquals(before, truncated);
                sql.execute("DROP TABLE " + table);
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                assertNotEquals(truncated, database.queryRelationFingerprint(connection, table));
                for (String ddl : new String[]{
                        "CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + table,
                        "CREATE GLOBAL TEMPORARY TABLE " + schema + ".g_sample(id BIGINT) ON COMMIT PRESERVE ROWS",
                        "CREATE HUGE TABLE " + schema + ".h_sample(id INT)",
                        "CREATE TABLE " + schema + ".p_sample(id INT) PARTITION BY RANGE(id) "
                                + "(PARTITION p1 VALUES LESS THAN (100), PARTITION p2 VALUES LESS THAN (MAXVALUE))",
                        "CREATE TABLE " + schema + ".h_part(id INT) PARTITION BY HASH(id) PARTITIONS 2",
                        "CREATE TABLE " + schema + ".l_part(id INT) PARTITION BY LIST(id) "
                                + "(PARTITION p1 VALUES (1), PARTITION p2 VALUES (2))",
                        "CREATE MATERIALIZED VIEW " + schema + ".mv_sample AS SELECT id FROM " + table,
                        "CREATE TABLE " + schema + ".\"KeepCase\"(id INT)"}) {
                    sql.execute(ddl);
                }
                for (String name : new String[]{"sample_view", "g_sample", "h_sample", "p_sample", "p_sample_p1",
                        "h_part", "l_part", "mv_sample", "KeepCase"}) {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(connection, schema + "." + name), name);
                }
                connection.setAutoCommit(false);
                try {
                    assertNotNull(database.queryRelationFingerprint(connection, table));
                    assertFalse(connection.getAutoCommit());
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test void tableSelectDoesNotExposeCatalogUntilSelectAnyTable() throws Exception {
        String schema = "q11d" + UUID.randomUUID().toString().replace("-", "");
        String role = "Q11R" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String rolePassword = "Q11" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "z9";
        try (var connection = open(); var sql = statement(connection)) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DM);
                String owner = database.queryRelationFingerprint(connection, schema + ".sample");
                sql.execute("CREATE USER " + role + " IDENTIFIED BY \"" + rolePassword + "\"");
                try {
                    sql.execute("GRANT SELECT ON " + schema + ".sample TO " + role);
                    try (var reader = open(role, rolePassword)) {
                        var denied = assertThrows(SQLException.class, () -> database.queryRelationFingerprint(
                                reader, schema + ".sample"));
                        assertEquals("28000", denied.getSQLState());
                        assertEquals(-5504, denied.getErrorCode());
                    }
                    sql.execute("GRANT SELECT ANY TABLE TO " + role);
                    try (var reader = open(role, rolePassword)) {
                        String seen = database.queryRelationFingerprint(reader, schema + ".sample");
                        assertTrue(seen.matches("[a-f0-9]{64}"));
                        assertNotEquals(owner, seen);
                        assertEquals(seen, database.queryRelationFingerprint(reader, schema + ".sample"));
                    }
                    sql.execute("REVOKE SELECT ANY TABLE FROM " + role);
                    try (var reader = open(role, rolePassword)) {
                        var denied = assertThrows(SQLException.class, () -> database.queryRelationFingerprint(
                                reader, schema + ".sample"));
                        assertEquals("28000", denied.getSQLState());
                        assertEquals(-5504, denied.getErrorCode());
                    }
                } finally {
                    sql.execute("DROP USER " + role + " CASCADE");
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static Connection open() throws Exception { return open("SYSDBA", System.getenv("DATAHUB_DM_TEST_PASSWORD")); }

    private static Connection open(String user, String password) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        return DriverManager.getConnection(System.getProperty("datahub.test.dm.url"), user, password);
    }

    private static Statement statement(Connection connection) throws SQLException {
        var statement = connection.createStatement();
        statement.setQueryTimeout(30);
        return statement;
    }
}
