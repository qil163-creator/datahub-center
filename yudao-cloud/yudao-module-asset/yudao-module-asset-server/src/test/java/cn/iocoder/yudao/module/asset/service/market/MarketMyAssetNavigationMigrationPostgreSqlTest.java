package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 在隔离 PostgreSQL 中验证手动平铺导航后的前向修复及撤权保持，不改写已交付迁移。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class MarketMyAssetNavigationMigrationPostgreSqlTest {
    private static final String ORIGINAL = "0183_asset_my-assets.sql";
    private static final String REPAIR = "0184_asset_my-assets-root-menu.sql";
    private String controlUrl;
    private String database;
    private Connection connection;
    private Path root;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres");
        database = "asset_my_nav_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var statement = control.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        connection = DriverManager.getConnection(controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database,
                "catalog_test", "");
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertThat(root).isNotNull();
    }

    @BeforeEach
    void prepareNestedFixture() throws Exception {
        sql("ROLLBACK");
        sql("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\",public");
        sql("""
                CREATE SEQUENCE system_menu_seq START 1000;
                CREATE SEQUENCE system_menu_id_seq START 1000;
                CREATE SEQUENCE system_role_menu_seq START 1000;
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY DEFAULT nextval('system_menu_id_seq'),name text,permission text DEFAULT '',
                    type smallint,sort integer DEFAULT 0,parent_id bigint,path text,icon text,component text,component_name text,
                    status smallint DEFAULT 0,visible boolean DEFAULT true,keep_alive boolean DEFAULT false,
                    always_show boolean DEFAULT true,deleted smallint DEFAULT 0,creator text DEFAULT 'original',
                    updater text DEFAULT 'unchanged',create_time timestamp DEFAULT timestamp '2000-01-01',
                    update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE TABLE system_role_menu (
                    id bigint PRIMARY KEY,role_id bigint,menu_id bigint,tenant_id bigint DEFAULT 1,
                    creator text DEFAULT 'original',updater text DEFAULT 'original',deleted smallint DEFAULT 0,
                    create_time timestamp DEFAULT timestamp '2000-01-01',update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE TABLE system_tenant_package (
                    id bigint PRIMARY KEY,menu_ids varchar(4096),deleted smallint DEFAULT 0,status smallint DEFAULT 0,
                    updater text DEFAULT 'unchanged',update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE TABLE dh_market_resource_apply (
                    id bigint PRIMARY KEY,apply_user_id bigint,status integer,deleted smallint DEFAULT 0,
                    create_time timestamp DEFAULT timestamp '2000-01-01'
                );
                INSERT INTO system_menu(id,name,type,sort,parent_id,path,component,component_name,permission) VALUES
                    (100,'数据资产',1,10,0,'asset',NULL,NULL,''),
                    (200,'原数据集市目录',1,30,100,'market',NULL,NULL,''),
                    (21,'数据集市',2,4,200,'mart','asset/market/index','AssetMarketMart',''),
                    (22,'现有同级页面',2,47,200,'existing','custom/index','ExistingPage','custom:query'),
                    (23,'根下现有页面',2,91,100,'custom','custom/root','CustomRoot','custom:query'),
                    (300,'其他业务',1,20,0,'other',NULL,NULL,''),
                    (40,'集市查询',3,0,21,NULL,NULL,NULL,'asset:market:query'),
                    (41,'集市管理',3,1,21,NULL,NULL,NULL,'asset:market:publish');
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
                    (1,10,40,1,0),(2,10,40,2,0),(3,20,41,1,0),(4,30,40,1,1),(5,40,21,1,0);
                INSERT INTO system_tenant_package(id,menu_ids,deleted,status) VALUES
                    (1,'[40,"200","999"]',0,0),(2,'[41,21]',0,0),(3,'[40]',1,1),(4,'[]',0,0);
                """);
    }

    @Test
    void originalFailureOnFlattenedCanonicalPageIsRepairedWithoutMovingExistingMenus() throws Exception {
        sql("UPDATE system_menu SET parent_id=100,permission='asset:market:query' WHERE id=21");
        String before = snapshot();
        assertThatThrownBy(() -> migrate(ORIGINAL)).isInstanceOf(SQLException.class)
                .hasMessageContaining("缺少正式数据集市页面");
        sql("ROLLBACK");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='idx_market_my_approved_assets'")).isZero();
        String oldMenus = originalMenus();

        migrate(REPAIR);

        long mine = myAssetId();
        assertThat(count("SELECT parent_id FROM system_menu WHERE id=" + mine)).isEqualTo(100);
        assertThat(count("SELECT sort FROM system_menu WHERE id=" + mine)).isGreaterThan(91);
        assertThat(originalMenus()).isEqualTo(oldMenus);
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='idx_market_my_approved_assets'")).isEqualTo(1);
    }

    @Test
    void nestedOrCustomizedMarketDoesNotChangeDirectAssetRootPlacement() throws Exception {
        sql("UPDATE system_menu SET component='custom/current-market',component_name='CustomMarket' WHERE id=21;"
                + "DELETE FROM system_menu WHERE id=40");
        sql("SELECT setval('system_menu_seq',5000); SELECT setval('system_menu_id_seq',6000)");
        String oldMenus = originalMenus();
        migrate(REPAIR);
        long mine = myAssetId();
        assertThat(count("SELECT parent_id FROM system_menu WHERE id=" + mine)).isEqualTo(100);
        assertThat(count("SELECT sort FROM system_menu WHERE id=" + mine)).isGreaterThan(91);
        assertThat(originalMenus()).isEqualTo(oldMenus);
        assertThat(text("SELECT component_name||'|'||permission FROM system_menu WHERE id=" + mine))
                .isEqualTo("AssetMarketMyAssets|asset:market:my-assets:query");
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=" + mine)).isZero();
        assertThat(mine).isEqualTo(6001);
        assertThat(count("SELECT last_value FROM system_menu_seq")).isEqualTo(mine);
        assertThat(count("SELECT last_value FROM system_menu_id_seq")).isEqualTo(mine);
    }

    @Test
    void initializationCopiesOnlyLiveQueryEntitlementsAndPreservesPackageEntries() throws Exception {
        sql("""
                INSERT INTO system_menu(id,type,parent_id,path,permission,status,deleted) VALUES
                    (45,1,100,'not-query-page','asset:market:query',0,0),
                    (46,3,21,NULL,'asset:market:query',1,0),(47,3,21,NULL,'asset:market:query',0,1);
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(6,60,45,1),(7,70,46,1),(8,80,47,1);
                INSERT INTO system_tenant_package(id,menu_ids) VALUES(6,'[45]'),(7,'[46]'),(8,'[47]');
                """);
        migrate(REPAIR);
        long mine = myAssetId();
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=10 AND menu_id=" + mine + " AND deleted=0")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id IN(20,30,40,60,70,80) AND menu_id=" + mine + " AND deleted=0")).isZero();
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=10 AND menu_id=100 AND deleted=0")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=10 AND menu_id=200 AND deleted=0")).isZero();
        assertThat(text("SELECT menu_ids::jsonb @> '[40,\"200\",\"999\"]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(text("SELECT menu_ids::jsonb @> '[" + mine + "]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(text("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=2")).isEqualTo("[41,21]|unchanged");
        assertThat(text("SELECT menu_ids||'|'||deleted||'|'||status FROM system_tenant_package WHERE id=3")).isEqualTo("[40]|1|1");
        assertThat(text("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=4")).isEqualTo("[]|unchanged");
        assertThat(count("SELECT count(*) FROM system_tenant_package WHERE id IN(6,7,8) AND updater='unchanged'")).isEqualTo(3);
    }

    @Test
    void replayDoesNotRestoreRevokedGrantsOrExpandNewQueryRolesAndPackages() throws Exception {
        migrate(REPAIR);
        long mine = myAssetId();
        sql("UPDATE system_role_menu SET deleted=1 WHERE menu_id=" + mine
                + "; UPDATE system_tenant_package SET menu_ids='[40]' WHERE id=1"
                + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(9000,50,40,1)"
                + "; INSERT INTO system_tenant_package(id,menu_ids) VALUES(5,'[40]')");
        String revoked = snapshot();

        migrate(REPAIR);

        assertThat(snapshot()).isEqualTo(revoked);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=" + mine + " AND deleted=0")).isZero();
    }

    @Test
    void replayPreservesDisabledDeletedRenamedAndManuallyMovedMyAssetPage() throws Exception {
        for (String adjustment : List.of("status=1", "deleted=1", "name='我的常用数据',sort=2",
                "parent_id=200,sort=7")) {
            prepareNestedFixture();
            migrate(REPAIR);
            sql("UPDATE system_menu SET " + adjustment + " WHERE id=" + myAssetId());
            String adjusted = snapshot();

            migrate(REPAIR);

            assertThat(snapshot()).as(adjustment).isEqualTo(adjusted);
        }
    }

    @Test
    void ambiguousRootOrUnsafeExistingMyAssetAncestorsRollBackAllChanges() throws Exception {
        for (String change : List.of(
                "DELETE FROM system_menu WHERE id=100",
                "UPDATE system_menu SET type=2 WHERE id=100",
                "UPDATE system_menu SET permission='custom:guard' WHERE id=100",
                "INSERT INTO system_menu(id,name,type,parent_id,path,permission) VALUES(90,'重复资产根',1,0,'asset','')"
        )) {
            prepareNestedFixture();
            sql(change);
            assertRepairRejectedWithoutDataChanges(change);
        }
        for (String change : List.of("UPDATE system_menu SET parent_id=300 WHERE component='asset/market/my-assets/index'",
                "UPDATE system_menu SET parent_id=200 WHERE component='asset/market/my-assets/index';"
                        + "UPDATE system_menu SET parent_id=(SELECT id FROM system_menu WHERE component='asset/market/my-assets/index') WHERE id=200",
                "UPDATE system_menu SET parent_id=200 WHERE component='asset/market/my-assets/index';"
                        + "UPDATE system_menu SET permission='custom:guard' WHERE id=200")) {
            prepareNestedFixture();
            migrate(REPAIR);
            sql(change);
            assertRepairRejectedWithoutDataChanges(change);
        }
    }

    @Test
    void alreadySuccessfulOriginalMigrationDoesNotReceiveAdditionalAssignments() throws Exception {
        sql("UPDATE system_menu SET permission='asset:market:query' WHERE id=21");
        migrate(ORIGINAL);
        long mine = myAssetId();
        sql("UPDATE system_role_menu SET deleted=1 WHERE menu_id=" + mine
                + "; UPDATE system_tenant_package SET menu_ids='[40]' WHERE id=1"
                + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(9000,50,40,1)");
        // 0183只推进了非默认菜单序列，修复必须同时抬高默认序列且不回退另一条更高序列。
        sql("SELECT setval('system_menu_seq',9000); SELECT setval('system_menu_id_seq',1)");
        String established = snapshot();

        migrate(REPAIR);

        assertThat(snapshot()).isEqualTo(established);
        assertThat(count("SELECT last_value FROM system_menu_seq")).isEqualTo(9000);
        assertThat(count("SELECT last_value FROM system_menu_id_seq")).isEqualTo(9000);
        sql("SELECT setval('system_menu_seq',1); SELECT setval('system_menu_id_seq',10000)");
        migrate(REPAIR);
        assertThat(snapshot()).isEqualTo(established);
        assertThat(count("SELECT last_value FROM system_menu_seq")).isEqualTo(10000);
        assertThat(count("SELECT last_value FROM system_menu_id_seq")).isEqualTo(10000);
    }

    @Test
    void packageAndPathOrPermissionConflictsCannotLeavePartialNavigationOrGrants() throws Exception {
        for (String change : List.of(
                "UPDATE system_tenant_package SET menu_ids='{}' WHERE id=1",
                "INSERT INTO system_menu(id,type,parent_id,path,component,component_name,permission) VALUES(90,2,100,'my-assets','custom/conflict','ConflictPage','custom:query')",
                "INSERT INTO system_menu(id,type,parent_id,path,component,component_name,permission) VALUES(90,3,21,NULL,NULL,NULL,'asset:market:my-assets:query')"
        )) {
            prepareNestedFixture();
            sql(change);
            assertRepairRejectedWithoutDataChanges(change);
        }
    }

    private void assertRepairRejectedWithoutDataChanges(String scenario) throws Exception {
        String before = snapshot();
        long previousIndexes = count("SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='idx_market_my_approved_assets'");
        assertThatThrownBy(() -> migrate(REPAIR)).as(scenario).isInstanceOf(SQLException.class);
        sql("ROLLBACK");
        assertThat(snapshot()).as(scenario).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='idx_market_my_approved_assets'")).isEqualTo(previousIndexes);
    }

    private long myAssetId() throws Exception {
        return count("SELECT id FROM system_menu WHERE component='asset/market/my-assets/index'");
    }

    private String originalMenus() throws Exception {
        return text("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(100,200,21,22,23,300,40,41)");
    }

    private String snapshot() throws Exception {
        return text("""
                SELECT jsonb_build_object(
                  'menus',(SELECT jsonb_agg(to_jsonb(menu) ORDER BY id) FROM system_menu menu),
                  'roles',(SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id) FROM system_role_menu grant_row),
                  'packages',(SELECT jsonb_agg(to_jsonb(package) ORDER BY id) FROM system_tenant_package package)
                )::text
                """);
    }

    private void migrate(String filename) throws Exception {
        sql(Files.readString(root.resolve("sql/postgresql/migrations").resolve(filename)));
    }

    private void sql(String value) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(value); }
    }

    private String text(String query) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            assertThat(result.next()).isTrue(); return result.getString(1);
        }
    }

    private long count(String query) throws Exception { return Long.parseLong(text(query)); }

    @AfterAll
    void dropOwnedDatabase() throws Exception {
        if (connection != null) { sql("ROLLBACK"); connection.close(); }
        if (database != null && database.matches("asset_my_nav_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}
