package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgresRelationIdentityPostgreSqlTest {
    @Test
    void resolvesActualSearchPathAndDetectsReplacementWithoutPersistingCredentials() throws Exception {
        String schema = "relation_identity_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
             var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("SET search_path TO " + schema);
                sql.execute("CREATE TABLE sample(id bigint)");
                String first = PostgresRelationIdentity.fingerprint(connection, "sample");
                assertEquals(first, cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL)
                        .queryRelationFingerprint(connection, "sample"));
                assertEquals(first, PostgresRelationIdentity.fingerprint(connection, schema + ".sample"));
                sql.execute("DROP TABLE sample");
                sql.execute("CREATE TABLE sample(id bigint)");
                assertNotEquals(first, PostgresRelationIdentity.fingerprint(connection, "sample"));
                sql.execute("CREATE TABLE \"a.b\"(id bigint)");
                assertEquals(PostgresRelationIdentity.fingerprint(connection, "\"a.b\""),
                        PostgresRelationIdentity.fingerprint(connection, schema + ".\"a.b\""));
                sql.execute("CREATE VIEW sample_view AS SELECT id FROM sample");
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> PostgresRelationIdentity.fingerprint(connection, "sample_view"));
                sql.execute("ALTER TABLE sample ENABLE ROW LEVEL SECURITY");
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> PostgresRelationIdentity.fingerprint(connection, "sample"));
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
