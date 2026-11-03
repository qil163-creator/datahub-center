package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** ShenTong V8.0.251217 permanent heap. Does not use a business database. */
@EnabledIfSystemProperty(named = "datahub.test.oscar.url", matches = "jdbc:oscar://127[.]0[.]0[.]1:[0-9]+/OSRDB")
@EnabledIfSystemProperty(named = "datahub.test.oscar.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OSCAR_TEST_PASSWORD", matches = ".+")
class OscarRelationIdentityOscarTest {
    @Test void nativeHeapSurvivesWritesButNotTruncateRecreationOrOtherForms() throws Exception {
        String schema = "q11o_" + UUID.randomUUID().toString().replace("-", "");
        String directory = schema + "dir";
        try (var session = open("SYSDBA", password())) {
            var sql = session.statement();
            sql.execute("CREATE SCHEMA " + schema);
            try {
                String table = schema + ".sample";
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OSCAR);
                String before = database.queryRelationFingerprint(session.connection(), table);
                assertTrue(before.matches("[a-f0-9]{64}"));
                assertEquals(before, database.queryRelationFingerprint(session.connection(),
                        schema.toUpperCase(Locale.ROOT) + ".SAMPLE"));
                sql.execute("CREATE TABLE " + schema + ".\"q11case\"(id BIGINT)");
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> database.queryRelationFingerprint(session.connection(), schema + ".q11case"));
                sql.execute("CREATE TABLE " + schema + ".q11case(id BIGINT)");
                assertTrue(database.queryRelationFingerprint(session.connection(), schema + ".q11case")
                        .matches("[a-f0-9]{64}"));
                sql.execute("CREATE TABLE q11cur_" + schema.substring(5) + "(id BIGINT)");
                try {
                    assertTrue(database.queryRelationFingerprint(session.connection(),
                            "q11cur_" + schema.substring(5)).matches("[a-f0-9]{64}"));
                } finally {
                    sql.execute("DROP TABLE q11cur_" + schema.substring(5));
                }
                sql.execute("INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("UPDATE " + table + " SET amount=1 WHERE id=1");
                assertEquals(before, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("ALTER TABLE " + table + " ADD PRIMARY KEY(id)");
                assertEquals(before, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("TRUNCATE " + table);
                String truncated = database.queryRelationFingerprint(session.connection(), table);
                assertNotEquals(before, truncated);
                sql.execute("DROP TABLE " + table);
                sql.execute("CREATE TABLE " + table + "(id BIGINT, amount BIGINT)");
                assertNotEquals(truncated, database.queryRelationFingerprint(session.connection(), table));
                sql.execute("CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + table);
                sql.execute("CREATE GLOBAL TEMPORARY TABLE " + schema + ".session_temp(id BIGINT) ON COMMIT PRESERVE ROWS");
                sql.execute("CREATE GLOBAL TEMPORARY TABLE " + schema + ".txn_temp(id BIGINT) ON COMMIT DELETE ROWS");
                sql.execute("CREATE TABLE " + schema + ".parent(id INT) PARTITION BY RANGE (id) "
                        + "(PARTITION p1 VALUES LESS THAN (10), PARTITION p2 VALUES LESS THAN (100))");
                sql.execute("CREATE TABLE " + schema + ".filled(id INT) PCTFREE 20");
                sql.execute("CREATE TABLE " + schema + ".nolog(id INT) NOLOGGING");
                sql.execute("CREATE TABLE " + schema + ".ruled(id INT)");
                sql.execute("CREATE RULE " + schema + "_rule AS ON INSERT TO " + schema + ".ruled DO INSTEAD NOTHING");
                sql.execute("CREATE OR REPLACE DIRECTORY " + directory + " AS '/opt/ShenTong/extern_data_dir'");
                sql.execute("CREATE TABLE " + schema + ".foreign_table(id INT) ORGANIZATION EXTERNAL "
                        + "(DEFAULT DIRECTORY " + directory + " LOCATION ('q11.csv'))");
                for (String name : new String[]{"sample_view", "session_temp", "txn_temp", "parent", "filled",
                        "nolog", "ruled", "foreign_table"}) {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(session.connection(), schema + "." + name), name);
                }
                String local = "lt" + schema.substring(schema.length() - 8);
                sql.execute("CREATE LOCAL TEMPORARY TABLE " + local + "(id INT)");
                String tempSchema;
                try (ResultSet rows = sql.executeQuery("SELECT n.NSPNAME FROM INFO_SCHEM.SYS_CLASS c "
                        + "JOIN INFO_SCHEM.SYS_NAMESPACE n ON n.OID=c.RELNAMESPACE WHERE c.RELNAME='"
                        + local.toUpperCase(Locale.ROOT) + "'")) {
                    assertTrue(rows.next());
                    tempSchema = rows.getString(1);
                    assertFalse(rows.next());
                }
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> database.queryRelationFingerprint(session.connection(), tempSchema + "." + local));
                String ownerSchema = schema.toUpperCase(Locale.ROOT);
                sql.execute("BEGIN DBMS_RLS.ADD_POLICY('" + ownerSchema + "','SAMPLE','Q11POL','"
                        + ownerSchema + "','Q11POL'); END;");
                try {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(session.connection(), table));
                } finally {
                    sql.execute("BEGIN DBMS_RLS.DROP_POLICY('" + ownerSchema + "','SAMPLE','Q11POL'); END;");
                }
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
            }
        }
    }

    @Test void selectOnlyRoleCanProveIdentityUntilPolicyCatalogIsHidden() throws Exception {
        String schema = "q11o_" + UUID.randomUUID().toString().replace("-", "");
        String role = schema + "r";
        String rolePassword = "Q11" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (var session = open("SYSDBA", password())) {
            var sql = session.statement();
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1)");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OSCAR);
                String owner = database.queryRelationFingerprint(session.connection(), schema + ".sample");
                sql.execute("CREATE USER " + role + " IDENTIFIED BY " + rolePassword);
                try {
                    sql.execute("GRANT SELECT ON " + schema + ".sample TO " + role);
                    for (String catalog : new String[]{"SYS_CLASS", "SYS_NAMESPACE", "SYS_DATABASE", "SYS_SEGMENT",
                            "SYS_EXTERNAL_TABLE", "SYS_PARTOBJ", "SYS_TABPART", "SYS_INHERITS", "SYS_POLICY"}) {
                        sql.execute("GRANT SELECT ON INFO_SCHEM." + catalog + " TO " + role);
                    }
                    try (var reader = open(role.toUpperCase(Locale.ROOT), rolePassword)) {
                        String seen = database.queryRelationFingerprint(reader.connection(), schema + ".sample");
                        assertTrue(seen.matches("[a-f0-9]{64}"));
                        assertNotEquals(owner, seen);
                        assertEquals(seen, database.queryRelationFingerprint(reader.connection(), schema + ".sample"));
                    }
                    sql.execute("REVOKE SELECT ON INFO_SCHEM.SYS_POLICY FROM " + role);
                    try (var reader = open(role.toUpperCase(Locale.ROOT), rolePassword)) {
                        var denied = assertThrows(SQLException.class, () -> database.queryRelationFingerprint(
                                reader.connection(), schema + ".sample"));
                        assertNotEquals("0A000", denied.getSQLState());
                    }
                } finally {
                    sql.execute("DROP USER " + role);
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Session open(String user, String password) throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.oscar.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        var loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR);
        var datasource = loader.dataSource(System.getProperty("datahub.test.oscar.url"),
                ProductTypeEnum.OSCAR.getDriveClassName(), user, password, new Properties());
        var connection = datasource.getConnection();
        var statement = connection.createStatement();
        statement.setQueryTimeout(20);
        return new Session(loader, connection, statement);
    }

    private static String password() { return System.getenv("DATAHUB_OSCAR_TEST_PASSWORD"); }

    private record Session(VerifiedDriverTestHandle loader, Connection connection, java.sql.Statement statement) implements AutoCloseable {
        @Override public void close() throws Exception {
            try { statement.close(); } finally { try { connection.close(); } finally { loader.close(); } }
        }
    }
}
