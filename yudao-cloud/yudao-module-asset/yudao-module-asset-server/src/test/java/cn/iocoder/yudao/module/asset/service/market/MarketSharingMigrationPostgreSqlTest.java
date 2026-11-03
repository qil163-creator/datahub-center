package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MarketSharingMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void marketMigrationsReplayAndKeepShareAttributePending() throws Exception {
        Path root = repoRoot();
        String guards = Files.readString(root.resolve("sql/postgresql/migrations/0023_asset_market-sharing-guards.sql"));
        String lifecycle = Files.readString(root.resolve("sql/postgresql/migrations/0030_asset_market-sharing-lifecycle.sql"));
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA \"datahub-center\"");
            sql.execute("SET search_path TO \"datahub-center\", public");
            createPrerequisites(sql);
            sql.execute(guards);
            sql.execute(lifecycle);
            assertEquals("0", columnDefault(sql, "dh_asset_data_asset", "share_attribute"));
            sql.execute("""
                    INSERT INTO dh_asset_data_asset(id, asset_name, status, tenant_id)
                    VALUES (1, '历史已发布', 1, 1)
                    """);
            assertEquals("0", one(sql, "SELECT share_attribute FROM dh_asset_data_asset WHERE id=1"));
            sql.execute("""
                    INSERT INTO dh_market_demand_match(id, demand_id, asset_id, tenant_id)
                    VALUES (1, 10, 1, 1)
                    """);
            assertThrows(SQLException.class, () -> sql.execute("""
                    INSERT INTO dh_market_demand_match(id, demand_id, asset_id, tenant_id)
                    VALUES (2, 10, 1, 1)
                    """));
            sql.execute(guards);
            sql.execute(lifecycle);
            assertEquals("0", one(sql, "SELECT count(*) FROM dh_market_delivery_operation"));
            assertTrue(indexExists(sql, "uk_dh_market_delivery_operation_id"));
        }
    }

    private static void createPrerequisites(Statement sql) throws SQLException {
        sql.execute("""
                CREATE TABLE dh_asset_data_asset (
                    id bigint PRIMARY KEY,
                    asset_name varchar(256),
                    status int2 NOT NULL DEFAULT 0,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
        sql.execute("""
                CREATE TABLE dh_market_resource_apply (
                    id bigint PRIMARY KEY,
                    apply_no varchar(32) NOT NULL,
                    title varchar(128) NOT NULL,
                    asset_id bigint NOT NULL,
                    apply_user_id bigint NOT NULL,
                    status int2 NOT NULL DEFAULT 1,
                    if_auth int2 NOT NULL DEFAULT 0,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
        sql.execute("""
                CREATE TABLE dh_market_demand (
                    id bigint PRIMARY KEY,
                    demand_no varchar(32) NOT NULL,
                    title varchar(128) NOT NULL,
                    description varchar(2000) NOT NULL DEFAULT '',
                    status int2 NOT NULL DEFAULT 0,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
        sql.execute("""
                CREATE TABLE dh_market_resource_comment (
                    id bigint PRIMARY KEY,
                    asset_id bigint NOT NULL,
                    comment_str varchar(500) NOT NULL,
                    level int2 NOT NULL DEFAULT 5,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
        sql.execute("""
                CREATE TABLE dh_market_demand_match (
                    id bigint PRIMARY KEY,
                    demand_id bigint NOT NULL,
                    asset_id bigint NOT NULL,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
    }

    private static String columnDefault(Statement sql, String table, String column) throws SQLException {
        try (ResultSet rs = sql.executeQuery("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema='datahub-center' AND table_name='%s' AND column_name='%s'
                """.formatted(table, column))) {
            assertTrue(rs.next());
            return rs.getString(1).contains("0") ? "0" : rs.getString(1);
        }
    }

    private static boolean indexExists(Statement sql, String index) throws SQLException {
        try (ResultSet rs = sql.executeQuery("SELECT to_regclass('datahub-center." + index + "')")) {
            assertTrue(rs.next());
            return rs.getString(1) != null;
        }
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations/0030_asset_market-sharing-lifecycle.sql"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("cannot locate yudao-cloud root");
        }
        return root;
    }

}
