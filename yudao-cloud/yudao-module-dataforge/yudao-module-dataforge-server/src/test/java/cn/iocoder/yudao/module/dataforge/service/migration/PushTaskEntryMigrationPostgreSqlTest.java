package cn.iocoder.yudao.module.dataforge.service.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实 PostgreSQL 的独立随机模式中验证单入口菜单、原授权与迁移重放。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
@Timeout(45)
class PushTaskEntryMigrationPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0161_dataforge_single-push-task-entry.sql";
    private final String schema = "push_entry_" + UUID.randomUUID().toString().replace("-", "");
    private Connection connection;
    private String migration;

    @BeforeEach
    void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema + ",pg_catalog");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(MIGRATION))) root = root.getParent();
        assertNotNull(root, "应从仓库读取当前菜单迁移");
        migration = Files.readString(root.resolve(MIGRATION)).replace("\"datahub-center\"", "\"" + schema + "\"");
        execute("""
                CREATE TABLE system_menu (id bigint PRIMARY KEY, name text, permission text DEFAULT '',
                    type integer, parent_id bigint DEFAULT 0, component text, status integer DEFAULT 0,
                    visible boolean DEFAULT true, deleted smallint DEFAULT 0,
                    updater text DEFAULT '原用户', update_time timestamp DEFAULT CURRENT_TIMESTAMP);
                CREATE TABLE system_role_menu (id bigint PRIMARY KEY, tenant_id bigint, role_id bigint, menu_id bigint,
                    deleted smallint DEFAULT 0, updater text DEFAULT '原用户', update_time timestamp DEFAULT CURRENT_TIMESTAMP);
                CREATE TABLE system_tenant_package (id bigint PRIMARY KEY, menu_ids varchar(4096),
                    updater text DEFAULT '原用户', update_time timestamp DEFAULT CURRENT_TIMESTAMP);
                INSERT INTO system_menu (id,name,permission,type,parent_id,component) VALUES
                    (5987,'数据推送','',1,0,NULL),
                    (6859,'推送配置','dataforge:data-push:query',2,5987,'dataforge/datapush/config/index'),
                    (6860,'推送任务','dataforge:data-push:query',2,5987,'dataforge/datapush/task/index'),
                    (6861,'推送配置创建','dataforge:data-push:create',3,6859,NULL),
                    (6862,'推送配置更新','dataforge:data-push:update',3,6859,NULL),
                    (6863,'推送配置删除','dataforge:data-push:delete',3,6859,NULL),
                    (6864,'推送任务执行','dataforge:data-push:run',3,6860,NULL),
                    (6867,'推送任务创建','dataforge:data-push:create',3,6860,NULL),
                    (922,'数据同步','dataforge:data-access:query',2,0,'dataforge/access/index');
                INSERT INTO system_role_menu (id,tenant_id,role_id,menu_id,deleted) VALUES
                    (1,1,11,6859,0),(2,1,11,6861,0),
                    (3,1,12,6859,0),(4,1,12,6860,0),
                    (5,2,11,6859,0),(6,2,11,6860,1),
                    (7,1,13,6859,1),(8,1,11,6859,0),
                    (9,1,14,922,0);
                INSERT INTO system_tenant_package (id,menu_ids) VALUES
                    (1,'[922,"6859",6861]'),(2,'["6859","6860"]'),(3,'[922]'),(4,'[]');
                CREATE TABLE dh_dataforge_push_config (id bigint PRIMARY KEY, channel_json jsonb);
                INSERT INTO dh_dataforge_push_config VALUES (1,'{"http":{"url":"https://example.invalid"}}');
                CREATE TABLE dh_dataforge_push_task (id bigint PRIMARY KEY, push_config_id bigint, name text);
                INSERT INTO dh_dataforge_push_task VALUES (1,1,'原任务'),(2,1,'历史共享任务');
                """);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection != null) {
            try (Connection owned = connection) {
                execute("ROLLBACK");
                assertTrue(schema.matches("push_entry_[a-f0-9]{32}"));
                execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void keepsPermissionsAndBusinessDataWhileConsolidatingAndReplaying() throws Exception {
        execute(migration);
        assertEquals("6860", value("SELECT string_agg(id::text,',' ORDER BY id) FROM system_menu WHERE type=2 AND parent_id=5987 AND visible"));
        assertEquals("0", value("SELECT status FROM system_menu WHERE id=6859"));
        assertEquals("3", value("SELECT count(*) FROM system_menu WHERE id IN (6861,6862,6863) AND parent_id=6860"));
        assertEquals("1", value("SELECT count(*) FROM system_role_menu WHERE tenant_id=1 AND role_id=11 AND menu_id=6860 AND deleted=0"));
        assertEquals("1", value("SELECT count(*) FROM system_role_menu WHERE tenant_id=1 AND role_id=12 AND menu_id=6860 AND deleted=0"));
        assertEquals("1", value("SELECT count(*) FROM system_role_menu WHERE tenant_id=2 AND role_id=11 AND menu_id=6860 AND deleted=0"));
        assertEquals("6859", value("SELECT menu_id FROM system_role_menu WHERE id=7"));
        assertEquals("6861", value("SELECT menu_id FROM system_role_menu WHERE id=2"));
        assertEquals("922", value("SELECT menu_id FROM system_role_menu WHERE id=9"));
        assertEquals("[922,\"6859\",6861,6860]", value("SELECT menu_ids FROM system_tenant_package WHERE id=1"));
        assertEquals("[\"6859\",\"6860\"]", value("SELECT menu_ids FROM system_tenant_package WHERE id=2"));
        assertEquals("原用户", value("SELECT updater FROM system_tenant_package WHERE id=3"));
        assertBusinessData();
        String snapshot = value("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row");
        String packageSnapshot = value("SELECT jsonb_agg(to_jsonb(package) ORDER BY id)::text FROM system_tenant_package package");
        execute(migration);
        assertEquals(snapshot, value("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row"));
        assertEquals(packageSnapshot, value("SELECT jsonb_agg(to_jsonb(package) ORDER BY id)::text FROM system_tenant_package package"));
        assertBusinessData();
    }

    @Test
    void doesNotEnableAdministrativelyDisabledMenus() throws Exception {
        execute("UPDATE system_menu SET status=1 WHERE id IN (6859,6860)");
        execute(migration);
        assertEquals("2", value("SELECT count(*) FROM system_menu WHERE id IN (6859,6860) AND status=1"));
    }

    @Test
    void unrelatedChildRejectsChangeAndRollsBack() throws Exception {
        execute("INSERT INTO system_menu (id,name,permission,type,parent_id) VALUES (7000,'其他功能','other:query',2,6859)");
        SQLException failure = assertThrows(SQLException.class, () -> execute(migration));
        assertTrue(failure.getMessage().contains("其他功能子项"));
        execute("ROLLBACK");
        assertOriginalPermissions();
    }

    @Test
    void missingTaskEntryRejectsChangeAndRollsBack() throws Exception {
        execute("UPDATE system_menu SET component='other/task/index' WHERE id=6860");
        SQLException failure = assertThrows(SQLException.class, () -> execute(migration));
        assertTrue(failure.getMessage().contains("缺少同父级"));
        execute("ROLLBACK");
        assertOriginalPermissions();
    }

    @Test
    void packageLengthOverflowRollsBackEarlierMenuAndRoleUpdates() throws Exception {
        execute("INSERT INTO system_tenant_package(id,menu_ids) SELECT 5,'[6859,' || string_agg('922',',') || ']' FROM generate_series(1,1022)");
        SQLException failure = assertThrows(SQLException.class, () -> execute(migration));
        assertEquals("22001", failure.getSQLState());
        execute("ROLLBACK");
        assertOriginalPermissions();
        assertEquals("4094", value("SELECT length(menu_ids) FROM system_tenant_package WHERE id=5"));
    }

    @Test
    void duplicateTaskEntryRejectsChangeAndRollsBack() throws Exception {
        execute("INSERT INTO system_menu(id,name,permission,type,parent_id,component) VALUES (7001,'重复任务','dataforge:data-push:query',2,5987,'dataforge/datapush/task/index')");
        SQLException failure = assertThrows(SQLException.class, () -> execute(migration));
        assertEquals("23505", failure.getSQLState());
        execute("ROLLBACK");
        assertOriginalPermissions();
    }

    private void assertOriginalPermissions() throws SQLException {
        assertEquals("true", value("SELECT visible::text FROM system_menu WHERE id=6859"));
        assertEquals("6859", value("SELECT parent_id FROM system_menu WHERE id=6861"));
        assertEquals("6859", value("SELECT menu_id FROM system_role_menu WHERE id=1"));
        assertEquals("[922,\"6859\",6861]", value("SELECT menu_ids FROM system_tenant_package WHERE id=1"));
        assertBusinessData();
    }

    private void assertBusinessData() throws SQLException {
        assertEquals("https://example.invalid", value("SELECT channel_json #>> '{http,url}' FROM dh_dataforge_push_config WHERE id=1"));
        assertEquals("2", value("SELECT count(*) FROM dh_dataforge_push_task WHERE push_config_id=1"));
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String value(String query) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next(), query);
            return result.getString(1);
        }
    }
}
