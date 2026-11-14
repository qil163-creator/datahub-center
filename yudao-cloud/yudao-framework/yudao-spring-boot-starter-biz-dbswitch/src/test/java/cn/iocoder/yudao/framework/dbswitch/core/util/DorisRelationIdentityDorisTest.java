package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit disposable Doris only; never obtains application credentials or datasource. */
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DorisRelationIdentityDorisTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    private static final String SUFFIX = " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')";

    @BeforeEach
    void createOwnedObjects() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "");
        sql = connection.createStatement();
        sql.setQueryTimeout(15);
        schema = "identity_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema);
        sql.close();
        connection.setCatalog(schema);
        sql = connection.createStatement();
        sql.setQueryTimeout(15);
        sql.execute("CREATE TABLE sample(id BIGINT, val VARCHAR(40)) DUPLICATE KEY(id)" + SUFFIX);
    }

    @AfterEach
    void cleanOwnedObjects() throws Exception {
        if (connection == null) return;
        try (var owned = connection) {
            if (sql != null) sql.close();
            try (var cleanup = owned.createStatement()) {
                if (schema != null) cleanup.execute("DROP DATABASE IF EXISTS " + schema + " FORCE");
            }
        }
    }

    private String identity(String table) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DORIS).queryRelationFingerprint(connection, table);
    }

    @Test
    void factoryDetectsSameNameRebuildAndTruncateButNotOrdinaryWrites() throws Exception {
        String original = identity("sample");
        assertTrue(original.matches("[a-f0-9]{64}"));
        assertEquals(original, identity("`" + schema + "`.`sample`"));
        sql.execute("INSERT INTO sample VALUES(1,'x')");
        assertEquals(original, identity("sample"));
        sql.execute("TRUNCATE TABLE sample");
        String truncated = identity("sample");
        assertNotEquals(original, truncated, "Doris keeps TABLE_ID on truncate; partition generation must be bound");
        sql.execute("DROP TABLE sample FORCE");
        sql.execute("CREATE TABLE sample(id BIGINT, val VARCHAR(40)) DUPLICATE KEY(id)" + SUFFIX);
        assertNotEquals(truncated, identity("sample"));
    }

    @Test
    void respectsCatalogAndCaseWithoutTakingConnectionOwnership() throws Exception {
        String original = identity("sample");
        String other = schema + "_b";
        sql.execute("CREATE DATABASE " + other);
        try {
            sql.execute("CREATE TABLE " + other + ".sample(id BIGINT)" + SUFFIX);
            connection.setCatalog(other);
            assertNotEquals(original, identity("sample"));
            assertEquals(original, identity(schema + ".sample"));
            assertEquals(other, connection.getCatalog());
            assertTrue(connection.getAutoCommit());
            assertFalse(connection.isClosed());
            assertThrows(SQLException.class, () -> identity("SAMPLE"));
        } finally {
            connection.setCatalog(schema);
            sql.execute("DROP DATABASE " + other + " FORCE");
        }
    }

    @Test
    void rejectsViewsTemporaryShadowCrossCatalogAndSqlFragments() throws Exception {
        String original = identity("sample");
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()
                .metadata(ProductTypeEnum.DORIS);
        assertEquals(original, database.resolveCatalogRelation(connection, schema, "sample", 5).objectId());
        sql.execute("CREATE VIEW sample_view AS SELECT * FROM sample");
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("sample_view"));
        sql.execute("CREATE TEMPORARY TABLE sample(id BIGINT)" + SUFFIX);
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("sample"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity(schema + ".sample"));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.resolveCatalogRelation(connection, schema, "sample", 5));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.queryCatalogTableList(connection, schema, 10, 5));
        sql.execute("DROP TABLE sample");
        assertEquals(original, identity("sample"));
        assertThrows(IllegalArgumentException.class, () -> identity("internal." + schema + ".sample"));
        assertThrows(IllegalArgumentException.class, () -> identity("sample; SELECT 1"));
        assertThrows(IllegalArgumentException.class, () -> identity("sample /* comment */"));
        assertThrows(SQLException.class, () -> identity("missing"));
    }

    @Test
    void bindsEveryPermanentPartitionGeneration() throws Exception {
        sql.execute("CREATE TABLE ranges(id BIGINT) DUPLICATE KEY(id) PARTITION BY RANGE(id) "
                + "(PARTITION p1 VALUES LESS THAN ('10'), PARTITION p2 VALUES LESS THAN ('20'))" + SUFFIX);
        String before = identity("ranges");
        sql.execute("INSERT INTO ranges VALUES(1),(11)");
        assertEquals(before, identity("ranges"));
        sql.execute("TRUNCATE TABLE ranges PARTITION(p1)");
        assertNotEquals(before, identity("ranges"));
    }

    @Test
    void effectiveReaderIsBoundAndPrivilegeRevocationDoesNotForgeProof() throws Exception {
        String user = "reader_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // All accounts/objects are disposable on an explicitly loopback-bound isolated container.
        sql.execute("CREATE USER '" + user + "'");
        try {
            sql.execute("GRANT SELECT_PRIV ON " + schema + ".sample TO '" + user + "'");
            try (var reader = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), user, "")) {
                reader.setCatalog(schema);
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DORIS);
                assertNotEquals(identity("sample"), database.queryRelationFingerprint(reader, "sample"));
                sql.execute("REVOKE SELECT_PRIV ON " + schema + ".sample FROM '" + user + "'");
                assertThrows(SQLException.class, () -> database.queryRelationFingerprint(reader, "sample"));
            }
        } finally { sql.execute("DROP USER '" + user + "'"); }
    }
}
