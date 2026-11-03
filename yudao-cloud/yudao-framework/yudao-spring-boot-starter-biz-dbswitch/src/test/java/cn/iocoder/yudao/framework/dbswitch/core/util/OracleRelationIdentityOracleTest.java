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
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Disposable Oracle Free PDB only. Password stays in DATAHUB_TEST_ORACLE_PASSWORD. */
@EnabledIfSystemProperty(named = "datahub.test.oracle.url", matches = "jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_ORACLE_PASSWORD", matches = ".+")
class OracleRelationIdentityOracleTest {
    @Test void nonemptyTruncateAndRebuildChangeHeapIdentityButEmptyTruncateDoesNot() throws Exception {
        String schema = "Q11O_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String reader = schema + "R";
        String password = "Q11o" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = open("SYSTEM", System.getenv("DATAHUB_TEST_ORACLE_PASSWORD")); var sql = admin.createStatement()) {
            sql.setQueryTimeout(30);
            execute(sql, "CREATE USER " + schema + " IDENTIFIED BY \"" + password + "\" DEFAULT TABLESPACE USERS QUOTA 40M ON USERS");
            execute(sql, "CREATE USER " + reader + " IDENTIFIED BY \"" + password + "\"");
            execute(sql, "GRANT CREATE SESSION, CREATE TABLE, CREATE MATERIALIZED VIEW TO " + schema);
            execute(sql, "GRANT CREATE SESSION TO " + reader);
            try {
                String table = schema + ".SAMPLE";
                execute(sql, "CREATE TABLE " + table + "(ID NUMBER(19,0), AMOUNT NUMBER(19,0))");
                execute(sql, "CREATE TABLE " + schema + ".SIBLING(ID NUMBER(19,0))");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.ORACLE);
                String before = database.queryRelationFingerprint(admin, table);
                String sibling = database.queryRelationFingerprint(admin, schema + ".SIBLING");
                assertTrue(before.matches("[a-f0-9]{64}"));
                assertNotEquals(before, sibling);
                assertEquals(before, database.queryRelationFingerprint(admin, schema.toLowerCase(Locale.ROOT) + ".sample"));
                execute(sql, "ALTER SESSION SET CURRENT_SCHEMA = " + schema);
                try {
                    assertEquals(before, database.queryRelationFingerprint(admin, table));
                    assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(admin, "SAMPLE"));
                } finally {
                    execute(sql, "ALTER SESSION SET CURRENT_SCHEMA = SYSTEM");
                }
                execute(sql, "INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(admin, table));
                execute(sql, "UPDATE " + table + " SET AMOUNT=1 WHERE ID=1");
                assertEquals(before, database.queryRelationFingerprint(admin, table));
                execute(sql, "DELETE FROM " + table);
                assertEquals(before, database.queryRelationFingerprint(admin, table));
                execute(sql, "INSERT INTO " + table + " VALUES(1,1)");
                assertEquals(before, database.queryRelationFingerprint(admin, table));
                execute(sql, "TRUNCATE TABLE " + table);
                String truncated = database.queryRelationFingerprint(admin, table);
                assertNotEquals(before, truncated);
                assertEquals(sibling, database.queryRelationFingerprint(admin, schema + ".SIBLING"));
                execute(sql, "TRUNCATE TABLE " + table);
                assertEquals(truncated, database.queryRelationFingerprint(admin, table));
                execute(sql, "INSERT INTO " + table + " VALUES(1,1)");
                assertEquals(truncated, database.queryRelationFingerprint(admin, table));
                execute(sql, "TRUNCATE TABLE " + table);
                String truncatedAgain = database.queryRelationFingerprint(admin, table);
                assertNotEquals(truncated, truncatedAgain);
                execute(sql, "DROP TABLE " + table + " PURGE");
                execute(sql, "CREATE TABLE " + table + "(ID NUMBER(19,0), AMOUNT NUMBER(19,0))");
                String recreated = database.queryRelationFingerprint(admin, table);
                assertNotEquals(truncatedAgain, recreated);
                execute(sql, "COMMENT ON TABLE " + table + " IS 'q11'");
                execute(sql, "CREATE INDEX " + schema + ".SAMPLE_ID ON " + table + "(ID)");
                assertEquals(recreated, database.queryRelationFingerprint(admin, table));
                execute(sql, "INSERT INTO " + table + " VALUES(1,1)");
                assertEquals(recreated, database.queryRelationFingerprint(admin, table));
                execute(sql, "ALTER TABLE " + table + " MOVE");
                assertNotEquals(recreated, database.queryRelationFingerprint(admin, table));
                assertEquals(sibling, database.queryRelationFingerprint(admin, schema + ".SIBLING"));
                for (String ddl : new String[]{
                        "CREATE VIEW " + schema + ".SAMPLE_VIEW AS SELECT * FROM " + table,
                        "CREATE GLOBAL TEMPORARY TABLE " + schema + ".GTT_TABLE(ID NUMBER(19,0)) ON COMMIT PRESERVE ROWS",
                        "CREATE TABLE " + schema + ".IOT_TABLE(ID NUMBER(19,0) PRIMARY KEY, NAME VARCHAR2(20)) ORGANIZATION INDEX",
                        "CREATE TABLE " + schema + ".PART_TABLE(ID NUMBER(19,0)) PARTITION BY RANGE (ID) "
                                + "(PARTITION P1 VALUES LESS THAN (10), PARTITION P2 VALUES LESS THAN (100))",
                        "CREATE TABLE " + schema + ".EXT_TABLE(ID NUMBER(19,0)) ORGANIZATION EXTERNAL "
                                + "(TYPE ORACLE_LOADER DEFAULT DIRECTORY DATA_PUMP_DIR "
                                + "ACCESS PARAMETERS (RECORDS DELIMITED BY NEWLINE) LOCATION ('missing.dat'))",
                        "CREATE CLUSTER " + schema + ".CL(ID NUMBER(19,0))",
                        "CREATE TABLE " + schema + ".CLT(ID NUMBER(19,0)) CLUSTER " + schema + ".CL(ID)",
                        "CREATE TABLE " + schema + ".\"KeepCase\"(ID NUMBER(19,0))"}) {
                    execute(sql, ddl);
                }
                try (var owner = open(schema, password); var ownerSql = owner.createStatement()) {
                    ownerSql.setQueryTimeout(30);
                    execute(ownerSql, "CREATE MATERIALIZED VIEW MV_TABLE AS SELECT ID FROM SAMPLE");
                }
                for (String name : new String[]{"SAMPLE_VIEW", "GTT_TABLE", "IOT_TABLE", "PART_TABLE", "EXT_TABLE",
                        "CLT", "MV_TABLE", "KeepCase"}) {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(admin, schema + "." + name), name);
                }
                execute(sql, "CREATE OR REPLACE FUNCTION " + schema + ".ALLOW_ALL(object_schema VARCHAR2, object_name VARCHAR2) "
                        + "RETURN VARCHAR2 IS BEGIN RETURN '1=1'; END;");
                execute(sql, "BEGIN DBMS_RLS.ADD_POLICY('" + schema + "','SIBLING','Q11_POL','" + schema + "','ALLOW_ALL','SELECT'); END;");
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> database.queryRelationFingerprint(admin, schema + ".SIBLING"));
                execute(sql, "CREATE VIEW " + schema + ".ALL_OBJECTS AS SELECT 1 OBJECT_ID FROM DUAL");
                execute(sql, "CREATE OR REPLACE FUNCTION " + schema + ".SYS_CONTEXT(namespace VARCHAR2, parameter VARCHAR2) "
                        + "RETURN VARCHAR2 IS BEGIN RETURN 'SHADOW'; END;");
                String unshadowed = database.queryRelationFingerprint(admin, table);
                execute(sql, "ALTER SESSION SET CURRENT_SCHEMA = " + schema);
                try {
                    assertEquals(unshadowed, database.queryRelationFingerprint(admin, table));
                } finally {
                    execute(sql, "ALTER SESSION SET CURRENT_SCHEMA = SYSTEM");
                }
                admin.setAutoCommit(false);
                try {
                    assertNotNull(database.queryRelationFingerprint(admin, table));
                    assertFalse(admin.getAutoCommit());
                } finally {
                    admin.rollback();
                    admin.setAutoCommit(true);
                }
                execute(sql, "GRANT SELECT ON " + table + " TO " + reader);
                String owner = database.queryRelationFingerprint(admin, table);
                try (var readerConnection = open(reader, password)) {
                    String seen = database.queryRelationFingerprint(readerConnection, table);
                    assertTrue(seen.matches("[a-f0-9]{64}"));
                    assertNotEquals(owner, seen);
                    assertEquals(seen, database.queryRelationFingerprint(readerConnection, table));
                }
                execute(sql, "REVOKE SELECT ON " + table + " FROM " + reader);
                try (var readerConnection = open(reader, password)) {
                    var hidden = assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(readerConnection, table));
                    assertEquals("0A000", hidden.getSQLState());
                }
            } finally {
                execute(sql, "DROP USER IF EXISTS " + reader + " CASCADE");
                execute(sql, "DROP USER IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Connection open(String user, String password) throws SQLException {
        var connection = DriverManager.getConnection(System.getProperty("datahub.test.oracle.url"), user, password);
        connection.setAutoCommit(true);
        return connection;
    }

    private static void execute(Statement sql, String text) throws SQLException {
        try {
            sql.execute(text);
        } catch (SQLException failure) {
            if (text.startsWith("CREATE USER ")) throw new SQLException("Fixture user creation failed", failure.getSQLState(), failure.getErrorCode());
            throw failure;
        }
    }
}
