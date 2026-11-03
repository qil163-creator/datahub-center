package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 PostgreSQL 验证单目标迁移重放、权限范围和历史证据保留。 */
@Timeout(60)
class ModelSingleTargetMigrationPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0120_modeling_single-target.sql";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private Connection connection;
    private String schema;

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = System.getenv("MODELING_TEST_PG_URL");
        if (jdbcUrl != null) {
            assertTrue(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:"), "仅允许任务专属本机 PostgreSQL");
            username = "modeling_test";
            password = "";
        } else {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "无 Docker 或隔离 PostgreSQL");
            POSTGRES.start();
            jdbcUrl = POSTGRES.getJdbcUrl();
            username = POSTGRES.getUsername();
            password = POSTGRES.getPassword();
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @BeforeEach
    void createSchema() throws SQLException {
        schema = "modeling_single_target_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(jdbcUrl, username, password);
        execute("CREATE SCHEMA \"" + schema + "\"; SET search_path TO \"" + schema + "\", public");
    }

    @AfterEach
    void dropSchema() throws SQLException {
        if (connection == null) return;
        try {
            assertTrue(schema.matches("modeling_single_target_[a-f0-9]{32}"));
            execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        } finally {
            connection.close();
        }
    }

    @Test
    void replayDisablesOnlyEnvironmentButtonsAndPreservesGrantsAndHistory() throws Exception {
        execute("""
                CREATE TABLE system_menu (
                    id bigint PRIMARY KEY, permission varchar(100), type integer, deleted smallint,
                    status integer, visible boolean, updater varchar(64), update_time timestamp
                );
                INSERT INTO system_menu VALUES
                    (1,'modeling:workspace:environment',3,0,0,true,'original','2026-10-07 10:00:00'),
                    (2,'modeling:workspace:approval',3,0,0,true,'original','2026-10-07 10:00:00'),
                    (3,'modeling:workspace:collaboration',3,0,0,true,'original','2026-10-07 10:00:00'),
                    (4,'modeling:workspace:inbox',3,0,0,true,'original','2026-10-07 10:00:00'),
                    (5,'modeling:workspace:environment',2,0,0,true,'original','2026-10-07 10:00:00'),
                    (6,'modeling:workspace:environment',3,1,0,true,'original','2026-10-07 10:00:00');
                CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint, menu_id bigint);
                INSERT INTO system_role_menu VALUES (11,21,1),(12,21,2);
                CREATE TABLE dh_modeling_environment_target (
                    id bigint PRIMARY KEY, tenant_id bigint, model_id bigint, code varchar(32),
                    database_id bigint, table_name varchar(128), enabled boolean
                );
                INSERT INTO dh_modeling_environment_target VALUES (31,7,41,'LEGACY',51,'dwd_resident',true);
                CREATE TABLE dh_modeling_deploy_record (
                    id bigint PRIMARY KEY, tenant_id bigint, model_id bigint,
                    target_id bigint, target_snapshot jsonb, status varchar(32)
                );
                INSERT INTO dh_modeling_deploy_record VALUES
                    (61,7,41,31,'{"targetId":31,"databaseId":51,"tableName":"dwd_resident"}','SUCCEEDED');
                """);
        List<String> otherMenus = rows("SELECT row_to_json(m)::text FROM system_menu m WHERE id<>1 ORDER BY id");
        List<String> grants = rows("SELECT row_to_json(m)::text FROM system_role_menu m ORDER BY id");
        List<String> targets = rows("SELECT row_to_json(m)::text FROM dh_modeling_environment_target m ORDER BY id");
        List<String> records = rows("SELECT row_to_json(m)::text FROM dh_modeling_deploy_record m ORDER BY id");

        for (int execution = 0; execution < 2; execution++) {
            apply(MIGRATION);
            assertEquals(List.of("1:false"), rows("SELECT status::text || ':' || visible::text FROM system_menu WHERE id=1"));
            assertEquals(otherMenus, rows("SELECT row_to_json(m)::text FROM system_menu m WHERE id<>1 ORDER BY id"));
            assertEquals(grants, rows("SELECT row_to_json(m)::text FROM system_role_menu m ORDER BY id"));
            assertEquals(targets, rows("SELECT row_to_json(m)::text FROM dh_modeling_environment_target m ORDER BY id"));
            assertEquals(records, rows("SELECT row_to_json(m)::text FROM dh_modeling_deploy_record m ORDER BY id"));
        }
    }

    @Test
    void freshBaselineRetiresEnvironmentConfigurationAndPreservesDeployEvidence() throws Exception {
        apply("sql/postgresql/datahub-center.sql");
        assertEquals(0, count("SELECT count(*) FROM system_menu WHERE permission='modeling:workspace:environment'"));
        assertEquals(0, count("SELECT count(*) FROM system_menu WHERE permission='modeling:workspace:environment'"
                + " AND type=3 AND deleted=0 AND status=0"));
        // 当前新库不再启用已退役的审批、协作、收件箱；0120 增量保真由上一用例独立证明。
        assertEquals(0, count("SELECT count(*) FROM system_menu WHERE permission IN"
                + " ('modeling:workspace:approval','modeling:workspace:collaboration','modeling:workspace:inbox')"
                + " AND type=3 AND deleted=0 AND status=0"));
        assertEquals(List.of("false:true"), rows("SELECT (to_regclass('dh_modeling_environment_target') IS NOT NULL)::text"
                + " || ':' || (to_regclass('dh_modeling_deploy_record') IS NOT NULL)::text"));
        String migration = Files.readString(ModelingMigrationTestSupport.repoFile(MIGRATION)).replace("\r\n", "\n").strip();
        assertEquals(migration, ModelingMigrationTestSupport.baselineBlock("-- Migration: 0120 ").strip());
    }

    private void apply(String relative) throws Exception {
        String sql = Files.readString(ModelingMigrationTestSupport.repoFile(relative))
                .replace("\"datahub-center\"", "\"" + schema + "\"")
                .replace("'datahub-center'", "'" + schema + "'");
        execute(sql);
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private int count(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
