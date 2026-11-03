package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Native generations alone do not prove an instance incarnation; keep the unproven path fail-closed. */
@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/")
class MariaDbRelationGenerationMariaDbTest {
    @Test void nativeTruncateChangesLocalIdsButDoesNotMakeServerUidAnInstanceGeneration() throws Exception {
        String schema = "maria_generation_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,val VARCHAR(40)) ENGINE=InnoDB");
                String uid = scalar(sql, "SELECT @@server_uid");
                String before = generation(connection, schema);
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,'before')"); assertEquals(before, generation(connection, schema));
                sql.execute("TRUNCATE TABLE " + schema + ".sample"); assertNotEquals(before, generation(connection, schema));
                assertEquals(uid, scalar(sql, "SELECT @@server_uid"));
                assertThrows(SQLFeatureNotSupportedException.class, () -> cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB)
                        .queryRelationFingerprint(connection, schema + ".sample"));
                assertTrue(connection.getAutoCommit()); assertFalse(connection.isClosed());
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
    private String generation(Connection connection, String schema) throws SQLException {
        try (var query = connection.prepareStatement("SELECT TABLE_ID,SPACE FROM information_schema.INNODB_SYS_TABLES WHERE NAME=?")) {
            query.setString(1, schema + "/sample"); query.setQueryTimeout(5); query.setMaxRows(2);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); String id = rows.getString(1), space = rows.getString(2);
                assertTrue(id.matches("[1-9][0-9]*")); assertTrue(space.matches("[1-9][0-9]*"));
                assertFalse(rows.next()); return id + "/" + space;
            }
        }
    }
    private String scalar(Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) { assertTrue(rows.next()); return rows.getString(1); }
    }
}
