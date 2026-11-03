package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 0110 结构化字段迁移：可重放、列类型、审计纳入新列，以及基线区块与迁移文件一致。 */
@Testcontainers(disabledWithoutDocker = true)
class ModelingStructuredFieldsMigrationPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private Connection connection;

    @BeforeEach
    void setup() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ModelingMigrationTestSupport.resetBaselineTables(connection);
        ModelingMigrationTestSupport.migrate(connection);
        execute("""
                INSERT INTO "datahub-center".dh_modeling_biz_category
                  (id,parent_id,name,code,status,tenant_id,creator)
                VALUES (9007199254740993,0,'政务服务','GOV',0,9007199254740997,'9007199254740999')
                """);
        execute("""
                INSERT INTO "datahub-center".dh_modeling_business_process
                  (id,biz_category_id,name,code,activity_definition,canonical_grain_definition,status,tenant_id,creator)
                VALUES (9007199254741011,9007199254740993,'办件受理','ACCEPT','受理申请','一个办件的一次受理决定',0,
                        9007199254740997,'9007199254740999')
                """);
    }

    @AfterEach
    void close() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void migrationReplaysAndAddsTypedCommentedColumns() throws Exception {
        execute(Files.readString(ModelingMigrationTestSupport.repoFile(
                ModelingMigrationTestSupport.STRUCTURED_FIELDS_MIGRATION)));

        assertThat(columnType("dh_modeling_business_process", "participant_roles")).isEqualTo("jsonb");
        assertThat(columnType("dh_modeling_business_process", "key_events")).isEqualTo("jsonb");
        assertThat(columnType("dh_modeling_biz_category", "responsibility_scope")).isEqualTo("character varying");
        assertThat(scalar("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema='datahub-center' AND table_name='dh_modeling_biz_category'
                  AND column_name='responsibility_scope'
                """)).isEqualTo("2000");
        assertThat(scalar("""
                SELECT col_description('"datahub-center".dh_modeling_business_process'::regclass, a.attnum)
                FROM pg_attribute a WHERE a.attrelid='"datahub-center".dh_modeling_business_process'::regclass
                  AND a.attname='key_events'
                """)).contains("关键事件");
    }

    @Test
    void structuredFieldChangeIsAuditedWithStringBeforeAfterValues() throws SQLException {
        execute("""
                UPDATE "datahub-center".dh_modeling_business_process
                SET participant_roles='[{"role":"受理人员","department":"综合受理科"}]'::jsonb,
                    updater='9007199254741003'
                WHERE id=9007199254741011
                """);

        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("""
                SELECT kind, action, object_id, actor_id,
                       changes->'participant_roles'->>'before' AS before_value,
                       changes->'participant_roles'->>'after' AS after_value,
                       jsonb_typeof(changes->'participant_roles'->'after') AS value_type
                FROM "datahub-center".dh_modeling_planning_change
                WHERE kind='BUSINESS_PROCESS' AND action='UPDATE'
                """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("object_id")).isEqualTo("9007199254741011");
            assertThat(row.getString("actor_id")).isEqualTo("9007199254741003");
            assertThat(row.getString("before_value")).isNull();
            assertThat(row.getString("after_value")).contains("受理人员", "综合受理科");
            assertThat(row.getString("value_type")).isEqualTo("string");
            assertThat(row.next()).isFalse();
        }
    }

    @Test
    void responsibilityScopeChangeIsAuditedForBizCategory() throws SQLException {
        execute("""
                UPDATE "datahub-center".dh_modeling_biz_category
                SET responsibility_scope='负责政务服务事项的受理与办结', updater='9007199254741005'
                WHERE id=9007199254740993
                """);

        assertThat(scalar("""
                SELECT changes->'responsibility_scope'->>'after' FROM "datahub-center".dh_modeling_planning_change
                WHERE kind='BIZ_CATEGORY' AND action='UPDATE'
                """)).isEqualTo("负责政务服务事项的受理与办结");
    }

    @Test
    void baselineBlockMatchesMigrationAndBuildsTheSameColumns() throws Exception {
        String migration = Files.readString(ModelingMigrationTestSupport.repoFile(
                ModelingMigrationTestSupport.STRUCTURED_FIELDS_MIGRATION)).replace("\r\n", "\n").strip();
        assertThat(ModelingMigrationTestSupport.baselineBlock("-- Migration: 0110 ").strip()).isEqualTo(migration);

        ModelingMigrationTestSupport.resetBaselineTables(connection);
        ModelingMigrationTestSupport.applyBaselineExtension(connection);

        assertThat(columnType("dh_modeling_business_process", "participant_roles")).isEqualTo("jsonb");
        assertThat(columnType("dh_modeling_business_process", "key_events")).isEqualTo("jsonb");
        assertThat(columnType("dh_modeling_biz_category", "responsibility_scope")).isEqualTo("character varying");
    }

    private String columnType(String table, String column) throws SQLException {
        return scalar("SELECT data_type FROM information_schema.columns WHERE table_schema='datahub-center'"
                + " AND table_name='" + table + "' AND column_name='" + column + "'");
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
