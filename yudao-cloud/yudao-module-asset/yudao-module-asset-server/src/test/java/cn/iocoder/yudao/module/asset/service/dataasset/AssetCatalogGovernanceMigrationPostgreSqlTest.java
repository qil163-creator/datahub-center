package cn.iocoder.yudao.module.asset.service.dataasset;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AssetCatalogGovernanceMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void catalogGovernanceMigrationReplaysAndKeepsOldAssetId() throws Exception {
        Path root = repoRoot();
        String catalog = Files.readString(root.resolve("sql/postgresql/migrations/0027_asset_catalog-governance.sql"));
        String lease = Files.readString(root.resolve("sql/postgresql/migrations/0031_asset_delivery-lease-acceptance.sql"));
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA \"datahub-center\"");
            sql.execute("SET search_path TO \"datahub-center\", public");
            createPrerequisites(sql);
            sql.execute(catalog);
            sql.execute(catalog);
            assertEquals("1", one(sql, "SELECT count(*) FROM dh_asset_tech_link WHERE asset_id=11"));
            assertEquals("0", one(sql, "SELECT share_attr FROM dh_asset_data_asset WHERE id=11"));
            assertEquals("11", one(sql, "SELECT id FROM dh_asset_data_asset WHERE id=11"));
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS dh_market_delivery_operation (
                        id bigint PRIMARY KEY,
                        operation_id varchar(64) NOT NULL,
                        apply_id bigint NOT NULL,
                        source_grant_id varchar(64) NOT NULL,
                        op_type varchar(16) NOT NULL,
                        status varchar(16) NOT NULL,
                        deleted int2 NOT NULL DEFAULT 0,
                        tenant_id bigint NOT NULL DEFAULT 1
                    )
                    """);
            sql.execute(lease);
            sql.execute(lease);
            assertTrue(columnExists(sql, "dh_market_delivery_operation", "lease_until"));
        }
    }

    private static void createPrerequisites(Statement sql) throws SQLException {
        sql.execute("""
                CREATE TABLE dh_asset_data_asset (
                    id bigint PRIMARY KEY,
                    asset_name varchar(256),
                    status int2 NOT NULL DEFAULT 0,
                    meta_table_id bigint NOT NULL DEFAULT 1,
                    resource_urn varchar(512),
                    datasource_id bigint,
                    schema_name varchar(128),
                    table_name varchar(256),
                    published_snapshot_id bigint,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1,
                    creator varchar(64) DEFAULT '',
                    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updater varchar(64) DEFAULT '',
                    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        sql.execute("INSERT INTO dh_asset_data_asset(id, asset_name, status, meta_table_id, datasource_id, table_name) "
                + "VALUES (11, '旧资产', 1, 88, 3, 'citizen')");
        sql.execute("""
                CREATE TABLE dh_market_resource_apply (
                    id bigint PRIMARY KEY,
                    apply_no varchar(32) NOT NULL DEFAULT 'A',
                    title varchar(128) NOT NULL DEFAULT 't',
                    asset_id bigint NOT NULL DEFAULT 1,
                    apply_user_id bigint NOT NULL DEFAULT 1,
                    status int2 NOT NULL DEFAULT 1,
                    if_auth int2 NOT NULL DEFAULT 0,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
        sql.execute("""
                CREATE TABLE dh_asset_quality_task (
                    id bigint PRIMARY KEY,
                    asset_id bigint NOT NULL,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
        sql.execute("""
                CREATE TABLE dh_asset_quality_task_rule (
                    id bigint PRIMARY KEY,
                    task_id bigint NOT NULL,
                    deleted int2 NOT NULL DEFAULT 0,
                    tenant_id bigint NOT NULL DEFAULT 1
                )
                """);
    }

    private static boolean columnExists(Statement sql, String table, String column) throws SQLException {
        try (ResultSet rs = sql.executeQuery("""
                SELECT 1 FROM information_schema.columns
                WHERE table_schema='datahub-center' AND table_name='%s' AND column_name='%s'
                """.formatted(table, column))) {
            return rs.next();
        }
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            assertTrue(rs.next());
            return String.valueOf(rs.getObject(1));
        }
    }

    private static Path repoRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("sql/postgresql/migrations"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException("cannot locate backend root");
        }
        return path;
    }

}
