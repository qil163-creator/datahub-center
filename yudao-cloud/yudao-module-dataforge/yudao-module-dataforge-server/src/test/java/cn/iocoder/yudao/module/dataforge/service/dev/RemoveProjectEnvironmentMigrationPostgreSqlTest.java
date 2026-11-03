package cn.iocoder.yudao.module.dataforge.service.dev;

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

/** 用真实 PostgreSQL 重放 0034：去除项目/环境维度后结构、唯一索引、菜单逻辑删除与可重复执行必须成立。 */
@Testcontainers(disabledWithoutDocker = true)
class RemoveProjectEnvironmentMigrationPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0034_dataforge_remove-project-and-environment.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(MIGRATION))) root = root.getParent();
        assertNotNull(root, "repository root containing " + MIGRATION);
        return root;
    }

    @Test
    void migrationDropsProjectAndEnvironmentAndIsRepeatable() throws Exception {
        String migration = Files.readString(root().resolve(MIGRATION));
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = c.createStatement()) {
            createPreMigrationSchema(sql);
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id,project_id,environment) VALUES (1,'日汇总',1,10,'DEV')");
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id,project_id,environment) VALUES (2,'日汇总',2,10,'DEV')");
            sql.execute("INSERT INTO dh_dataforge_etl_pipeline(id,name,tenant_id,project_id,environment) VALUES (1,'清洗',1,10,'PROD')");

            sql.execute(migration);

            for (String table : new String[]{"dh_foundation_projects", "dh_foundation_project_member", "dh_dataforge_project_member",
                    "dh_dataforge_project_database", "dh_dataforge_project_audit"}) {
                assertEquals("0", one(sql, "SELECT count(*) FROM information_schema.tables WHERE table_schema='datahub-center' AND table_name='" + table + "'"), table);
            }
            assertEquals("0", one(sql, "SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'"
                    + " AND column_name IN ('project_id','environment','claimed_by','claimed_at','claim_reason')"
                    + " AND table_name LIKE 'dh_dataforge_%'"));
            assertEquals("0", one(sql, "SELECT count(*) FROM information_schema.sequences WHERE sequence_schema='datahub-center'"
                    + " AND sequence_name IN ('datahub_projects_seq','datahub_project_id_seq','datahub_project_member_id_seq',"
                    + "'dh_dataforge_project_member_seq','dh_dataforge_project_database_seq','dh_dataforge_project_audit_seq')"));
            assertTrue(one(sql, "SELECT indexdef FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='uk_dh_dataforge_dev_task_name'")
                    .contains("(tenant_id, name)"));
            assertTrue(one(sql, "SELECT indexdef FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='uk_dh_dataforge_etl_pipeline_name'")
                    .contains("(tenant_id, name)"));
            assertTrue(one(sql, "SELECT indexdef FROM pg_indexes WHERE schemaname='datahub-center' AND indexname='idx_dataforge_dev_dependency'")
                    .contains("(tenant_id, task_id, business_date, version_id)"));
            assertEquals("0", one(sql, "SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center'"
                    + " AND indexname IN ('uk_dataforge_dev_task_workspace_name','uk_dataforge_etl_pipeline_workspace_name')"));

            // 同租户重名被唯一索引阻断；跨租户与已逻辑删除的记录不受影响
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id) VALUES (3,'日汇总',1)"));
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id,deleted) VALUES (4,'日汇总',1,1)");
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id) VALUES (5,'日汇总',3)");

            assertEquals("7", one(sql, "SELECT count(*) FROM system_menu WHERE deleted = 1"));
            assertEquals("0", one(sql, "SELECT count(*) FROM system_menu WHERE deleted = 0 AND (permission LIKE 'dataforge:projects:%' OR component = 'dataforge/projects/index')"));
            assertEquals("1", one(sql, "SELECT count(*) FROM system_menu WHERE deleted = 0"));
            assertEquals("7", one(sql, "SELECT count(*) FROM system_role_menu WHERE deleted = 1"));
            assertEquals("1", one(sql, "SELECT count(*) FROM system_role_menu WHERE deleted = 0"));

            // 可重复执行：第二次不报错且不改变结果
            sql.execute(migration);
            assertEquals("7", one(sql, "SELECT count(*) FROM system_menu WHERE deleted = 1"));
            assertEquals("3", one(sql, "SELECT count(*) FROM dh_dataforge_dev_task WHERE deleted = 0"));
        }
    }

    @Test
    void duplicateNamesAcrossFormerEnvironmentsAbortTheWholeMigration() throws Exception {
        String migration = Files.readString(root().resolve(MIGRATION));
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sql = c.createStatement()) {
            createPreMigrationSchema(sql);
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id,project_id,environment) VALUES (1,'日汇总',1,10,'DEV')");
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,tenant_id,project_id,environment) VALUES (2,'日汇总',1,10,'TEST')");

            assertThrows(SQLException.class, () -> sql.execute(migration));
            // 脚本内显式 BEGIN 失败后连接仍处于中止事务，显式回滚以便继续核对
            sql.execute("ROLLBACK");

            // 事务整体回滚：项目表与列都还在，等待人工处理重名后重试
            assertEquals("1", one(sql, "SELECT count(*) FROM information_schema.tables WHERE table_schema='datahub-center' AND table_name='dh_foundation_projects'"));
            assertEquals("1", one(sql, "SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_dataforge_dev_task' AND column_name='project_id'"));
        }
    }

    /** 与 0034 相关的最小前置结构：只建迁移会触碰的表、列、索引、序列与菜单。 */
    private static void createPreMigrationSchema(Statement sql) throws SQLException {
        sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        sql.execute("CREATE SCHEMA \"datahub-center\"");
        sql.execute("SET search_path TO \"datahub-center\", public");
        sql.execute("CREATE SEQUENCE datahub_projects_seq");
        sql.execute("CREATE SEQUENCE datahub_project_id_seq");
        sql.execute("CREATE SEQUENCE datahub_project_member_id_seq");
        sql.execute("CREATE TABLE dh_foundation_projects (id bigint PRIMARY KEY DEFAULT nextval('datahub_projects_seq'), project_name varchar(128), tenant_id bigint, deleted smallint DEFAULT 0)");
        sql.execute("CREATE TABLE dh_foundation_project_member (id bigint PRIMARY KEY DEFAULT nextval('datahub_project_member_id_seq'), project_id bigint, tenant_id bigint, deleted smallint DEFAULT 0)");
        for (String table : new String[]{"member", "database", "audit"}) {
            sql.execute("CREATE SEQUENCE dh_dataforge_project_" + table + "_seq");
            sql.execute("CREATE TABLE dh_dataforge_project_" + table + " (id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_project_" + table + "_seq'), project_id bigint NOT NULL, tenant_id bigint NOT NULL, deleted smallint DEFAULT 0)");
        }
        sql.execute("CREATE INDEX uk_dataforge_project_member_active ON dh_dataforge_project_member(tenant_id, project_id)");
        sql.execute("CREATE INDEX uk_dataforge_project_database_active ON dh_dataforge_project_database(tenant_id, project_id)");
        sql.execute("CREATE INDEX idx_dataforge_project_audit_scope ON dh_dataforge_project_audit(tenant_id, project_id)");

        sql.execute("CREATE TABLE dh_dataforge_dev_task (id bigint PRIMARY KEY, name varchar(100) NOT NULL, tenant_id bigint NOT NULL, deleted smallint NOT NULL DEFAULT 0,"
                + " project_id bigint, environment varchar(8) NOT NULL DEFAULT 'DEV', claimed_by bigint, claimed_at timestamp(6), claim_reason varchar(1000),"
                + " dependency_task_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb, status int DEFAULT 0, schedule_status int DEFAULT 0)");
        sql.execute("CREATE UNIQUE INDEX uk_dataforge_dev_task_workspace_name ON dh_dataforge_dev_task(tenant_id, project_id, environment, name) WHERE deleted = 0 AND project_id IS NOT NULL");
        sql.execute("CREATE TABLE dh_dataforge_dev_dir (id bigint PRIMARY KEY, tenant_id bigint, deleted smallint DEFAULT 0, project_id bigint, claimed_by bigint, claimed_at timestamp(6), claim_reason varchar(1000))");
        sql.execute("CREATE TABLE dh_dataforge_dev_task_version (id bigint PRIMARY KEY, task_id bigint, tenant_id bigint, deleted smallint DEFAULT 0, project_id bigint, environment varchar(8))");
        sql.execute("CREATE TABLE dh_dataforge_dev_record (id bigint PRIMARY KEY, task_id bigint, tenant_id bigint, deleted smallint DEFAULT 0, project_id bigint, environment varchar(8), business_date date, version_id bigint)");
        sql.execute("CREATE INDEX idx_dataforge_dev_dependency ON dh_dataforge_dev_record(tenant_id, project_id, environment, task_id, business_date, version_id)");
        sql.execute("CREATE TABLE dh_dataforge_etl_pipeline (id bigint PRIMARY KEY, name varchar(100) NOT NULL, tenant_id bigint NOT NULL, deleted smallint NOT NULL DEFAULT 0, project_id bigint, environment varchar(8) NOT NULL DEFAULT 'DEV')");
        sql.execute("CREATE UNIQUE INDEX uk_dataforge_etl_pipeline_workspace_name ON dh_dataforge_etl_pipeline(tenant_id, project_id, environment, name) WHERE deleted = 0 AND project_id IS NOT NULL");
        sql.execute("CREATE TABLE dh_dataforge_etl_record (id bigint PRIMARY KEY, pipeline_id bigint, tenant_id bigint, deleted smallint DEFAULT 0, project_id bigint, environment varchar(8) NOT NULL DEFAULT 'DEV')");
        for (String table : new String[]{"dh_dataforge_database", "dh_dataforge_access", "dh_dataforge_access_task", "dh_dataforge_access_task_detail",
                "dh_dataforge_log_job", "dh_dataforge_message_job"}) {
            sql.execute("CREATE TABLE " + table + " (id bigint PRIMARY KEY, tenant_id bigint, deleted smallint DEFAULT 0, project_id bigint)");
        }

        sql.execute("CREATE TABLE system_menu (id bigint PRIMARY KEY, name varchar(50), permission varchar(100) DEFAULT '', component varchar(255), deleted smallint DEFAULT 0, update_time timestamp(6) DEFAULT now())");
        sql.execute("INSERT INTO system_menu(id,name,permission,component) VALUES (6013,'项目管理','','dataforge/projects/index'),(6014,'查询','dataforge:projects:query',''),"
                + "(6015,'创建','dataforge:projects:create',''),(6016,'更新','dataforge:projects:update',''),(6017,'删除','dataforge:projects:delete',''),"
                + "(6018,'导出','dataforge:projects:export',''),(7001,'项目协作授权','dataforge:projects:authorize',''),(6821,'开发工作台','dataforge:dev-task:query','dataforge/dev/index')");
        sql.execute("CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint, menu_id bigint, deleted smallint DEFAULT 0, update_time timestamp(6) DEFAULT now())");
        sql.execute("INSERT INTO system_role_menu(id,role_id,menu_id) VALUES (1,1,6013),(2,1,6014),(3,1,6015),(4,1,6016),(5,1,6017),(6,1,6018),(7,1,7001),(8,1,6821)");
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            assertTrue(rs.next(), query);
            return rs.getString(1);
        }
    }

}
