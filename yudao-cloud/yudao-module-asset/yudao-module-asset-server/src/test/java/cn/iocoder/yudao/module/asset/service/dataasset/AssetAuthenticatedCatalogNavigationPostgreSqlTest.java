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

/** 只在显式本机控制连接下验证自建数据库，不读取或修改任何现有业务库。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class AssetAuthenticatedCatalogNavigationPostgreSqlTest {

    private String controlUrl;
    private String ownedDatabase;
    private Connection connection;
    private Path root;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres");
        ownedDatabase = "asset_public_navigation_" + UUID.randomUUID().toString().replace("-", "");
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
        sql("ROLLBACK");
        sql("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\", public");
        sql("""
                CREATE SEQUENCE system_menu_seq START 1000;
                CREATE SEQUENCE system_menu_id_seq START 1000;
                CREATE SEQUENCE system_role_menu_seq START 1000;
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY DEFAULT nextval('system_menu_id_seq'),name text,permission text DEFAULT '',
                    type smallint,sort integer DEFAULT 0,parent_id bigint,path text,component text,component_name text,
                    status smallint DEFAULT 0,visible boolean DEFAULT true,deleted smallint DEFAULT 0,
                    creator text DEFAULT 'original',updater text DEFAULT 'unchanged',
                    create_time timestamp DEFAULT timestamp '2000-01-01',update_time timestamp DEFAULT timestamp '2000-01-01'
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
                CREATE TABLE system_role(id bigint PRIMARY KEY,status smallint DEFAULT 0);
                INSERT INTO system_role(id) VALUES(10),(11),(12),(13),(14),(15);
                INSERT INTO system_menu(id,name,type,parent_id,path,component,component_name,permission) VALUES
                    (100,'数据资产',1,0,'asset',NULL,NULL,''),
                    (200,'数据集市',1,100,'market',NULL,NULL,''),
                    (20,'资产地图',2,200,'map','asset/market/map/index','AssetMarketMap','asset:data-asset:query'),
                    (21,'数据集市',2,200,'mart','asset/market/index','AssetMarketMart','asset:market:query'),
                    (22,'其他功能',2,100,'custom','custom/index','CustomPage','custom:query');
                INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES
                    (30,'修改资产',3,20,'asset:data-asset:update'),
                    (31,'收藏资产',3,21,'asset:market:favorite'),
                    (32,'预览资产',3,20,'asset:data-asset:preview'),
                    (33,'历史删除按钮',3,20,'custom:deleted'),
                    (34,'历史停用按钮',3,20,'custom:disabled');
                UPDATE system_menu SET deleted=1 WHERE id=33;
                UPDATE system_menu SET status=1 WHERE id=34;
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
                    (1,10,20,1,0),(2,11,21,2,0),(3,12,30,1,0),(4,13,20,1,1),
                    (5,10,20,1,0),(6,10,32,1,0),(7,11,31,2,0),(8,15,20,3,0);
                INSERT INTO system_tenant_package(id,menu_ids,deleted,status) VALUES
                    (1,'[100,200,20,"21",30,"999"]',0,0),(2,'["20",30]',0,0),
                    (3,'[21]',0,0),(4,'[22,30]',0,0),(5,'[]',0,0),(6,'[20]',1,1);
                """);
    }

    @Test
    void upgradePreservesOriginalRoleAndPackageCapabilitiesWithoutGrantingWriteAccess() throws Exception {
        String originalButtons = scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE type=3");
        String originalGrants = scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row");
        sql(migration());
        long mapQuery = queryId("asset:data-asset:query");
        long martQuery = queryId("asset:market:query");
        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(20,21) AND permission=''")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_menu WHERE type=3 AND permission IN('asset:data-asset:query','asset:market:query')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=" + mapQuery + " AND role_id=10 AND tenant_id=1 AND deleted=0")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=" + mapQuery + " AND role_id=15 AND tenant_id=3 AND deleted=0")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id=" + martQuery + " AND role_id=11 AND tenant_id=2 AND deleted=0")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id IN(" + mapQuery + "," + martQuery + ") AND role_id IN(12,13,14)")).isZero();
        assertThat(scalar("SELECT jsonb_agg(to_jsonb(menu) ORDER BY id)::text FROM system_menu menu WHERE id IN(30,31,32,33,34)")).isEqualTo(originalButtons);
        assertThat(scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row WHERE id<=8")).isEqualTo(originalGrants);
        assertThat(scalar("SELECT menu_ids::jsonb='[100,200,20,\"21\",30,\"999\"," + martQuery + "," + mapQuery + "]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids::jsonb='[\"20\",30," + mapQuery + "]'::jsonb FROM system_tenant_package WHERE id=2")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids::jsonb='[21," + martQuery + "]'::jsonb FROM system_tenant_package WHERE id=3")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=4")).isEqualTo("[22,30]|unchanged");
        assertThat(scalar("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=5")).isEqualTo("[]|unchanged");
        assertThat(scalar("SELECT deleted||'|'||status FROM system_tenant_package WHERE id=6")).isEqualTo("1|1");
        String first = snapshot();
        String sequences = sequences();
        sql(migration());
        assertThat(snapshot()).isEqualTo(first);
        assertThat(sequences()).isEqualTo(sequences);
    }

    @Test
    void replayCannotRestoreRevokedQueryGrantsOrPackageAllowanceFromPublicPageAssignments() throws Exception {
        sql(migration());
        long mapQuery = queryId("asset:data-asset:query");
        long martQuery = queryId("asset:market:query");
        sql("UPDATE system_role_menu SET deleted=1 WHERE menu_id=" + mapQuery
                + "; DELETE FROM system_role_menu WHERE menu_id=" + martQuery
                + "; UPDATE system_tenant_package SET menu_ids='[100,200,20,21]' WHERE id IN(1,2,3)"
                + "; INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(100,14,20,1),(101,14,21,1)"
                + "; INSERT INTO system_tenant_package(id,menu_ids) VALUES(7,'[20,21]')");
        String revoked = snapshot();

        sql(migration());

        assertThat(snapshot()).isEqualTo(revoked);
        assertThat(count("SELECT count(*) FROM system_role_menu WHERE menu_id IN(" + mapQuery + "," + martQuery + ") AND deleted=0")).isZero();
    }

    @Test
    void replayCannotRestoreSoftDeletedOrPhysicallyDeletedQueryButtons() throws Exception {
        sql(migration());
        long mapQuery = queryId("asset:data-asset:query");
        long martQuery = queryId("asset:market:query");
        sql("UPDATE system_menu SET deleted=1 WHERE id=" + mapQuery + "; DELETE FROM system_menu WHERE id=" + martQuery);
        String revoked = snapshot();

        sql(migration());

        assertThat(snapshot()).isEqualTo(revoked);
        assertThat(count("SELECT count(*) FROM system_menu WHERE type=3 AND deleted=0 AND permission IN('asset:data-asset:query','asset:market:query')")).isZero();
    }

    @Test
    void disabledPagesAndExistingQueryButtonsKeepTheirDisabledState() throws Exception {
        sql("UPDATE system_menu SET status=1 WHERE id=21; INSERT INTO system_menu(id,name,type,parent_id,permission,status)"
                + " VALUES(40,'原停用查询',3,20,'asset:data-asset:query',1)");

        sql(migration());

        assertThat(scalar("SELECT permission||'|'||status FROM system_menu WHERE id=20")).isEqualTo("|0");
        assertThat(scalar("SELECT permission||'|'||status FROM system_menu WHERE id=21")).isEqualTo("|1");
        assertThat(count("SELECT status FROM system_menu WHERE id=40")).isEqualTo(1);
        assertThat(count("SELECT status FROM system_menu WHERE id=" + queryId("asset:market:query"))).isEqualTo(1);
        assertThat(scalar("SELECT deleted||'|'||status FROM system_menu WHERE id=33")).isEqualTo("1|0");
        assertThat(scalar("SELECT deleted||'|'||status FROM system_menu WHERE id=34")).isEqualTo("0|1");
    }

    @Test
    void duplicateIdentitiesOrInvalidAncestorsRollbackAllPermissionChanges() throws Exception {
        for (String change : new String[]{
                "INSERT INTO system_menu(id,type,parent_id,component,component_name,permission) VALUES(90,2,200,'/asset/market/map/index/','AssetMarketMap','asset:data-asset:query')",
                "UPDATE system_menu SET component_name='OtherPage' WHERE id=20",
                "UPDATE system_menu SET permission='custom:query' WHERE id=20",
                "UPDATE system_menu SET permission='custom:query' WHERE id=200",
                "UPDATE system_menu SET parent_id=200 WHERE id=100",
                "INSERT INTO system_menu(id,name,type,parent_id,permission,deleted) VALUES(90,'查询一',3,20,'asset:data-asset:query',0),(91,'查询二',3,20,'asset:data-asset:query',1)"
        }) {
            prepareFixture();
            sql(change);
            String before = snapshot();
            assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("0178");
            sql("ROLLBACK");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void malformedOrOversizedPackagesRollbackPageButtonsAndRoleChanges() throws Exception {
        for (String change : new String[]{
                "UPDATE system_tenant_package SET menu_ids='{}' WHERE id=1",
                "UPDATE system_tenant_package SET menu_ids=(SELECT '[20,'||string_agg('1',',')||']' FROM generate_series(1,2043)) WHERE id=1"
        }) {
            prepareFixture();
            sql(change);
            String before = snapshot();
            assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class);
            sql("ROLLBACK");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void longIdsAndExistingStringPackageEntriesRemainExactWithoutDuplicates() throws Exception {
        sql("UPDATE system_menu SET id=9007199254740993 WHERE id=20; UPDATE system_menu SET parent_id=9007199254740993 WHERE parent_id=20;"
                + "UPDATE system_role_menu SET menu_id=9007199254740993 WHERE menu_id=20;"
                + "TRUNCATE system_tenant_package; INSERT INTO system_tenant_package(id,menu_ids) VALUES(1,'[\"9007199254740993\",30]')");

        sql(migration());

        long mapQuery = queryId("asset:data-asset:query");
        assertThat(mapQuery).isGreaterThan(9007199254740993L);
        assertThat(scalar("SELECT menu_ids::jsonb='[\"9007199254740993\",30," + mapQuery + "]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        String first = snapshot();
        sql(migration());
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    void allocationUsesBothSequenceHighWatermarksWithoutReusingDeletedMenuIds() throws Exception {
        sql("SELECT setval('system_menu_seq',9000); SELECT setval('system_menu_id_seq',10000)");

        sql(migration());

        assertThat(queryId("asset:market:query")).isEqualTo(10001);
        assertThat(queryId("asset:data-asset:query")).isEqualTo(10002);
        assertThat(count("SELECT last_value FROM system_menu_seq")).isEqualTo(10002);
        assertThat(count("SELECT last_value FROM system_menu_id_seq")).isEqualTo(10002);
        String first = snapshot();
        String sequences = sequences();
        sql(migration());
        assertThat(snapshot()).isEqualTo(first);
        assertThat(sequences()).isEqualTo(sequences);
    }

    @Test
    void baselineThrough0178ContainsPublicPagesAndScopedQueryButtonsAndReplays() throws Exception {
        sql("DROP SCHEMA \"datahub-center\" CASCADE");
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int laterMigration = baseline.indexOf("-- Migration: 0179 ");
        assertThat(laterMigration).isPositive();
        String baselineThrough0178 = baseline.substring(0, laterMigration).stripTrailing();
        assertThat(baselineThrough0178).endsWith(migration().stripTrailing());

        sql(baselineThrough0178);
        sql("SET search_path TO \"datahub-center\", public");

        assertThat(count("SELECT count(*) FROM system_menu WHERE deleted=0 AND permission='' AND component IN('asset/market/index','asset/market/map/index')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM system_menu WHERE deleted=0 AND type=3 AND permission IN('asset:market:query','asset:data-asset:query')")).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM system_role_menu query_grant
                JOIN system_menu query_menu ON query_menu.id=query_grant.menu_id
                WHERE query_grant.deleted=0 AND query_menu.permission IN('asset:market:query','asset:data-asset:query')
                  AND NOT EXISTS(SELECT 1 FROM system_role_menu page_grant WHERE page_grant.menu_id=query_menu.parent_id
                      AND page_grant.role_id=query_grant.role_id AND page_grant.tenant_id=query_grant.tenant_id AND page_grant.deleted=0)
                """)).isZero();
        String first = snapshot();
        sql(migration());
        assertThat(snapshot()).isEqualTo(first);
    }

    private String migration() throws Exception {
        return Files.readString(root.resolve("sql/postgresql/migrations/0178_asset_authenticated-catalog-navigation.sql"));
    }

    private long queryId(String permission) throws SQLException {
        return count("SELECT id FROM system_menu WHERE type=3 AND permission='" + permission + "'");
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

    private String sequences() throws SQLException {
        return scalar("SELECT (SELECT last_value FROM system_menu_seq)||'|'||(SELECT last_value FROM system_menu_id_seq)||'|'||(SELECT last_value FROM system_role_menu_seq)");
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
