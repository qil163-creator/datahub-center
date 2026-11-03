package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.opengauss.url", matches = "jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/(?:postgres|dbswitch_test)")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OPENGAUSS_TEST_PASSWORD", matches = ".+")
class OpengaussRelationIdentityOpengaussTest {
    @Test void nativeGenerationSurvivesWritesButNotTruncateOrRecreation() throws Exception {
        String schema = "og_identity_" + UUID.randomUUID().toString().replace("-", "");
        Class.forName("org.opengauss.Driver");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.opengauss.url"),
                System.getProperty("datahub.test.opengauss.user", "codex_test"), System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD")); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE SCHEMA " + schema);
            try {
                String table = schema + ".sample";
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OPENGAUSS);
                String before = database.queryRelationFingerprint(connection, table);
                assertTrue(before.matches("[a-f0-9]{64}"));
                sql.execute("CREATE FUNCTION " + schema + ".version() RETURNS TEXT LANGUAGE SQL AS 'SELECT ''shadow''::text'");
                sql.execute("SET search_path=" + schema + ", pg_catalog");
                try { assertEquals(before, database.queryRelationFingerprint(connection, table)); }
                finally { sql.execute("RESET search_path"); }
                sql.execute("INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("TRUNCATE " + table);
                String truncated = database.queryRelationFingerprint(connection, table);
                assertNotEquals(before, truncated);
                sql.execute("DROP TABLE " + table); sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                assertNotEquals(truncated, database.queryRelationFingerprint(connection, table));
                sql.execute("CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + table);
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, schema + ".sample_view"));
                sql.execute("CREATE TEMP TABLE sample(id BIGINT)");
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
                sql.execute("CREATE ROW LEVEL SECURITY POLICY filter_rows ON " + table + " USING (id>0)");
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, table));
                sql.execute("DROP ROW LEVEL SECURITY POLICY filter_rows ON " + table);
                sql.execute("CREATE TABLE " + schema + ".column_table(id BIGINT) WITH (orientation=column)");
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, schema + ".column_table"));
                sql.execute("CREATE TABLE " + schema + ".partition_table(id BIGINT) PARTITION BY RANGE(id) "
                        + "(PARTITION p0 VALUES LESS THAN (10), PARTITION p1 VALUES LESS THAN (MAXVALUE))");
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, schema + ".partition_table"));
                sql.execute("CREATE RESOURCE LABEL " + schema + " ADD COLUMN(" + table + ".amount)");
                try {
                    sql.execute("CREATE MASKING POLICY " + schema + " maskall ON LABEL(" + schema + ")");
                    try {
                        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, table));
                    } finally { sql.execute("DROP MASKING POLICY " + schema); }
                } finally { sql.execute("DROP RESOURCE LABEL " + schema); }
                connection.setAutoCommit(false);
                try { assertNotNull(database.queryRelationFingerprint(connection, table)); assertFalse(connection.getAutoCommit()); }
                finally { connection.rollback(); connection.setAutoCommit(true); }
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void readableDataWithoutSecurityCatalogAccessCannotProveIdentity() throws Exception {
        String schema = "og_reader_" + UUID.randomUUID().toString().replace("-", "");
        String user = schema + "_user"; // openGauss CREATE USER also creates a same-name schema.
        Class.forName("org.opengauss.Driver");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.opengauss.url"),
                System.getProperty("datahub.test.opengauss.user", "codex_test"), System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD")); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1)");
                String temporaryPassword = "Q11!" + UUID.randomUUID().toString().replace("-", "").substring(0, 22) + "z";
                sql.execute("CREATE USER " + user + " PASSWORD '" + temporaryPassword + "'");
                try {
                    sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + user);
                    sql.execute("GRANT SELECT ON " + schema + ".sample TO " + user);
                    try (var reader = DriverManager.getConnection(System.getProperty("datahub.test.opengauss.url"), user, temporaryPassword);
                         var read = reader.createStatement()) {
                        read.setQueryTimeout(10);
                        try (var rows = read.executeQuery("SELECT count(*) FROM " + schema + ".sample")) {
                            assertTrue(rows.next()); assertEquals(1, rows.getLong(1));
                        }
                        var denied = assertThrows(SQLException.class, () -> cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OPENGAUSS)
                                .queryRelationFingerprint(reader, schema + ".sample"));
                        assertEquals("42501", denied.getSQLState());
                    }
                } finally { sql.execute("DROP OWNED BY " + user); sql.execute("DROP ROLE " + user); }
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
