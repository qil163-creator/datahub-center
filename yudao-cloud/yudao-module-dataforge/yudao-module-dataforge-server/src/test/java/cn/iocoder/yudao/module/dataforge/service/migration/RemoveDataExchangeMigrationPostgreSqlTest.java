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

/** 在本机真实 PostgreSQL 的独立随机模式中验证退役迁移，不触及平台业务模式。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
@Timeout(45)
class RemoveDataExchangeMigrationPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0160_dataforge_remove-data-exchange.sql";
    private final String schema = "retire_exchange_" + UUID.randomUUID().toString().replace("-", "");
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
        assertNotNull(root, "应从仓库读取当前退役迁移");
        migration = Files.readString(root.resolve(MIGRATION)).replace("\"datahub-center\"", "\"" + schema + "\"");
        execute("""
                CREATE TABLE system_menu (id bigint PRIMARY KEY, name text, permission text DEFAULT '',
                    type integer, parent_id bigint DEFAULT 0, path text DEFAULT '', component text,
                    component_name text, deleted smallint DEFAULT 0);
                CREATE TABLE system_role_menu (id bigint PRIMARY KEY, menu_id bigint, deleted smallint DEFAULT 0);
                CREATE TABLE system_tenant_package (id bigint PRIMARY KEY, menu_ids varchar(4096),
                    updater text DEFAULT '原用户', update_time timestamp DEFAULT CURRENT_TIMESTAMP);
                INSERT INTO system_menu (id,name,permission,type,parent_id,path,component,component_name,deleted) VALUES
                    (910,'数据交换','',1,0,'exchange',NULL,NULL,0),
                    (911,'交换契约','dataforge:exchange-contract:query',2,910,'contract',NULL,NULL,0),
                    (912,'交换契约新增','dataforge:exchange-contract:create',3,911,'',NULL,NULL,0),
                    (919,'无权限按钮','',3,911,'',NULL,NULL,0),
                    (920,'已删除交付页','',2,910,'delivery','/dataforge/exchange/delivery/index/',NULL,1),
                    (921,'交换任务','',2,910,'job',NULL,'DataforgeExchangeJob',0),
                    (922,'数据同步','dataforge:data-access:query',2,0,'access','dataforge/access/index',NULL,0),
                    (923,'数据推送','dataforge:data-push:query',2,0,'push','dataforge/datapush/index',NULL,0);
                INSERT INTO system_role_menu VALUES (1,910,0),(2,911,0),(3,912,0),(4,919,0),(5,920,1),(6,921,0),
                    (910,922,0),(8,923,0);
                INSERT INTO system_tenant_package (id,menu_ids) VALUES
                    (1,'[922,"911",912,923,"922",910,919,920,921]'),(2,'[]'),(3,'["919"]'),(4,'[922,"923"]');
                CREATE TABLE infra_job (id bigint PRIMARY KEY, handler_name text);
                CREATE TABLE infra_job_log (id bigint PRIMARY KEY, handler_name text);
                INSERT INTO infra_job VALUES (1,'dataforgeExchangeJob'),(2,'dataAccessSyncJob'),(3,'dataPushSyncJob');
                INSERT INTO infra_job_log VALUES (1,'dataforgeExchangeJob'),(2,'dataAccessSyncJob'),(3,'dataPushSyncJob');
                CREATE TABLE dh_dataforge_access (id bigint PRIMARY KEY, name text);
                INSERT INTO dh_dataforge_access VALUES (1,'保留同步任务');
                CREATE TABLE dh_dataforge_push_config (id bigint PRIMARY KEY, channel_json jsonb);
                INSERT INTO dh_dataforge_push_config VALUES (1,'{"rabbit":{"exchange":"保留交换机"}}');
                """);
        for (String suffix : new String[]{"contract", "job", "delivery"}) {
            String table = "dh_dataforge_exchange_" + suffix;
            execute("CREATE SEQUENCE " + table + "_seq");
            execute("CREATE TABLE " + table + " (id bigint PRIMARY KEY DEFAULT nextval('" + table + "_seq'), data text)");
            execute("INSERT INTO " + table + " (data) VALUES ('退役数据')");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection != null) {
            try (Connection owned = connection) {
                execute("ROLLBACK");
                assertTrue(schema.matches("retire_exchange_[a-f0-9]{32}"));
                execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void removesOnlyExchangeObjectsAndGrantsAndReplaysWithoutLegacyJobs() throws Exception {
        execute(migration);
        assertEquals("0", value("SELECT count(*) FROM pg_class WHERE relnamespace='" + schema
                + "'::regnamespace AND relname LIKE 'dh_dataforge_exchange_%'"));
        assertEquals("922,923", value("SELECT string_agg(id::text,',' ORDER BY id) FROM system_menu"));
        // 角色菜单的主键恰好等于退役菜单编号时，仍须按 menu_id 保留该条授权。
        assertEquals("910,8", value("SELECT string_agg(id::text,',' ORDER BY menu_id) FROM system_role_menu"));
        assertEquals("[922,923,\"922\"]", value("SELECT menu_ids FROM system_tenant_package WHERE id=1"));
        assertEquals("[]", value("SELECT menu_ids FROM system_tenant_package WHERE id=3"));
        assertEquals("原用户", value("SELECT updater FROM system_tenant_package WHERE id=2"));
        assertEquals("[922,\"923\"]", value("SELECT menu_ids FROM system_tenant_package WHERE id=4"));
        assertEquals("2", value("SELECT count(*) FROM infra_job"));
        assertEquals("2", value("SELECT count(*) FROM infra_job_log"));
        assertEquals("保留同步任务", value("SELECT name FROM dh_dataforge_access WHERE id=1"));
        assertEquals("保留交换机", value("SELECT channel_json #>> '{rabbit,exchange}' FROM dh_dataforge_push_config WHERE id=1"));
        String packageAfter = value("SELECT menu_ids FROM system_tenant_package WHERE id=1");
        execute("DROP TABLE infra_job_log,infra_job");
        execute(migration);
        assertEquals(packageAfter, value("SELECT menu_ids FROM system_tenant_package WHERE id=1"));
        assertEquals("2", value("SELECT count(*) FROM system_role_menu"));
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_access"));
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_push_config"));
    }

    @Test
    void unrelatedChildMenuRejectsRemovalAndRollsBack() throws Exception {
        execute("INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES (924,'其他功能','other:query',2,911)");
        SQLException failure = assertThrows(SQLException.class, () -> execute(migration));
        assertTrue(failure.getMessage().contains("其他功能子项"));
        execute("ROLLBACK");
        assertUnchanged();
        assertEquals("1", value("SELECT count(*) FROM system_menu WHERE id=924"));
    }

    @Test
    void externalForeignKeyRejectsDropAndRollsBackPermissionCleanup() throws Exception {
        execute("CREATE TABLE other_business (id bigint PRIMARY KEY, contract_id bigint REFERENCES dh_dataforge_exchange_contract(id))");
        execute("INSERT INTO other_business VALUES (1,1)");
        SQLException failure = assertThrows(SQLException.class, () -> execute(migration));
        assertEquals("2BP01", failure.getSQLState());
        execute("ROLLBACK");
        assertUnchanged();
        assertEquals("1", value("SELECT count(*) FROM other_business"));
        // 失败发生在删除交付表之后，整笔事务必须恢复先前已删除的对象。
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_exchange_delivery"));
    }

    private void assertUnchanged() throws SQLException {
        assertEquals("1", value("SELECT count(*) FROM dh_dataforge_exchange_contract"));
        assertEquals("8", value("SELECT count(*) FROM system_role_menu"));
        assertEquals("[922,\"911\",912,923,\"922\",910,919,920,921]",
                value("SELECT menu_ids FROM system_tenant_package WHERE id=1"));
        assertEquals("3", value("SELECT count(*) FROM infra_job"));
        assertEquals("3", value("SELECT count(*) FROM infra_job_log"));
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
