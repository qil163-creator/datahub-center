package cn.iocoder.yudao.module.asset.service.term;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅允许独立容器或显式配置的本机 codex_term_* 临时数据库。 */
class AssetTermGovernanceMigrationPostgreSqlTest {

    private static PostgreSQLContainer<?> postgres;
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void startDatabase() {
        url = System.getenv("ASSET_TERM_MIGRATION_TEST_JDBC_URL");
        if (url != null && !url.isBlank()) {
            assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/codex_term_[a-z0-9_]+"),
                    "只允许本机专用临时数据库，不能配置业务数据库");
            user = System.getenv().getOrDefault("ASSET_TERM_MIGRATION_TEST_JDBC_USER", "codex_term");
            password = System.getenv().getOrDefault("ASSET_TERM_MIGRATION_TEST_JDBC_PASSWORD", "");
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "需要 Docker 或显式配置的独立 PostgreSQL");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        url = postgres.getJdbcUrl();
        user = postgres.getUsername();
        password = postgres.getPassword();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createUnusedFeature() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
            sql.execute(Files.readString(repo().resolve("yudao-module-asset/yudao-module-asset-server/"
                    + "src/test/resources/sql/asset-term-governance-migration.sql")));
        }
    }

    @Test
    void emptyUpgradeRetiresOnlyLegacyObjectsAndReplaysWithoutErasingNewBindings() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute(migration());
            assertEquals("true", one(sql, "SELECT to_regclass('dh_asset_term') IS NULL AND to_regclass('dh_asset_category_term') IS NULL"));
            assertEquals("true", one(sql, "SELECT to_regclass('dh_asset_term_seq') IS NULL AND to_regclass('dh_asset_category_term_seq') IS NULL"));
            assertEquals("NO", one(sql, "SELECT is_nullable FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_asset_term_column_rel' AND column_name='standard_term_id'"));
            assertEquals("0", one(sql, "SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_asset_term_column_rel' AND column_name='term_id'"));
            assertEquals("2", one(sql, "SELECT count(*) FROM dh_governance_standard_term"));
            assertEquals("1,2,7", one(sql, "SELECT string_agg(id::text, ',' ORDER BY id) FROM system_menu"));
            assertEquals("1,2,7", one(sql, "SELECT string_agg(menu_id::text, ',' ORDER BY menu_id) FROM system_role_menu"));
            assertEquals("true", one(sql, "SELECT menu_ids::jsonb = '[1,\"2\",\"7\",1]'::jsonb FROM system_tenant_package WHERE id=1"));
            assertEquals("[]", one(sql, "SELECT menu_ids FROM system_tenant_package WHERE id=2"));
            assertEquals("original", one(sql, "SELECT updater FROM system_tenant_package WHERE id=3"));
            String firstUpdated = one(sql, "SELECT update_time FROM system_tenant_package WHERE id=1");
            sql.execute("INSERT INTO dh_asset_term_column_rel(standard_term_id,asset_id,meta_column_id) VALUES (11,101,201)");
            sql.execute(migration());
            assertEquals("11", one(sql, "SELECT standard_term_id FROM dh_asset_term_column_rel"));
            assertEquals(firstUpdated, one(sql, "SELECT update_time FROM system_tenant_package WHERE id=1"));
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO dh_asset_term_column_rel(standard_term_id,asset_id,meta_column_id) VALUES (12,101,201)"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dh_asset_term", "dh_asset_category_term", "dh_asset_term_column_rel"})
    void anyLegacyDataIncludingSoftDeletedRecordsRefusesUpgrade(String legacyTable) throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            if (legacyTable.equals("dh_asset_term_column_rel")) {
                sql.execute("INSERT INTO dh_asset_term_column_rel(term_id,asset_id,meta_column_id,deleted) VALUES (11,101,201,1)");
            } else {
                sql.execute("INSERT INTO " + legacyTable + "(id,deleted) VALUES (11,1)");
            }
            SQLException failure = assertThrows(SQLException.class, () -> sql.execute(migration()));
            assertTrue(failure.getMessage().contains("存在数据"));
            sql.execute("ROLLBACK");
            assertEquals("1", one(sql, "SELECT count(*) FROM " + legacyTable));
            assertEquals("7", one(sql, "SELECT count(*) FROM system_menu"));
            assertEquals("true", one(sql, "SELECT to_regclass('dh_asset_term') IS NOT NULL"));
            assertEquals("1", one(sql, "SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_asset_term_column_rel' AND column_name='term_id'"));
        }
    }

    @Test
    void unknownChildPermissionRollsBackAllSchemaChanges() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO system_menu VALUES (8,3,'asset:data-asset:update',3,NULL,NULL)");
            SQLException failure = assertThrows(SQLException.class, () -> sql.execute(migration()));
            assertTrue(failure.getMessage().contains("其他业务页面或权限"));
            sql.execute("ROLLBACK");
            assertEquals("true", one(sql, "SELECT to_regclass('dh_asset_term') IS NOT NULL"));
            assertEquals("8", one(sql, "SELECT count(*) FROM system_menu"));
            assertEquals("1", one(sql, "SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_asset_term_column_rel' AND column_name='term_id'"));
        }
    }

    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        try (Statement sql = connection.createStatement()) {
            sql.execute("SET search_path TO \"datahub-center\", pg_catalog");
        }
        return connection;
    }

    private static String migration() throws Exception {
        return Files.readString(repo().resolve("sql/postgresql/migrations/0157_asset_governance-terms.sql"));
    }

    private static Path repo() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("未找到仓库迁移目录");
        }
        return root;
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (var result = sql.executeQuery(query)) {
            assertTrue(result.next());
            return String.valueOf(result.getObject(1));
        }
    }
}
