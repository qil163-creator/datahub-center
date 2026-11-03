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

/** 仅在显式本机控制连接下创建并清理本测试数据库，验证权限补偿和最终基线。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class AssetRoleCatalogNavigationPostgreSqlTest {
    private String controlUrl;
    private String ownedDatabase;
    private Connection connection;
    private Path root;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres");
        ownedDatabase = "asset_role_navigation_" + UUID.randomUUID().toString().replace("-", "");
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
    void prepareFixture() throws Exception {
        prepareOriginalFixture();
        sql(migration0178());
    }

    private void prepareOriginalFixture() throws Exception {
        sql("ROLLBACK; DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\", public");
        sql("""
                CREATE SEQUENCE system_menu_seq START 1000;
                CREATE SEQUENCE system_menu_id_seq START 1000;
                CREATE SEQUENCE system_role_menu_seq START 1000;
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY,name text,permission text DEFAULT '',type smallint,sort integer DEFAULT 0,
                    parent_id bigint,path text DEFAULT '',icon text DEFAULT '#',component text,component_name text,
                    status smallint DEFAULT 0,visible boolean DEFAULT true,keep_alive boolean DEFAULT true,
                    always_show boolean DEFAULT true,deleted smallint DEFAULT 0,creator text DEFAULT 'original',
                    updater text DEFAULT 'unchanged',create_time timestamp DEFAULT CURRENT_TIMESTAMP,
                    update_time timestamp DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE system_role_menu (
                    id bigint PRIMARY KEY,role_id bigint,menu_id bigint,tenant_id bigint DEFAULT 1,
                    creator text DEFAULT 'original',updater text DEFAULT 'original',deleted smallint DEFAULT 0,
                    create_time timestamp DEFAULT CURRENT_TIMESTAMP,update_time timestamp DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE system_tenant_package (
                    id bigint PRIMARY KEY,menu_ids varchar(4096),deleted smallint DEFAULT 0,status smallint DEFAULT 0,
                    updater text DEFAULT 'unchanged',update_time timestamp DEFAULT CURRENT_TIMESTAMP
                );
                INSERT INTO system_menu(id,name,type,parent_id,path,component,component_name,permission) VALUES
                    (100,'数据资产',1,0,'asset',NULL,NULL,''),(200,'数据集市',1,100,'market',NULL,NULL,''),
                    (20,'资产地图',2,200,'map','asset/market/map/index','AssetMarketMap','asset:data-asset:query'),
                    (21,'数据集市',2,200,'mart','asset/market/index','AssetMarketMart','asset:market:query');
                INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES
                    (30,'修改资产',3,20,'asset:data-asset:update'),(31,'收藏资产',3,21,'asset:market:favorite'),
                    (32,'预览资产',3,20,'asset:data-asset:preview'),(33,'历史删除按钮',3,20,'custom:deleted'),
                    (34,'历史停用按钮',3,20,'custom:disabled');
                UPDATE system_menu SET deleted=1 WHERE id=33;
                UPDATE system_menu SET status=1 WHERE id=34;
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
                    (1,10,20,1,0),(2,11,21,2,0),(3,12,30,1,0),(4,13,20,1,1),
                    (5,10,20,1,0),(6,10,32,1,0),(7,11,31,2,0),(8,15,20,3,0);
                INSERT INTO system_tenant_package(id,menu_ids,deleted,status) VALUES
                    (1,'[100,200,20,"21",30,"999"]',0,0),(2,'["20",30]',0,0),
                    (3,'[21]',0,0),(4,'[30,"999"]',0,0),(5,'[]',0,0),(6,'[20]',1,1);
                """);
    }

    @Test
    void compensationUsesCurrentQueriesAndReachesPagesWithoutRestoringRevokedAccess() throws Exception {
        long mapQuery = queryId("asset:data-asset:query");
        long martQuery = queryId("asset:market:query");
        sql("UPDATE system_role_menu SET deleted=1 WHERE role_id=10 AND menu_id=" + mapQuery
                + "; DELETE FROM system_role_menu WHERE role_id=15 AND menu_id=20"
                + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(100,14,20,1),(101,16," + mapQuery + ",4)"
                + "; UPDATE system_tenant_package SET menu_ids='[100,200,20,\"21\",30,\"999\"," + martQuery + "]' WHERE id=1"
                + "; UPDATE system_tenant_package SET menu_ids='[\"" + mapQuery + "\",30]' WHERE id=2"
                + "; INSERT INTO system_tenant_package(id,menu_ids) VALUES(7,'[20,\"21\",999]')");
        String writeMenus = writeMenus();
        String writeGrants = writeGrants();

        sql(migration());

        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(20,21) AND permission IN('asset:data-asset:query','asset:market:query')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=20 AND role_id IN(10,14) AND deleted=0")).isZero();
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id IN(20,100,200) AND role_id=16 AND tenant_id=4 AND deleted=0")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id IN(20,100,200) AND role_id=15 AND tenant_id=3 AND deleted=0")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id IN(12,13) AND menu_id=20 AND deleted=0")).isZero();
        assertThat(scalar("SELECT menu_ids::jsonb='[100,200,\"21\",30,\"999\"]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids::jsonb='[30,100,200,20]'::jsonb FROM system_tenant_package WHERE id=2")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids FROM system_tenant_package WHERE id=7")).isEqualTo("[999]");
        assertThat(scalar("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=4")).isEqualTo("[30,\"999\"]|unchanged");
        assertThat(scalar("SELECT deleted||'|'||status FROM system_tenant_package WHERE id=6")).isEqualTo("1|1");
        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(" + mapQuery + "," + martQuery + ") AND deleted=1 AND updater='migration:0179'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id IN(" + mapQuery + "," + martQuery + ") AND deleted=0")).isZero();
        assertThat(writeMenus()).isEqualTo(writeMenus);
        assertThat(writeGrants()).isEqualTo(writeGrants);
        String compensated = snapshot();
        sql(migration());
        assertThat(snapshot()).isEqualTo(compensated);
    }

    @Test
    void replayDoesNotRestoreRevokedPageOrAncestorGrantsOrPackageAllowances() throws Exception {
        sql(migration());
        sql("UPDATE system_role_menu SET deleted=1 WHERE role_id=10 AND menu_id IN(20,100,200)"
                + "; DELETE FROM system_role_menu WHERE role_id=11 AND menu_id=21"
                + "; UPDATE system_tenant_package SET menu_ids='[30]' WHERE id IN(1,2,3)");
        String revoked = snapshot();

        sql(migration());

        assertThat(snapshot()).isEqualTo(revoked);
    }

    @Test
    void existingEquivalentPermissionOutsidePageSubtreeRetainsItsNavigationAndHumanNode() throws Exception {
        long mapQuery = queryId("asset:data-asset:query");
        sql("UPDATE system_role_menu SET deleted=1 WHERE menu_id=" + mapQuery
                + "; INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES(700,'外部人工查询',3,100,'asset:data-asset:query')"
                + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(100,10,700,1),(101,18,700,6),(102,19,20,1)"
                + "; INSERT INTO system_tenant_package(id,menu_ids) VALUES(7,'[20,null,{\"unknown\":\"keep\"},\"700\",30]')");
        String node = scalar("SELECT to_jsonb(menu)::text FROM system_menu menu WHERE id=700");

        sql(migration());

        assertThat(scalar("SELECT to_jsonb(menu)::text FROM system_menu menu WHERE id=700")).isEqualTo(node);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=10 AND tenant_id=1 AND menu_id=20 AND deleted=0")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=18 AND tenant_id=6 AND menu_id IN(20,100,200) AND deleted=0")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=19 AND menu_id=20 AND deleted=0")).isZero();
        assertThat(scalar("SELECT menu_ids::jsonb='[20,null,{\"unknown\":\"keep\"},\"700\",30,100,200]'::jsonb FROM system_tenant_package WHERE id=7")).isEqualTo("t");
    }

    @Test
    void humanButtonsAndManuallyChangedMigrationNodesArePreserved() throws Exception {
        for (String change : new String[]{
                "UPDATE system_menu SET name='人工名称' WHERE id=",
                "UPDATE system_menu SET updater='operator',update_time=CURRENT_TIMESTAMP WHERE id=",
                "UPDATE system_menu SET visible=true WHERE id="
        }) {
            prepareFixture();
            long mapQuery = queryId("asset:data-asset:query");
            sql(change + mapQuery);
            sql("INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES(700,'人工查询',3,20,'asset:data-asset:query')"
                    + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(100,17,700,5)"
                    + "; INSERT INTO system_tenant_package(id,menu_ids) VALUES(7,'[\"700\",30]')");
            String manualNodes = scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(700," + mapQuery + ")");
            String manualGrants = scalar("SELECT to_jsonb(grant_row)::text FROM system_role_menu grant_row WHERE id=100");

            sql(migration());

            assertThat(scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(700," + mapQuery + ")")).isEqualTo(manualNodes);
            assertThat(scalar("SELECT to_jsonb(grant_row)::text FROM system_role_menu grant_row WHERE id=100")).isEqualTo(manualGrants);
            assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=17 AND tenant_id=5 AND menu_id IN(20,100,200) AND deleted=0")).isEqualTo(3);
            assertThat(scalar("SELECT menu_ids::jsonb='[\"700\",30,100,200,20]'::jsonb FROM system_tenant_package WHERE id=7")).isEqualTo("t");
        }
    }

    @Test
    void inactiveDeletedOrMissingQueryChildCannotRestoreNavigationOnlyAccess() throws Exception {
        for (String change : new String[]{"UPDATE system_menu SET status=1 WHERE id=",
                "UPDATE system_menu SET deleted=1 WHERE id=", "DELETE FROM system_menu WHERE id="}) {
            prepareFixture();
            long mapQuery = queryId("asset:data-asset:query");
            sql(change + mapQuery);

            sql(migration());

            assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=20 AND deleted=0")).isZero();
            assertThat(count("SELECT count(*) FROM system_tenant_package package WHERE EXISTS(SELECT 1 FROM jsonb_array_elements_text(package.menu_ids::jsonb) entry(value) WHERE entry.value='20')")).isZero();
            assertThat(scalar("SELECT permission FROM system_menu WHERE id=20")).isEqualTo("asset:data-asset:query");
        }
    }

    @Test
    void disabledNavigationAndNestedHumanChangesAreNotEnabledOrDeleted() throws Exception {
        long mapQuery = queryId("asset:data-asset:query");
        sql("UPDATE system_menu SET status=1 WHERE id IN(100,20)"
                + "; INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES(700,'人工后代',3," + mapQuery + ",'custom:inspect')");
        String nodes = scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(700," + mapQuery + ")");

        sql(migration());

        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(100,20) AND status=1")).isEqualTo(2);
        assertThat(scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(700," + mapQuery + ")")).isEqualTo(nodes);
    }

    @Test
    void original0177StateWithout0178IsAnExactNoOp() throws Exception {
        prepareOriginalFixture();
        String original = snapshot();
        String sequence = scalar("SELECT last_value||'|'||is_called FROM system_role_menu_seq");

        sql(migration());

        assertThat(snapshot()).isEqualTo(original);
        assertThat(scalar("SELECT last_value||'|'||is_called FROM system_role_menu_seq")).isEqualTo(sequence);
    }

    @Test
    void identityAncestorAndPackageErrorsRollbackAllCompensation() throws Exception {
        for (String change : new String[]{
                "INSERT INTO system_menu(id,type,parent_id,component,component_name,permission) VALUES(90,2,200,'/asset/market/map/index/','AssetMarketMap','')",
                "UPDATE system_menu SET permission='custom:query' WHERE id=20",
                "UPDATE system_menu SET permission='custom:query' WHERE id=100",
                "UPDATE system_menu SET parent_id=200 WHERE id=100",
                "UPDATE system_tenant_package SET menu_ids='{}' WHERE id=1",
                "UPDATE system_tenant_package SET menu_ids=(SELECT '[" + queryId("asset:data-asset:query") + ",'||string_agg('1',',')||']' FROM generate_series(1,2040)) WHERE id=1"
        }) {
            prepareFixture();
            sql(change);
            String original = snapshot();
            assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class);
            sql("ROLLBACK");
            assertThat(snapshot()).isEqualTo(original);
        }
    }

    @Test
    void missingNavigationUsesRoleSequenceHighWatermarkAndPreservesTenantScope() throws Exception {
        long mapQuery = queryId("asset:data-asset:query");
        sql("SELECT setval('system_role_menu_seq',10000)"
                + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(100,18," + mapQuery + ",6)");

        sql(migration());

        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=18 AND tenant_id=6 AND menu_id IN(20,100,200) AND deleted=0 AND id>10000")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE role_id=18 AND tenant_id<>6 AND deleted=0")).isZero();
        assertThat(count("SELECT last_value FROM system_role_menu_seq")).isEqualTo(count("SELECT max(id) FROM system_role_menu"));
    }

    @Test
    void baselineThrough0179RestoresRolePagesAndReplaysWithoutNewerMigrations() throws Exception {
        sql("DROP SCHEMA \"datahub-center\" CASCADE");
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int laterMigration = baseline.indexOf("-- Migration: 0180 ");
        assertThat(laterMigration).isPositive();
        String baselineThrough0179 = baseline.substring(0, laterMigration).stripTrailing();
        assertThat(baselineThrough0179).endsWith(migration().stripTrailing());

        sql(baselineThrough0179);
        sql("SET search_path TO \"datahub-center\", public");

        assertThat(count("SELECT count(*) FROM system_menu WHERE deleted=0 AND type=2 AND component IN('asset/market/index','asset/market/map/index') AND permission IN('asset:market:query','asset:data-asset:query')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_menu WHERE creator='migration:0178' AND type=3 AND permission IN('asset:market:query','asset:data-asset:query') AND deleted=0")).isZero();
        assertThat(count("SELECT count(*) FROM system_role_menu grant_row JOIN system_menu menu ON menu.id=grant_row.menu_id WHERE menu.updater='migration:0179' AND menu.deleted=1 AND grant_row.deleted=0")).isZero();
        assertThat(count("SELECT count(*) FROM system_menu WHERE deleted=0 AND permission IN('asset:data-asset:update','asset:data-asset:publish','asset:data-asset:preview')")).isGreaterThanOrEqualTo(3);
        String current = snapshot();
        sql(migration());
        assertThat(snapshot()).isEqualTo(current);
    }

    private String migration() throws Exception {
        return Files.readString(root.resolve("sql/postgresql/migrations/0179_asset_restore-role-catalog-navigation.sql"));
    }

    private String migration0178() throws Exception {
        return Files.readString(root.resolve("sql/postgresql/migrations/0178_asset_authenticated-catalog-navigation.sql"));
    }

    private long queryId(String permission) throws SQLException {
        return count("SELECT id FROM system_menu WHERE type=3 AND permission='" + permission + "' AND deleted=0");
    }

    private String writeMenus() throws SQLException {
        return scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(30,31,32,33,34)");
    }

    private String writeGrants() throws SQLException {
        return scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row WHERE menu_id IN(30,31,32,33,34)");
    }

    private void sql(String script) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(script); }
    }

    private long count(String query) throws SQLException { return Long.parseLong(scalar(query)); }

    private String scalar(String query) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
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
