package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class StarrocksRelationIdentityStarrocksTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    private static final String SUFFIX = " DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')";

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.starrocks.url"), "root", "");
        sql = connection.createStatement(); sql.setQueryTimeout(15);
        schema = "sr_identity_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema); sql.close(); connection.setCatalog(schema);
        sql = connection.createStatement(); sql.setQueryTimeout(15);
        sql.execute("CREATE TABLE sample(id BIGINT,val VARCHAR(40))" + SUFFIX);
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if (connection != null && schema != null) {
                connection.setCatalog("information_schema");
                try (var cleanup = connection.createStatement()) { cleanup.execute("DROP DATABASE IF EXISTS " + schema + " FORCE"); }
            }
        }
        finally { if (sql != null) sql.close(); if (connection != null) connection.close(); }
    }
    private String identity(String reference) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.STARROCKS).queryRelationFingerprint(connection, reference);
    }

    @Test void nativeGenerationsDetectRebuildAndTruncateNotOrdinaryWrites() throws Exception {
        String original = identity("sample"); assertTrue(original.matches("[0-9a-f]{64}"));
        assertEquals(original, identity(schema + ".sample"));
        sql.execute("INSERT INTO sample VALUES(1,'first')"); assertEquals(original, identity("sample"));
        sql.execute("TRUNCATE TABLE sample"); String truncated = identity("sample");
        assertNotEquals(original, truncated);
        sql.execute("DROP TABLE sample FORCE"); sql.execute("CREATE TABLE sample(id BIGINT,val VARCHAR(40))" + SUFFIX);
        assertNotEquals(truncated, identity("sample"));
        assertEquals(schema, connection.getCatalog()); assertTrue(connection.getAutoCommit()); assertFalse(connection.isClosed());
    }

    @Test void temporaryShadowsViewsAndAmbiguousMetadataCannotBorrowPermanentIdentity() throws Exception {
        String original = identity("sample");
        sql.execute("CREATE VIEW sample_view AS SELECT * FROM sample");
        assertThrows(SQLException.class, () -> identity("sample_view"));
        sql.execute("CREATE TEMPORARY TABLE sample(id BIGINT)" + SUFFIX);
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity("sample"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> identity(schema + ".sample"));
        sql.execute("DROP TEMPORARY TABLE sample"); assertEquals(original, identity("sample"));
        assertThrows(SQLException.class, () -> identity("SAMPLE"));
        assertThrows(SQLException.class, () -> identity("missing"));
    }

    @Test void everyPartitionGenerationIsBound() throws Exception {
        sql.execute("CREATE TABLE ranges(id BIGINT) DUPLICATE KEY(id) PARTITION BY RANGE(id)"
                + "(PARTITION p1 VALUES LESS THAN ('10'),PARTITION p2 VALUES LESS THAN ('20'))"
                + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
        String original = identity("ranges");
        sql.execute("INSERT INTO ranges VALUES(1),(11)"); assertEquals(original, identity("ranges"));
        sql.execute("TRUNCATE TABLE ranges PARTITION(p1)"); assertNotEquals(original, identity("ranges"));
    }

    @Test void readableRowsDoNotProveUnfilteredMetadataAndRolesAreBound() throws Exception {
        String user = "sr_reader_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sql.execute("CREATE USER '" + user + "'");
        try {
            sql.execute("GRANT SELECT ON TABLE " + schema + ".sample TO USER '" + user + "'");
            try (var reader = DriverManager.getConnection(System.getProperty("datahub.test.starrocks.url"), user, "");
                 var query = reader.createStatement()) {
                try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + schema + ".sample")) { assertTrue(rows.next()); }
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.STARROCKS);
                assertThrows(SQLException.class, () -> database.queryRelationFingerprint(reader, schema + ".sample"));
                sql.execute("GRANT OPERATE ON SYSTEM TO USER '" + user + "'");
                String direct = database.queryRelationFingerprint(reader, schema + ".sample");
                assertNotEquals(identity("sample"), direct);
                sql.execute("GRANT db_admin TO USER '" + user + "'"); query.execute("SET ROLE db_admin");
                assertNotEquals(direct, database.queryRelationFingerprint(reader, schema + ".sample"));
                sql.execute("REVOKE OPERATE ON SYSTEM FROM USER '" + user + "'");
                query.execute("SET ROLE NONE");
                assertThrows(SQLException.class, () -> database.queryRelationFingerprint(reader, schema + ".sample"));
            }
        } finally { sql.execute("DROP USER '" + user + "'"); }
    }
}
