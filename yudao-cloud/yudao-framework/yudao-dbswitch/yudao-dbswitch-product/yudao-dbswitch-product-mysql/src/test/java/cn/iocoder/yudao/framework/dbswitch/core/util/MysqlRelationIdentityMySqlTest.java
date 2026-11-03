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
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class MysqlRelationIdentityMySqlTest {
    private Connection connection;
    private Statement sql;
    private String schema;

    @BeforeEach
    void createOwnedSchema() throws Exception {
        schema = "relation_identity_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), "");
        try (var setup = connection.createStatement()) {
            setup.execute("CREATE DATABASE " + schema);
        }
        connection.setCatalog(schema);
        sql = connection.createStatement();
        sql.execute("CREATE TABLE sample(id bigint) ENGINE=InnoDB");
    }

    @AfterEach
    void dropOnlyOwnedSchema() throws Exception {
        if (connection == null) return;
        try (var owned = connection) {
            if (sql != null) sql.close();
            if (!owned.getAutoCommit()) owned.rollback();
            owned.setAutoCommit(true);
            owned.setCatalog("mysql");
            try (var cleanup = owned.createStatement()) {
                if (schema != null) cleanup.execute("DROP DATABASE IF EXISTS " + schema);
            }
        }
    }

    @Test
    void factoryResolvesSameObjectAndDetectsDropRecreateAndTruncate() throws Exception {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MYSQL);
        String before = database.queryRelationFingerprint(connection, "sample");
        assertTrue(before.matches("[a-f0-9]{64}"));
        assertEquals(before, database.queryRelationFingerprint(connection, "`" + schema + "`.`sample`"));
        sql.execute("DROP TABLE sample");
        sql.execute("CREATE TABLE sample(id bigint) ENGINE=InnoDB");
        String recreated = database.queryRelationFingerprint(connection, "sample");
        assertNotEquals(before, recreated);
        sql.execute("TRUNCATE TABLE sample");
        assertNotEquals(recreated, database.queryRelationFingerprint(connection, "sample"));
    }

    @Test
    void respectsActualCatalogAndServerCaseRules() throws Exception {
        String before = MysqlRelationIdentity.fingerprint(connection, "sample");
        try (var rows = sql.executeQuery("SELECT @@lower_case_table_names")) {
            assertTrue(rows.next());
            if (rows.getInt(1) != 0) {
                assertEquals(before, MysqlRelationIdentity.fingerprint(connection,
                        schema.toUpperCase(Locale.ROOT) + ".SAMPLE"));
            }
        }
        String other = schema + "_b";
        sql.execute("CREATE DATABASE " + other);
        try {
            sql.execute("CREATE TABLE " + other + ".sample(id bigint) ENGINE=InnoDB");
            connection.setCatalog(other);
            assertNotEquals(before, MysqlRelationIdentity.fingerprint(connection, "sample"));
            assertEquals(before, MysqlRelationIdentity.fingerprint(connection, schema + ".sample"));
        } finally {
            connection.setCatalog(schema);
            sql.execute("DROP DATABASE " + other);
        }
    }

    @Test
    void rejectsTemporaryShadowViewsPartitionsOtherEnginesAndEncodedNames() throws Exception {
        sql.execute("CREATE TEMPORARY TABLE sample(id bigint) ENGINE=InnoDB");
        assertUnsupported("sample");
        assertUnsupported(schema + ".sample");
        sql.execute("DROP TEMPORARY TABLE sample");
        assertNotNull(MysqlRelationIdentity.fingerprint(connection, "sample"));
        sql.execute("CREATE VIEW sample_view AS SELECT * FROM sample");
        assertUnsupported("sample_view");
        sql.execute("CREATE TABLE sample_partition(id bigint) ENGINE=InnoDB PARTITION BY HASH(id) PARTITIONS 2");
        assertUnsupported("sample_partition");
        sql.execute("CREATE TABLE sample_myisam(id bigint) ENGINE=MyISAM");
        assertUnsupported("sample_myisam");
        sql.execute("CREATE TABLE `a.b`(id bigint) ENGINE=InnoDB");
        assertUnsupported("`a.b`");
        assertThrows(SQLException.class, () -> MysqlRelationIdentity.fingerprint(connection, "missing_table"));
    }

    @Test
    void doesNotCloseConnectionChangeSessionOrCommitCallerWork() throws Exception {
        connection.setAutoCommit(false);
        int isolation = connection.getTransactionIsolation();
        boolean readOnly = connection.isReadOnly();
        sql.execute("INSERT INTO sample VALUES(1)");
        assertNotNull(MysqlRelationIdentity.fingerprint(connection, "sample"));
        assertFalse(connection.isClosed());
        assertFalse(connection.getAutoCommit());
        assertEquals(schema, connection.getCatalog());
        assertEquals(isolation, connection.getTransactionIsolation());
        assertEquals(readOnly, connection.isReadOnly());
        connection.rollback();
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM sample")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void readOnlyAccountNeedsExplicitIdentityPrivilegeAndRevocationIsObserved() throws Exception {
        String user = "ri_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String peer;
        try (var rows = sql.executeQuery("SELECT SUBSTRING_INDEX(USER(),'@',-1)")) {
            assertTrue(rows.next()); peer = rows.getString(1);
        }
        // Docker bridge clients do not appear as 127.0.0.1 inside the server; do not grant to a wildcard host.
        assertTrue(peer.matches("[A-Za-z0-9:._-]+"));
        String account = "'" + user + "'@'" + peer + "'";
        // 该账号只获授本次随机数据库的权限，连接握手也必须选择该数据库。
        var address = java.net.URI.create(System.getProperty("datahub.test.mysql.url").substring("jdbc:".length()));
        String readerUrl = "jdbc:" + new java.net.URI(address.getScheme(), null, address.getHost(), address.getPort(),
                "/" + schema, address.getQuery(), null);
        sql.execute("CREATE USER " + account);
        try {
            sql.execute("GRANT SELECT(id) ON " + schema + ".sample TO " + account);
            try (var reader = DriverManager.getConnection(readerUrl, user, "")) {
                reader.setCatalog(schema);
                try (var query = reader.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM sample")) {
                    assertTrue(rows.next());
                }
                assertThrows(SQLFeatureNotSupportedException.class, () -> MysqlRelationIdentity.fingerprint(reader, "sample"));
            }
            sql.execute("GRANT SELECT ON " + schema + ".sample TO " + account);
            try (var reader = DriverManager.getConnection(readerUrl, user, "")) {
                reader.setCatalog(schema);
                assertThrows(SQLFeatureNotSupportedException.class, () -> MysqlRelationIdentity.fingerprint(reader, "sample"));
            }
            // Grants apply only to this ephemeral account in the explicitly supplied test instance.
            sql.execute("GRANT PROCESS ON *.* TO " + account);
            try (var reader = DriverManager.getConnection(readerUrl, user, "")) {
                reader.setCatalog(schema);
                assertNotNull(MysqlRelationIdentity.fingerprint(reader, "sample"));
            }
            sql.execute("REVOKE PROCESS ON *.* FROM " + account);
            try (var reader = DriverManager.getConnection(readerUrl, user, "")) {
                reader.setCatalog(schema);
                assertThrows(SQLFeatureNotSupportedException.class, () -> MysqlRelationIdentity.fingerprint(reader, "sample"));
            }
        } finally {
            sql.execute("DROP USER " + account);
        }
    }

    private void assertUnsupported(String reference) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> MysqlRelationIdentity.fingerprint(connection, reference));
    }
}
