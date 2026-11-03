package cn.iocoder.yudao.module.asset.service.dataasset;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只在显式本机控制连接下创建、验证并删除本测试拥有的数据库，不访问共享业务库。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class AssetManagementMenuRetirementPostgreSqlTest {

    private String controlUrl;
    private String ownedDatabase;
    private Connection connection;
    private Path root;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres");
        ownedDatabase = "asset_menu_retirement_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + ownedDatabase);
        }
        connection = DriverManager.getConnection(controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + ownedDatabase,
                "catalog_test", "");
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertThat(root).isNotNull();
    }

    @BeforeEach
    void prepareIsolatedPermissionFixture() throws Exception {
        sql("ROLLBACK");
        sql("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\", public");
        sql("""
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY,name text,permission text,type smallint,parent_id bigint,
                    path text,component text,component_name text,status smallint DEFAULT 0,
                    deleted smallint DEFAULT 0,updater text DEFAULT 'unchanged',
                    update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE TABLE system_role_menu (
                    id bigint PRIMARY KEY,role_id bigint,menu_id bigint,tenant_id bigint DEFAULT 1,
                    creator text DEFAULT 'original',updater text DEFAULT 'original',deleted smallint DEFAULT 0,
                    create_time timestamp DEFAULT timestamp '2000-01-01',update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE SEQUENCE system_role_menu_seq START 100;
                CREATE TABLE system_tenant_package (
                    id bigint PRIMARY KEY,menu_ids varchar(4096),deleted smallint DEFAULT 0,
                    updater text DEFAULT 'unchanged',update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE TABLE dh_asset_data_asset(id bigint PRIMARY KEY,asset_name text,status integer);
                CREATE TABLE dh_asset_catalog_link_operation(id bigint PRIMARY KEY,status text,reason text);
                INSERT INTO dh_asset_data_asset VALUES(1,'共享目录资产',1);
                INSERT INTO dh_asset_catalog_link_operation VALUES(1,'MANUAL','历史处置依据');
                INSERT INTO system_menu(id,name,type,parent_id,path,component,permission) VALUES
                    (100,'数据资产',1,0,'asset',NULL,''),
                    (200,'数据集市',1,100,'market',NULL,''),
                    (10,'旧资产管理',2,100,'dataasset','/asset/dataasset/index/','asset:data-asset:query'),
                    (20,'资产地图',2,200,'map','asset/market/map/index','asset:data-asset:query'),
                    (21,'数据集市',2,200,'mart','asset/market/index','asset:market:query'),
                    (22,'资产注册',2,100,'assetmap','asset/assetmap/index','asset:data-asset:create'),
                    (6243,'其他功能的同号菜单',2,0,'custom','custom/retained/index','custom:retained:query');
                INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES
                    (11,'注册',3,10,'asset:data-asset:create'),(12,'修改',3,10,'asset:data-asset:update'),
                    (13,'发布',3,10,'asset:data-asset:publish'),(14,'删除',3,10,'asset:data-asset:delete'),
                    (15,'预览',3,10,'asset:data-asset:preview'),(16,'血缘',3,10,'asset:data-asset:lineage'),
                    (17,'打标',3,10,'asset:data-asset:tag'),(18,'术语',3,10,'asset:data-asset:term'),
                    (19,'质量',3,10,'asset:quality-task:manage'),(30,'停供',3,10,'asset:data-asset:stop-supply'),
                    (31,'人工恢复',3,10,'asset:catalog-delivery:handle'),
                    (32,'类目查询',3,10,'asset:category-data-asset:query'),
                    (33,'类目创建',3,10,'asset:category-data-asset:create'),
                    (34,'类目修改',3,10,'asset:category-data-asset:update'),
                    (35,'类目删除',3,10,'asset:category-data-asset:delete'),
                    (36,'类目导出',3,10,'asset:category-data-asset:export'),
                    (37,'人工恢复子按钮',3,31,''),(38,'历史删除按钮',3,10,'custom:deleted'),
                    (39,'历史停用按钮',3,10,'custom:disabled'),(40,'自定义按钮',3,10,'custom:asset:inspect');
                UPDATE system_menu SET deleted=1 WHERE id=38;
                UPDATE system_menu SET status=1 WHERE id=39;
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
                    (1,10,10,1,0),(2,10,11,1,0),(3,10,31,1,0),(4,10,20,1,0),
                    (5,11,10,2,0),(6,12,21,1,0),(7,13,10,1,1),(8,14,32,1,0),
                    (9,15,10,1,0),(10,15,10,1,0),(11,10,37,1,0);
                INSERT INTO system_tenant_package(id,menu_ids,deleted) VALUES
                    (1,'[100,10,"11",31,6243,10]',0),(2,'["10","12",100,200]',0),
                    (3,'[21,6243]',0),(4,'[20,"10","11",100,200]',0),
                    (5,'[10]',1),(6,'[]',0);
                """);
    }

    @Test
    void retirementPreservesButtonsAndExistingScopesAndReplaysWithoutAdditionalChanges() throws Exception {
        String buttons = scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row WHERE menu_id IN (11,31,32,37)");
        sql(migration());
        assertThat(count("SELECT count(*) FROM system_menu WHERE trim(both '/' from coalesce(component,''))='asset/dataasset/index'")).isZero();
        assertThat(count("SELECT count(*) FROM system_menu WHERE id=6243")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_menu WHERE parent_id=10")).isZero();
        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN (11,12,13,14,15,16,17,18,19,30,31,32,33,34,35,36,38,39,40) AND parent_id=20")).isEqualTo(19);
        assertThat(count("SELECT parent_id FROM system_menu WHERE id=37")).isEqualTo(31);
        assertThat(scalar("SELECT deleted||'|'||status FROM system_menu WHERE id=38")).isEqualTo("1|0");
        assertThat(scalar("SELECT deleted||'|'||status FROM system_menu WHERE id=39")).isEqualTo("0|1");
        assertThat(scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row WHERE menu_id IN (11,31,32,37)")).isEqualTo(buttons);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=10")).isZero();
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=10 AND tenant_id=1 AND menu_id=20 AND deleted=0")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE id=4 AND menu_id=20 AND updater='original'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=11 AND tenant_id=2 AND menu_id IN (20,100,200) AND deleted=0")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id IN (12,13,14) AND menu_id IN (20,100,200) AND deleted=0")).isZero();
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=15 AND menu_id=20 AND deleted=0")).isEqualTo(1);
        assertThat(scalar("SELECT menu_ids::jsonb='[100,20,\"11\",31,6243,20,200]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids::jsonb='[\"20\",\"12\",100,200]'::jsonb FROM system_tenant_package WHERE id=2")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids::jsonb='[20,\"11\",100,200]'::jsonb FROM system_tenant_package WHERE id=4")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids::jsonb='[20,100,200]'::jsonb AND deleted=1 FROM system_tenant_package WHERE id=5")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=3")).isEqualTo("[21,6243]|unchanged");
        assertThat(scalar("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=6")).isEqualTo("[]|unchanged");
        assertThat(scalar("SELECT asset_name||'|'||status FROM dh_asset_data_asset WHERE id=1")).isEqualTo("共享目录资产|1");
        assertThat(scalar("SELECT status||'|'||reason FROM dh_asset_catalog_link_operation WHERE id=1")).isEqualTo("MANUAL|历史处置依据");
        String first = snapshot();
        String sequence = scalar("SELECT last_value FROM system_role_menu_seq");
        sql(migration());
        assertThat(snapshot()).isEqualTo(first);
        assertThat(scalar("SELECT last_value FROM system_role_menu_seq")).isEqualTo(sequence);
    }

    @Test
    void longIdsAreLocatedByComponentAndPackageStringTypesRemainExact() throws Exception {
        sql("UPDATE system_menu SET id=9007199254740995 WHERE id=10; UPDATE system_menu SET id=9007199254740993 WHERE id=20;"
                + "UPDATE system_menu SET parent_id=9007199254740995 WHERE parent_id=10;"
                + "UPDATE system_role_menu SET menu_id=9007199254740995 WHERE menu_id=10;"
                + "UPDATE system_role_menu SET menu_id=9007199254740993 WHERE menu_id=20;"
                + "TRUNCATE system_tenant_package; INSERT INTO system_tenant_package(id,menu_ids) VALUES(1,'[\"9007199254740995\",11,999]')");
        sql(migration());
        assertThat(count("SELECT count(*) FROM system_menu WHERE id=9007199254740995")).isZero();
        assertThat(count("SELECT parent_id FROM system_menu WHERE id=31")).isEqualTo(9007199254740993L);
        assertThat(scalar("SELECT menu_ids::jsonb='[\"9007199254740993\",11,999,100,200]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=11 AND tenant_id=2 AND menu_id=9007199254740993")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_menu WHERE id=6243")).isEqualTo(1);
    }

    @Test
    void disabledOrDeletedOldEntryDoesNotReviveQueryPermissionsOrPackageAllowance() throws Exception {
        for (String change : new String[]{"UPDATE system_menu SET status=1 WHERE id=10", "UPDATE system_menu SET deleted=1 WHERE id=10"}) {
            prepareIsolatedPermissionFixture();
            sql(change);
            sql(migration());
            assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=11 AND menu_id=20")).isZero();
            assertThat(scalar("SELECT menu_ids FROM system_tenant_package WHERE id=5")).isEqualTo("[]");
            assertThat(count("SELECT count(*) FROM system_menu WHERE id=31 AND parent_id=20 AND permission='asset:catalog-delivery:handle'")).isEqualTo(1);
        }
    }

    @Test
    void duplicateOrMismatchedIdentitiesRejectBeforeAnyPermissionChange() throws Exception {
        for (String change : new String[]{
                "INSERT INTO system_menu(id,type,parent_id,component,permission) VALUES(90,2,100,'asset/dataasset/index','asset:data-asset:query')",
                "INSERT INTO system_menu(id,type,parent_id,component,permission) VALUES(90,2,200,'/asset/market/map/index/','asset:data-asset:query')",
                "UPDATE system_menu SET status=1 WHERE id=20",
                "UPDATE system_menu SET permission='asset:market:query' WHERE id=20",
                "UPDATE system_menu SET permission='custom:query' WHERE id=10",
                "UPDATE system_menu SET permission='asset:market:query' WHERE id=200",
                "DELETE FROM system_menu WHERE id=20"
        }) {
            prepareIsolatedPermissionFixture();
            sql(change);
            String before = snapshot();
            assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("0177");
            sql("ROLLBACK");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void unexpectedDirectOrNestedPagesRollbackAllPermissionsAndSharedAssetState() throws Exception {
        for (int parent : new int[]{10, 31}) {
            prepareIsolatedPermissionFixture();
            sql("INSERT INTO system_menu(id,type,parent_id,component) VALUES(90,2," + parent + ",'custom/child/index')");
            String before = snapshot();
            assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("未知页面或目录子树");
            sql("ROLLBACK");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void malformedPackageStateRollsBackRatherThanDroppingOrGuessingReferences() throws Exception {
        sql("UPDATE system_tenant_package SET menu_ids='{}' WHERE id=1");
        String before = snapshot();
        assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("JSON 数组");
        sql("ROLLBACK");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void packageLengthFailureRollsBackAlreadyInsertedGrantsAndMovedButtons() throws Exception {
        sql("UPDATE system_tenant_package SET menu_ids=(SELECT '[10,'||string_agg('1',',')||']' FROM generate_series(1,2045)) WHERE id=1");
        assertThat(count("SELECT length(menu_ids) FROM system_tenant_package WHERE id=1")).isLessThanOrEqualTo(4096);
        String before = snapshot();
        assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("4096");
        sql("ROLLBACK");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("SELECT parent_id FROM system_menu WHERE id=31")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=11 AND menu_id=20")).isZero();
    }

    @Test
    void baselineThrough0177RetiresOnlyStandaloneEntryAndKeepsManagementPermissionsOnMap() throws Exception {
        sql("DROP SCHEMA \"datahub-center\" CASCADE");
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int laterMigration = baseline.indexOf("-- Migration: 0178 ");
        assertThat(laterMigration).isPositive();
        String baselineThrough0177 = baseline.substring(0, laterMigration).stripTrailing();
        assertThat(baselineThrough0177).endsWith(migration().stripTrailing());
        sql(baselineThrough0177);
        sql("SET search_path TO \"datahub-center\", public");
        assertThat(count("SELECT count(*) FROM system_menu WHERE trim(both '/' from coalesce(component,''))='asset/dataasset/index'")).isZero();
        long map = count("SELECT id FROM system_menu WHERE deleted=0 AND component='asset/market/map/index'");
        assertThat(count("SELECT count(*) FROM system_menu WHERE id=6243")).isZero();
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=6243")).isZero();
        assertThat(count("SELECT count(*) FROM system_menu WHERE parent_id=6243")).isZero();
        for (String permission : new String[]{"asset:data-asset:create", "asset:data-asset:update", "asset:data-asset:publish", "asset:data-asset:preview",
                "asset:data-asset:tag", "asset:data-asset:term", "asset:data-asset:delete",
                "asset:quality-task:manage", "asset:data-asset:stop-supply", "asset:catalog-delivery:handle", "asset:category-data-asset:query"}) {
            assertThat(count("SELECT count(*) FROM system_menu WHERE type=3 AND deleted=0 AND permission='" + permission + "' AND parent_id=" + map)).as(permission).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM system_menu WHERE deleted=0 AND component='asset/assetmap/index'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=1 AND menu_id=" + map + " AND deleted=0")).isEqualTo(1);
        assertThat(scalar("SELECT to_regclass('dh_asset_data_asset') IS NOT NULL AND to_regclass('dh_asset_catalog_link_operation') IS NOT NULL")).isEqualTo("t");
        String current = snapshot();
        sql(migration());
        assertThat(snapshot()).isEqualTo(current);
    }

    private String migration() throws Exception {
        return Files.readString(root.resolve("sql/postgresql/migrations/0177_asset_retire-standalone-management-menu.sql"));
    }

    private void sql(String script) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(script); }
    }

    private long count(String query) throws SQLException { return Long.parseLong(scalar(query)); }

    private String scalar(String query) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            assertThat(result.next()).isTrue(); return result.getString(1);
        }
    }

    private String snapshot() throws SQLException {
        return scalar("""
                SELECT jsonb_build_object(
                    'menus',(SELECT jsonb_agg(to_jsonb(menu) ORDER BY id) FROM system_menu menu),
                    'grants',(SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id) FROM system_role_menu grant_row),
                    'packages',(SELECT jsonb_agg(to_jsonb(package) ORDER BY id) FROM system_tenant_package package))::text
                """);
    }

    @AfterAll
    void dropOnlyOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (ownedDatabase != null) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + ownedDatabase + " WITH (FORCE)");
            }
        }
    }
}
