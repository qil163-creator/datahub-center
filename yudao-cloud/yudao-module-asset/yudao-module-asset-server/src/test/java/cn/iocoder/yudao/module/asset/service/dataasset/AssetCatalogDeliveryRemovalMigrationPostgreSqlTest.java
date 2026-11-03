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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AssetCatalogDeliveryRemovalMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void removalReplaysAndPreservesUnrelatedPermissionsAndCatalogOperations() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            createPrerequisites(sql);
            sql.execute(migration());
            assertEquals("4", one(sql, "SELECT count(*) FROM system_menu"));
            assertEquals("3", one(sql, "SELECT count(*) FROM system_role_menu"));
            assertEquals("asset:data-asset:stop-supply", one(sql, "SELECT permission FROM system_menu WHERE id=11"));
            assertEquals("10|asset:catalog-delivery:handle|0|0", one(sql, """
                    SELECT parent_id || '|' || permission || '|' || status || '|' || deleted
                    FROM system_menu WHERE id=50
                    """));
            assertEquals("50", one(sql, "SELECT parent_id FROM system_menu WHERE id=51"));
            assertEquals("2", one(sql, "SELECT count(*) FROM system_role_menu WHERE menu_id IN (50,51) AND role_id=1"));
            assertEquals("preserved", one(sql, """
                    SELECT CASE WHEN menu_ids::jsonb = '[10,"11",50,51,10]'::jsonb THEN 'preserved' ELSE 'changed' END
                    FROM system_tenant_package WHERE id=1
                    """));
            assertEquals("[]", one(sql, "SELECT menu_ids FROM system_tenant_package WHERE id=2"));
            assertEquals("unchanged", one(sql, "SELECT updater FROM system_tenant_package WHERE id=3"));
            assertEquals("preserved", one(sql, """
                    SELECT CASE WHEN menu_ids::jsonb = '[10,"11","50",51]'::jsonb THEN 'preserved' ELSE 'changed' END
                    FROM system_tenant_package WHERE id=3
                    """));
            assertEquals("MANUAL|REJECTED|historical reason", one(sql, """
                    SELECT status || '|' || outcome || '|' || disposition_reason
                    FROM dh_asset_catalog_link_operation WHERE id=1
                    """));
            assertNotNull(one(sql, "SELECT to_regclass('dh_asset_catalog_link_operation_seq')"));
            assertNotNull(one(sql, "SELECT to_regclass('idx_asset_catalog_link_due')"));
            String firstUpdatedAt = one(sql, "SELECT update_time FROM system_tenant_package WHERE id=1");
            String firstHandleUpdatedAt = one(sql, "SELECT update_time FROM system_menu WHERE id=50");
            sql.execute(migration());
            assertEquals(firstUpdatedAt, one(sql, "SELECT update_time FROM system_tenant_package WHERE id=1"));
            assertEquals(firstHandleUpdatedAt, one(sql, "SELECT update_time FROM system_menu WHERE id=50"));
            assertEquals("1", one(sql, "SELECT count(*) FROM dh_asset_catalog_link_operation"));
        }
    }

    @Test
    void unknownHandlerChildRollsBackReparentingAndCleanup() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            createPrerequisites(sql);
            sql.execute("""
                    INSERT INTO system_menu(id,type,parent_id,permission)
                    VALUES(90,3,50,'asset:catalog-delivery:query')
                    """);
            SQLException failure = assertThrows(SQLException.class, () -> sql.execute(migration()));
            assertTrue(failure.getMessage().contains("未知权限或页面子树"));
            sql.execute("ROLLBACK");
            assertEquals("20", one(sql, "SELECT parent_id FROM system_menu WHERE id=50"));
            assertEquals("12", one(sql, "SELECT count(*) FROM system_menu"));
            assertEquals("unchanged", one(sql, "SELECT updater FROM system_tenant_package WHERE id=1"));
        }
    }

    @Test
    void absentHandlerDoesNotCreatePermissionsOrGrants() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            createPrerequisites(sql);
            sql.execute("DELETE FROM system_menu WHERE id IN (50,51)");
            sql.execute(migration());
            assertEquals("0", one(sql, "SELECT count(*) FROM system_menu WHERE permission='asset:catalog-delivery:handle'"));
            assertEquals("2", one(sql, "SELECT count(*) FROM system_menu"));
        }
    }

    @Test
    void missingOrDuplicateActiveAssetMenuRejectsMigration() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            for (String change : new String[]{
                    "UPDATE system_menu SET status=1 WHERE id=10",
                    "INSERT INTO system_menu(id,type,status,component) VALUES(90,2,0,'asset/dataasset/index')"
            }) {
                createPrerequisites(sql);
                sql.execute(change);
                SQLException failure = assertThrows(SQLException.class, () -> sql.execute(migration()));
                assertTrue(failure.getMessage().contains("唯一有效的资产管理菜单"));
                sql.execute("ROLLBACK");
                assertEquals("20", one(sql, "SELECT parent_id FROM system_menu WHERE id=50"));
                assertEquals("unchanged", one(sql, "SELECT updater FROM system_tenant_package WHERE id=1"));
            }
        }
    }

    @Test
    void unexpectedChildPageRollsBackAllPermissionCleanup() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            createPrerequisites(sql);
            sql.execute("""
                    INSERT INTO system_menu(id,type,parent_id,path,component)
                    VALUES(90,2,20,'custom-page','asset/custom/index')
                    """);
            SQLException failure = assertThrows(SQLException.class, () -> sql.execute(migration()));
            assertTrue(failure.getMessage().contains("禁止连带删除"));
            sql.execute("ROLLBACK");
            assertEquals("12", one(sql, "SELECT count(*) FROM system_menu"));
            assertEquals("10", one(sql, "SELECT count(*) FROM system_role_menu"));
            assertEquals("unchanged", one(sql, "SELECT updater FROM system_tenant_package WHERE id=1"));
            assertEquals("1", one(sql, "SELECT count(*) FROM dh_asset_catalog_link_operation"));
        }
    }

    private static void createPrerequisites(Statement sql) throws SQLException {
        sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        sql.execute("CREATE SCHEMA \"datahub-center\"");
        sql.execute("SET search_path TO \"datahub-center\", public");
        sql.execute("""
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY,type int,parent_id bigint,path text,component text,
                    component_name text,permission text,deleted int DEFAULT 0,status int DEFAULT 0,
                    updater text DEFAULT 'unchanged',update_time timestamp DEFAULT timestamp '2000-01-01'
                );
                CREATE TABLE system_role_menu (id bigint PRIMARY KEY,role_id bigint,menu_id bigint);
                CREATE TABLE system_tenant_package (
                    id bigint PRIMARY KEY,menu_ids text,updater text,update_time timestamp,deleted int DEFAULT 0
                );
                CREATE SEQUENCE dh_asset_catalog_link_operation_seq;
                CREATE TABLE dh_asset_catalog_link_operation (
                    id bigint PRIMARY KEY DEFAULT nextval('dh_asset_catalog_link_operation_seq'),
                    tenant_id bigint,status text,outcome text,disposition_reason text,next_retry_time timestamp,
                    deleted int DEFAULT 0
                );
                CREATE INDEX idx_asset_catalog_link_due ON dh_asset_catalog_link_operation(tenant_id,status,next_retry_time)
                    WHERE deleted=0;
                INSERT INTO dh_asset_catalog_link_operation(tenant_id,status,outcome,disposition_reason)
                    VALUES(1,'MANUAL','REJECTED','historical reason');
                INSERT INTO system_menu(id,type,parent_id,path,component,component_name,permission,deleted) VALUES
                    (10,2,1,'dataasset','asset/dataasset/index','DataAsset','asset:data-asset:query',0),
                    (11,3,10,'',NULL,NULL,'asset:data-asset:stop-supply',0),
                    (20,2,1,'renamed','/asset/catalogdelivery/index/','Renamed','',0),
                    (21,3,20,'',NULL,NULL,'asset:catalog-delivery:query',0),
                    (22,3,21,'',NULL,NULL,'',1),
                    (30,2,1,'renamed','asset/retired/index','AssetCatalogDelivery','',1),
                    (40,2,1,'/asset/catalog-delivery/',NULL,NULL,'',0),
                    (50,3,20,'',NULL,NULL,'asset:catalog-delivery:handle',0),
                    (51,3,50,'',NULL,NULL,'',0),
                    (60,2,1,'catalog-delivery',NULL,NULL,'',0),
                    (70,3,10,'',NULL,NULL,'asset:catalog-delivery:inspect',0);
                INSERT INTO system_role_menu(id,role_id,menu_id)
                    SELECT id,1,id FROM system_menu WHERE id<>10;
                INSERT INTO system_tenant_package(id,menu_ids,updater,update_time,deleted) VALUES
                    (1,'[10,20,"11","21",22,30,40,50,51,60,70,10]','unchanged',timestamp '2000-01-01',0),
                    (2,'[20,"21"]','unchanged',timestamp '2000-01-01',1),
                    (3,'[10,"11","50",51]','unchanged',timestamp '2000-01-01',0);
                """);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (ResultSet result = sql.executeQuery(query)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static String migration() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        return Files.readString(root.resolve("sql/postgresql/migrations/0151_asset_catalog-delivery-removal.sql"));
    }
}
