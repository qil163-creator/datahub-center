package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class ClickhouseRelationIdentityClickhouseTest {
    @ParameterizedTest @ValueSource(strings = {"sample", "*"})
    void rowPolicyCannotMasqueradeAsUnfilteredIdentity(String target) throws Exception {
        String schema = "clickhouse_policy_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + schema + " ENGINE=Atomic");
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id Int64) ENGINE=MergeTree ORDER BY id");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE);
                String before = database.queryRelationFingerprint(connection, schema + ".sample");
                sql.execute("CREATE ROW POLICY filtered ON " + schema + "." + target + " FOR SELECT USING id>0 TO default");
                try {
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> database.queryRelationFingerprint(connection, schema + ".sample"));
                } finally { sql.execute("DROP ROW POLICY filtered ON " + schema + "." + target); }
                assertEquals(before, database.queryRelationFingerprint(connection, schema + ".sample"));
            } finally { sql.execute("DROP DATABASE " + schema + " SYNC"); }
        }
    }

    @Test void readableTableDoesNotProveThatRowPolicyMetadataIsVisible() throws Exception {
        String name = "clickhouse_reader_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + name + " ENGINE=Atomic");
            try {
                sql.execute("CREATE TABLE " + name + ".sample(id Int64) ENGINE=MergeTree ORDER BY id");
                sql.execute("INSERT INTO " + name + ".sample VALUES(1)");
                sql.execute("CREATE USER " + name + " IDENTIFIED WITH no_password");
                try {
                    sql.execute("GRANT SELECT ON " + name + ".* TO " + name);
                    try (var reader = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), name, "");
                         var read = reader.createStatement()) {
                        try (var rows = read.executeQuery("SELECT count() FROM " + name + ".sample")) {
                            assertTrue(rows.next()); assertEquals(1, rows.getLong(1));
                        }
                        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE);
                        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(reader, name + ".sample"));
                        sql.execute("GRANT SHOW ROW POLICIES ON *.* TO " + name);
                        assertTrue(database.queryRelationFingerprint(reader, name + ".sample").matches("[a-f0-9]{64}"));
                    }
                } finally { sql.execute("DROP USER " + name); }
            } finally { sql.execute("DROP DATABASE " + name + " SYNC"); }
        }
    }

    @Test void nativeAtomicRelationSurvivesWritesButNotRecreation() throws Exception {
        String schema = "clickhouse_identity_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + schema + " ENGINE=Atomic");
            try {
                String table = schema + ".sample";
                sql.execute("CREATE TABLE " + table + "(id Int64, amount Nullable(Int64)) ENGINE=MergeTree ORDER BY id");
                var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE);
                String before = database.queryRelationFingerprint(connection, table);
                assertTrue(before.matches("[a-f0-9]{64}"));
                sql.execute("INSERT INTO " + table + " VALUES(1,NULL)");
                assertEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("DROP TABLE " + table + " SYNC");
                sql.execute("CREATE TABLE " + table + "(id Int64, amount Nullable(Int64)) ENGINE=MergeTree ORDER BY id");
                assertNotEquals(before, database.queryRelationFingerprint(connection, table));
                sql.execute("CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + table);
                assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, schema + ".sample_view"));
            } finally { sql.execute("DROP DATABASE " + schema + " SYNC"); }
        }
    }
}
