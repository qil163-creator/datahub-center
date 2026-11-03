package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class TidbRelationIdentityTidbTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"), "root", "");
        schema = "tidb_identity_" + UUID.randomUUID().toString().replace("-", "");
        try (var create = connection.createStatement()) { create.execute("CREATE DATABASE " + schema); }
        connection.setCatalog(schema); sql = connection.createStatement(); sql.setQueryTimeout(15);
        sql.execute("CREATE TABLE sample(id BIGINT PRIMARY KEY,val VARCHAR(40))");
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if (connection != null && schema != null) {
                connection.setCatalog("information_schema");
                try (var cleanup = connection.createStatement()) { cleanup.execute("DROP DATABASE IF EXISTS " + schema); }
            }
        } finally { if (sql != null) sql.close(); if (connection != null) connection.close(); }
    }
    private String identity(String reference) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB).queryRelationFingerprint(connection, reference);
    }
    @Test void nativeTableGenerationAndCaseTwoResolutionAreBound() throws Exception {
        String before = identity("sample"); assertTrue(before.matches("[0-9a-f]{64}"));
        assertEquals(before, identity(schema + ".SAMPLE"));
        sql.execute("INSERT INTO sample VALUES(1,'x')"); assertEquals(before, identity("sample"));
        sql.execute("TRUNCATE TABLE sample"); String truncated = identity("sample"); assertNotEquals(before, truncated);
        sql.execute("DROP TABLE sample"); sql.execute("CREATE TABLE sample(id BIGINT PRIMARY KEY,val VARCHAR(40))");
        assertNotEquals(truncated, identity("sample"));
        assertEquals(schema, connection.getCatalog()); assertTrue(connection.getAutoCommit()); assertFalse(connection.isClosed());
    }
    @Test void viewsAndTemporaryShadowsCannotUsePermanentSystemTableIds() throws Exception {
        String before = identity("sample"); sql.execute("CREATE VIEW sample_view AS SELECT * FROM sample");
        assertThrows(SQLException.class, () -> identity("sample_view"));
        sql.execute("CREATE TEMPORARY TABLE sample(id BIGINT)");
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("sample"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity(schema + ".sample"));
        sql.execute("DROP TEMPORARY TABLE sample"); assertEquals(before, identity("sample"));
        sql.execute("CREATE GLOBAL TEMPORARY TABLE global_temp(id BIGINT) ON COMMIT DELETE ROWS");
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("global_temp"));
    }
    @Test void partitionTruncationChangesNativeIdentity() throws Exception {
        sql.execute("CREATE TABLE ranges(id BIGINT PRIMARY KEY) PARTITION BY RANGE(id)"
                + "(PARTITION p1 VALUES LESS THAN (10), PARTITION p2 VALUES LESS THAN (20))");
        String before = identity("ranges"); sql.execute("INSERT INTO ranges VALUES(1),(11)");
        assertEquals(before, identity("ranges")); sql.execute("ALTER TABLE ranges TRUNCATE PARTITION p1");
        assertNotEquals(before, identity("ranges"));
    }
    @Test void unverifiedStaleReadsAreRejectedWithoutChangingCallerSettings() throws Exception {
        String before = identity("sample");
        sql.execute("SET @@tidb_read_staleness=-1");
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("sample"));
        try (var rows = sql.executeQuery("SELECT @@tidb_read_staleness")) { assertTrue(rows.next()); assertEquals(-1, rows.getInt(1)); }
        sql.execute("SET @@tidb_read_staleness=0");
        sql.execute("SET @@tidb_replica_read='follower'");
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("sample"));
        sql.execute("SET @@tidb_replica_read='leader'"); assertEquals(before, identity("sample"));
    }
    @Test void metadataPrivilegesAndEffectiveRoleAreNotInferredFromReadableRows() throws Exception {
        String user = "reader_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String role = user + "_role";
        sql.execute("CREATE USER '" + user + "'"); sql.execute("CREATE ROLE '" + role + "'");
        try {
            sql.execute("GRANT SELECT ON " + schema + ".sample TO '" + user + "'");
            try (var reader = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"), user, "");
                 var query = reader.createStatement()) {
                try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + schema + ".sample")) { assertTrue(rows.next()); }
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB);
                assertThrows(SQLException.class, () -> database.queryRelationFingerprint(reader, schema + ".sample"));
                sql.execute("GRANT SELECT ON mysql.tidb TO '" + user + "'");
                String direct = database.queryRelationFingerprint(reader, schema + ".sample");
                assertNotEquals(identity("sample"), direct);
                sql.execute("GRANT '" + role + "' TO '" + user + "'"); query.execute("SET ROLE '" + role + "'");
                assertNotEquals(direct, database.queryRelationFingerprint(reader, schema + ".sample"));
                sql.execute("REVOKE SELECT ON mysql.tidb FROM '" + user + "'");
                assertThrows(SQLException.class, () -> database.queryRelationFingerprint(reader, schema + ".sample"));
            }
        } finally { sql.execute("DROP USER '" + user + "'"); sql.execute("DROP ROLE '" + role + "'"); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEGIN", "SET autocommit=0", "SET tidb_enable_external_ts_read=1",
            "SET TRANSACTION READ ONLY AS OF TIMESTAMP NOW() - INTERVAL 1 SECOND",
            "START TRANSACTION READ ONLY AS OF TIMESTAMP NOW() - INTERVAL 1 SECOND"})
    void transactionAndExternalTimestampModesCannotClaimCurrentReadIdentity(String mode) throws Exception {
        String before = identity("sample");
        try (var isolated = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"), "root", "");
             var command = isolated.createStatement()) {
            command.setQueryTimeout(10); command.execute(mode);
            String state = readModes(command);
            assertThrows(SQLFeatureNotSupportedException.class, () ->
                    cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB).queryRelationFingerprint(isolated, schema + ".sample"));
            assertEquals(state, readModes(command), "identity probe must not consume/reset a pending stale-read or transaction");
        }
        assertEquals(before, identity("sample"));
    }

    private String readModes(Statement statement) throws SQLException {
        try (var rows = statement.executeQuery("SELECT @@autocommit,@@tidb_current_ts,@@tx_read_ts,@@tidb_enable_external_ts_read")) {
            assertTrue(rows.next());
            return rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getString(3) + "/" + rows.getString(4);
        }
    }
}
