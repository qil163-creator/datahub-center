package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
class OceanbaseRelationIdentityOceanbaseTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    @BeforeEach void setup() throws Exception {
        Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName());
        connection = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), "root@test", "");
        schema = "ob_identity_" + UUID.randomUUID().toString().replace("-", "");
        sql = connection.createStatement(); sql.setQueryTimeout(15);
        sql.execute("CREATE DATABASE " + schema);
        sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,val VARCHAR(40))");
    }
    @AfterEach void cleanup() throws Exception {
        try { if (sql != null && schema != null) sql.execute("DROP DATABASE IF EXISTS " + schema); }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); }
    }
    private String identity(Connection c, String reference) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OCEANBASE).queryRelationFingerprint(c, reference);
    }
    private String identity() throws SQLException { return identity(connection, schema + ".sample"); }

    @Test void nativeIdentityBindsTabletsAndIsNotJustTheUnchangedTableId() throws Exception {
        String before = identity(); assertTrue(before.matches("[0-9a-f]{64}"));
        assertEquals(before, identity(connection, schema.toUpperCase() + ".SAMPLE"));
        sql.execute("INSERT INTO " + schema + ".sample VALUES(1,'x')"); assertEquals(before, identity());
        sql.execute("TRUNCATE TABLE " + schema + ".sample"); String truncated = identity(); assertNotEquals(before, truncated);
        sql.execute("DROP TABLE " + schema + ".sample");
        sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,val VARCHAR(40))");
        assertNotEquals(truncated, identity()); assertTrue(connection.getAutoCommit()); assertFalse(connection.isClosed());
    }

    @Test void independentConnectionsAndFactoriesUseTheSamePersistentTenantIdentity() throws Exception {
        String before = identity();
        try (var other = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), "root@test", "")) {
            assertEquals(before, identity(other, schema + ".sample"));
        }
    }

    @Test void viewsAndTemporaryShadowsCannotBorrowPermanentTablets() throws Exception {
        String before = identity(); sql.execute("CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + schema + ".sample");
        assertThrows(SQLException.class, () -> identity(connection, schema + ".sample_view"));
        sql.execute("CREATE TEMPORARY TABLE " + schema + ".sample(id BIGINT)");
        try { assertThrows(SQLFeatureNotSupportedException.class, this::identity); }
        finally { sql.execute("DROP TEMPORARY TABLE " + schema + ".sample"); }
        assertEquals(before, identity());
    }

    @Test void partitionTruncationChangesTheGeneration() throws Exception {
        sql.execute("CREATE TABLE " + schema + ".ranges(id BIGINT PRIMARY KEY) PARTITION BY RANGE(id)"
                + "(PARTITION p1 VALUES LESS THAN (10), PARTITION p2 VALUES LESS THAN (20))");
        String before = identity(connection, schema + ".ranges");
        sql.execute("INSERT INTO " + schema + ".ranges VALUES(1),(11)");
        assertEquals(before, identity(connection, schema + ".ranges"));
        sql.execute("ALTER TABLE " + schema + ".ranges TRUNCATE PARTITION p1");
        assertNotEquals(before, identity(connection, schema + ".ranges"));
    }

    @ParameterizedTest @ValueSource(strings = {"BEGIN", "SET autocommit=0", "SET ob_read_consistency='WEAK'"})
    void unverifiedReadModesDoNotGetIdentityOrHaveTheirStateReset(String mode) throws Exception {
        String before = identity();
        try (var other = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), "root@test", "");
             var command = other.createStatement()) {
            command.execute(mode);
            String state = readState(command);
            assertThrows(SQLFeatureNotSupportedException.class, () -> identity(other, schema + ".sample"));
            assertEquals(state, readState(command));
        }
        assertEquals(before, identity());
    }
    private String readState(Statement command) throws SQLException {
        try (var rows = command.executeQuery("SELECT @@autocommit,@@ob_read_consistency,"
                + "(SELECT TRANS_ID FROM oceanbase.GV$OB_SESSION WHERE ID=CONNECTION_ID())")) {
            assertTrue(rows.next()); return rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getString(3);
        }
    }

    @Test void metadataPrivilegeCannotBeInferredFromReadableBusinessRows() throws Exception {
        String host;
        try (var rows = sql.executeQuery("SELECT USER()")) {
            assertTrue(rows.next()); String user = rows.getString(1); host = user.substring(user.lastIndexOf('@') + 1);
            assertTrue(host.matches("[A-Za-z0-9.:_-]+"));
        }
        String user = "ob_reader_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String account = "'" + user + "'@'" + host + "'";
        sql.execute("CREATE USER " + account);
        try {
            sql.execute("GRANT SELECT ON " + schema + ".sample TO " + account);
            try (var reader = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), user + "@test", "");
                 var query = reader.createStatement()) {
                query.setQueryTimeout(10);
                try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + schema + ".sample")) { assertTrue(rows.next()); }
                assertThrows(SQLException.class, () -> identity(reader, schema + ".sample"));
                for (String view : new String[]{"GV$OB_SESSION", "DBA_OB_TENANTS", "DBA_OB_SYS_VARIABLES", "DBA_OB_TABLE_LOCATIONS"}) {
                    sql.execute("GRANT SELECT ON oceanbase.`" + view + "` TO " + account);
                }
                String limited = identity(reader, schema + ".sample");
                assertNotEquals(identity(), limited, "effective principal is bound into identity");
                sql.execute("REVOKE SELECT ON oceanbase.DBA_OB_SYS_VARIABLES FROM " + account);
                assertThrows(SQLException.class, () -> identity(reader, schema + ".sample"));
            }
        } finally { sql.execute("DROP USER " + account); }
    }

    @Test void subpartitionGenerationsAreCovered() throws Exception {
        sql.execute("CREATE TABLE " + schema + ".subparts(id BIGINT PRIMARY KEY) PARTITION BY RANGE(id)"
                + " SUBPARTITION BY HASH(id) SUBPARTITIONS 2"
                + " (PARTITION p1 VALUES LESS THAN (10), PARTITION p2 VALUES LESS THAN (20))");
        String before = identity(connection, schema + ".subparts");
        sql.execute("INSERT INTO " + schema + ".subparts VALUES(1),(11)");
        assertEquals(before, identity(connection, schema + ".subparts"));
        sql.execute("ALTER TABLE " + schema + ".subparts TRUNCATE PARTITION p1");
        assertNotEquals(before, identity(connection, schema + ".subparts"));
    }

    @ParameterizedTest @ValueSource(strings = {"each column", "all columns, each column"})
    void columnAndHybridStorageCannotClaimUnverifiedRowStoreIdentity(String columns) throws Exception {
        sql.execute("CREATE TABLE " + schema + ".column_data(id BIGINT PRIMARY KEY,val VARCHAR(40)) WITH COLUMN GROUP(" + columns + ")");
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity(connection, schema + ".column_data"));
    }
}
