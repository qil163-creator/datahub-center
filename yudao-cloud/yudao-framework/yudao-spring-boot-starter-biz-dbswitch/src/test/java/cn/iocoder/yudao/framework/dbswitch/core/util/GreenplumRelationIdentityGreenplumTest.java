package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres\\?sslmode=disable")
@EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
class GreenplumRelationIdentityGreenplumTest {
    @Test void nativeHeapHashSurvivesWritesButNotTruncateRecreationOrOtherForms() throws Exception {
        String schema = "q11g_" + UUID.randomUUID().toString().replace("-", "");
        Class.forName("org.postgresql.Driver");
        try (var connection = DriverManager.getConnection(url(), "gpadmin", password());
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(20);
            sql.execute("CREATE SCHEMA " + schema);
            try {
                String table = schema + ".sample";
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.GREENPLUM);
                String before = database.queryRelationFingerprint(connection, table);
                assertTrue(before.matches("[a-f0-9]{64}"));
                sql.execute("CREATE FUNCTION " + schema + ".version() RETURNS TEXT LANGUAGE SQL AS 'SELECT ''shadow''::text'");
                sql.execute("SET search_path=" + schema + ", pg_catalog");
                try { assertEquals(before, database.queryRelationFingerprint(connection, table)); }
                finally { sql.execute("RESET search_path"); }
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("TRUNCATE " + table);
                String truncated = database.queryRelationFingerprint(connection, table);
                assertNotEquals(before, truncated);
                sql.execute("DROP TABLE " + table);
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                assertNotEquals(truncated, database.queryRelationFingerprint(connection, table));
                for (String ddl : new String[]{
                        "CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + table,
                        "CREATE TABLE " + schema + ".random_table(id BIGINT) DISTRIBUTED RANDOMLY",
                        "CREATE TABLE " + schema + ".replicated_table(id BIGINT) DISTRIBUTED REPLICATED",
                        "CREATE TABLE " + schema + ".ao_table(id BIGINT) WITH (appendoptimized=true, orientation=row) DISTRIBUTED BY (id)",
                        "CREATE TABLE " + schema + ".column_table(id BIGINT) WITH (appendoptimized=true, orientation=column) DISTRIBUTED BY (id)",
                        "CREATE TABLE " + schema + ".parent(id BIGINT) PARTITION BY RANGE (id) (START (0) END (100) EVERY (50))",
                        "CREATE EXTERNAL WEB TABLE " + schema + ".external_table(id BIGINT) EXECUTE 'echo 1' ON COORDINATOR FORMAT 'TEXT'"}) {
                    sql.execute(ddl);
                }
                for (String name : new String[]{"sample_view", "random_table", "replicated_table", "ao_table",
                        "column_table", "parent", "parent_1_prt_1", "external_table"}) {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(connection, schema + "." + name), name);
                }
                sql.execute("CREATE TEMP TABLE sample(id BIGINT)");
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
                sql.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                sql.execute("CREATE POLICY filter_rows ON " + table + " USING (id>0)");
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, table));
                sql.execute("DROP POLICY filter_rows ON " + table);
                sql.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
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

    @Test void selectOnlyRoleCanProveIdentityUntilDistributionCatalogIsHidden() throws Exception {
        String schema = "q11g_" + UUID.randomUUID().toString().replace("-", "");
        String role = schema + "_r";
        String rolePassword = "Q11g" + UUID.randomUUID().toString().replace("-", "") + "z";
        Class.forName("org.postgresql.Driver");
        try (var connection = DriverManager.getConnection(url(), "gpadmin", password());
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(20);
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.GREENPLUM);
                String owner = database.queryRelationFingerprint(connection, schema + ".sample");
                sql.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + rolePassword + "'");
                try {
                    sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
                    sql.execute("GRANT SELECT ON " + schema + ".sample TO " + role);
                    try (var reader = DriverManager.getConnection(url(), role, rolePassword)) {
                        // current_user is part of the native proof, so a different login cannot reuse the owner's fingerprint.
                        String seen = database.queryRelationFingerprint(reader, schema + ".sample");
                        assertTrue(seen.matches("[a-f0-9]{64}"));
                        assertNotEquals(owner, seen);
                        assertEquals(seen, database.queryRelationFingerprint(reader, schema + ".sample"));
                    }
                    sql.execute("REVOKE SELECT ON pg_catalog.gp_distribution_policy FROM PUBLIC");
                    try (var reader = DriverManager.getConnection(url(), role, rolePassword)) {
                        var denied = assertThrows(SQLException.class, () -> cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.GREENPLUM)
                                .queryRelationFingerprint(reader, schema + ".sample"));
                        assertEquals("42501", denied.getSQLState());
                    }
                } finally {
                    sql.execute("GRANT SELECT ON pg_catalog.gp_distribution_policy TO PUBLIC");
                    sql.execute("DROP OWNED BY " + role);
                    sql.execute("DROP ROLE " + role);
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static String url() { return System.getProperty("datahub.test.greenplum.url"); }
    private static String password() { return System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"); }
}
