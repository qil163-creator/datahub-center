package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:[0-9]+/test")
@EnabledIfSystemProperty(named = "datahub.test.kingbase.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
class KingbaseRelationIdentityKingbaseTest {
    @Test void nativeHeapSurvivesWritesButNotTruncateRecreationOrOtherForms() throws Exception {
        String schema = "q11k_" + UUID.randomUUID().toString().replace("-", "");
        String server = schema + "_srv";
        try (var session = open("system", password())) {
            var sql = session.statement();
            sql.execute("CREATE SCHEMA " + schema);
            try {
                String table = schema + ".sample";
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.KINGBASE);
                String before = database.queryRelationFingerprint(session.connection(), table);
                assertTrue(before.matches("[a-f0-9]{64}"));
                assertEquals(before, database.queryRelationFingerprint(session.connection(),
                        schema.toUpperCase(Locale.ROOT) + ".SAMPLE"));
                sql.execute("CREATE FUNCTION " + schema + ".version() RETURNS TEXT LANGUAGE SQL AS 'SELECT ''shadow''::text'");
                sql.execute("SET search_path=" + schema + ", pg_catalog");
                try { assertEquals(before, database.queryRelationFingerprint(session.connection(), table)); }
                finally { sql.execute("RESET search_path"); }
                assertEquals(before, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("UPDATE " + table + " SET amount=1 WHERE id=1");
                assertEquals(before, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("TRUNCATE " + table);
                String truncated = database.queryRelationFingerprint(session.connection(), table);
                assertNotEquals(before, truncated);
                sql.execute("DROP TABLE " + table);
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                assertNotEquals(truncated, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("CREATE TABLE " + schema + ".\"Q11Quoted\"(id BIGINT)");
                String quoted = database.queryRelationFingerprint(session.connection(), schema + ".q11quoted");
                assertTrue(quoted.matches("[a-f0-9]{64}"));
                assertEquals(quoted, database.queryRelationFingerprint(session.connection(), schema + ".Q11QUOTED"));
                for (String ddl : new String[]{
                        "CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + table,
                        "CREATE MATERIALIZED VIEW " + schema + ".mat_view AS SELECT 1 AS id",
                        "CREATE UNLOGGED TABLE " + schema + ".unlogged_table(id BIGINT)",
                        "CREATE TABLE " + schema + ".parent(id BIGINT) PARTITION BY RANGE (id)",
                        "CREATE TABLE " + schema + ".parent_a PARTITION OF " + schema + ".parent FOR VALUES FROM (0) TO (50)",
                        "CREATE TABLE " + schema + ".parent_b PARTITION OF " + schema + ".parent FOR VALUES FROM (50) TO (100)",
                        "CREATE TABLE " + schema + ".inherit_parent(id BIGINT)",
                        "CREATE TABLE " + schema + ".inherit_child(id BIGINT) INHERITS (" + schema + ".inherit_parent)",
                        "CREATE TABLE " + schema + ".filled(id BIGINT) WITH (fillfactor=70)",
                        "CREATE TABLE " + schema + ".ruled(id BIGINT)",
                        "CREATE RULE sample_rule AS ON INSERT TO " + schema + ".ruled DO INSTEAD NOTHING",
                        "CREATE GLOBAL TEMPORARY TABLE " + schema + ".session_temp(id BIGINT) ON COMMIT PRESERVE ROWS",
                        "CREATE EXTENSION IF NOT EXISTS file_fdw",
                        "CREATE SERVER " + server + " FOREIGN DATA WRAPPER file_fdw",
                        "CREATE FOREIGN TABLE " + schema + ".foreign_table(id BIGINT) SERVER " + server
                                + " OPTIONS (filename '/tmp/q11.csv', format 'csv')"}) {
                    sql.execute(ddl);
                }
                for (String name : new String[]{"sample_view", "mat_view", "unlogged_table", "parent", "parent_a", "parent_b",
                        "inherit_parent", "inherit_child", "filled", "ruled", "session_temp", "foreign_table"}) {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(session.connection(), schema + "." + name), name);
                }
                sql.execute("CREATE TEMP TABLE sample(id BIGINT)");
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> database.queryRelationFingerprint(session.connection(), "sample"));
                sql.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                sql.execute("CREATE POLICY filter_rows ON " + table + " USING (id>0)");
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> database.queryRelationFingerprint(session.connection(), table));
                sql.execute("DROP POLICY filter_rows ON " + table);
                sql.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
                session.connection().setAutoCommit(false);
                try {
                    assertNotNull(database.queryRelationFingerprint(session.connection(), table));
                    assertFalse(session.connection().getAutoCommit());
                } finally {
                    session.connection().rollback();
                    session.connection().setAutoCommit(true);
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                sql.execute("DROP SERVER IF EXISTS " + server + " CASCADE");
            }
        }
    }

    @Test void selectOnlyRoleCanProveIdentityUntilPolicyCatalogIsHidden() throws Exception {
        String schema = "q11k_" + UUID.randomUUID().toString().replace("-", "");
        String role = schema + "_r";
        String rolePassword = "Q11" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "z!";
        try (var session = open("system", password())) {
            var sql = session.statement();
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.KINGBASE);
                String owner = database.queryRelationFingerprint(session.connection(), schema + ".sample");
                sql.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + rolePassword + "'");
                try {
                    sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
                    sql.execute("GRANT SELECT ON " + schema + ".sample TO " + role);
                    try (var reader = open(role, rolePassword)) {
                        String seen = database.queryRelationFingerprint(reader.connection(), schema + ".sample");
                        assertTrue(seen.matches("[a-f0-9]{64}"));
                        assertNotEquals(owner, seen);
                        assertEquals(seen, database.queryRelationFingerprint(reader.connection(), schema + ".sample"));
                    }
                    sql.execute("REVOKE SELECT ON pg_catalog.pg_policy FROM PUBLIC");
                    try (var reader = open(role, rolePassword)) {
                        var denied = assertThrows(SQLException.class, () -> database.queryRelationFingerprint(
                                reader.connection(), schema + ".sample"));
                        assertEquals("42501", denied.getSQLState());
                    }
                } finally {
                    sql.execute("GRANT SELECT ON pg_catalog.pg_policy TO PUBLIC");
                    sql.execute("DROP OWNED BY " + role);
                    sql.execute("DROP ROLE " + role);
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Session open(String user, String password) throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.kingbase.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        var loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.KINGBASE);
        var datasource = loader.dataSource(System.getProperty("datahub.test.kingbase.url"),
                ProductTypeEnum.KINGBASE.getDriveClassName(), user, password, new Properties());
        var connection = datasource.getConnection();
        var statement = connection.createStatement();
        statement.setQueryTimeout(20);
        return new Session(loader, connection, statement);
    }

    private static String password() { return System.getenv("DATAHUB_KINGBASE_TEST_PASSWORD"); }

    private record Session(VerifiedDriverTestHandle loader, Connection connection, java.sql.Statement statement) implements AutoCloseable {
        @Override public void close() throws Exception {
            try { statement.close(); } finally { try { connection.close(); } finally { loader.close(); } }
        }
    }
}
