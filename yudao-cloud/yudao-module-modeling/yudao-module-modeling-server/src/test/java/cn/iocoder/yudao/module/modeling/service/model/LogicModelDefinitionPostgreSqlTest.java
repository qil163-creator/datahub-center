package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 一次性本机 PostgreSQL 验证新增字段、迁移重放、已有行保留及新库片段一致。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogicModelDefinitionPostgreSqlTest {
    private static final String MIGRATION = "sql/postgresql/migrations/0212_modeling_logic-table-definition.sql";
    private Connection connection;

    @BeforeAll void connect() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+$"),
                "需要本次一次性本机 PostgreSQL 实例");
        connection = DriverManager.getConnection(url, System.getProperty("datahub.test.postgres.user", "modeling_test"), "");
    }

    @AfterAll void close() throws Exception { if (connection != null) connection.close(); }

    @Test void replayPreservesRowsAndNewMetadata() throws Exception {
        execute("CREATE SCHEMA IF NOT EXISTS \"datahub-center\"; SET search_path TO \"datahub-center\"; "
                + "CREATE TABLE dh_modeling_logic_model (id bigint PRIMARY KEY,table_type varchar(32),entity_id bigint,data_timeliness varchar(8),"
                + "status varchar(16) DEFAULT 'REVISING',deleted smallint DEFAULT 0,data_domain_id bigint,subject_domain_id bigint,statistics_str varchar(500),latest_version integer DEFAULT 1); "
                + "CREATE TABLE dh_modeling_logic_model_column (id bigint PRIMARY KEY,model_id bigint,field_name varchar(255),field_uid varchar(128),deleted smallint DEFAULT 0); "
                + "CREATE TABLE dh_modeling_business_entity(id bigint PRIMARY KEY,data_domain_id bigint,subject_domain_id bigint,name varchar(255),code varchar(128),entity_type varchar(32),entity_subtype varchar(32),status integer,deleted smallint DEFAULT 0); "
                + "CREATE TABLE dh_modeling_logic_model_version(id bigint PRIMARY KEY,model_id bigint,version integer,status varchar(32),fields jsonb,deleted smallint DEFAULT 0); "
                + "INSERT INTO dh_modeling_business_entity VALUES(7,1,2,'测站','STATION','OBJECT','NORMAL',0,0); "
                + "INSERT INTO dh_modeling_logic_model(id,table_type,data_domain_id,subject_domain_id,statistics_str) VALUES (1,'SUMMARY',1,2,'测站'); "
                + "INSERT INTO dh_modeling_logic_model_column(id,model_id,field_name,field_uid) VALUES (2,1,'station_id','existing-station'); "
                + "INSERT INTO dh_modeling_logic_model_version VALUES (6,1,1,'REVISING','[{\"fieldUid\":\"existing-station\",\"fieldName\":\"station_id\"}]',0)");
        String sql = Files.readString(ModelingMigrationTestSupport.repoFile(MIGRATION));
        execute(sql);
        try (var statement = connection.createStatement(); var row = statement.executeQuery(
                "SELECT m.grain_entity_ids,c.field_category,v.fields->0->>'fieldUid' AS uid,v.fields->0->>'dimensionName' AS dimension "
                        + "FROM dh_modeling_logic_model m JOIN dh_modeling_logic_model_column c ON c.model_id=m.id "
                        + "JOIN dh_modeling_logic_model_version v ON v.model_id=m.id WHERE m.id=1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("grain_entity_ids")).isEqualTo("7");
            assertThat(row.getString("field_category")).isEqualTo("GRAIN");
            assertThat(row.getString("uid")).isEqualTo("existing-station");
            assertThat(row.getString("dimension")).isEqualTo("测站");
        }
        execute("UPDATE dh_modeling_logic_model SET grain_entity_ids='9007199254740993,7'; "
                + "UPDATE dh_modeling_logic_model_column SET field_category='DIMENSION',dimension_id=9007199254740993,"
                + "dimension_name='测站',data_classification_id=7,data_classification_name='公开水情',data_grade='PUBLIC',"
                + "code_table_id=8,code_table_name='测站类型',unique_flag=1,indicator_id=9,atomic_indicator_name='降雨量',"
                + "business_condition='有效测站',statistics_period='DAY',source_model_name='测站发布快照' WHERE id=2");
        execute(sql);
        try (var statement = connection.createStatement(); var row = statement.executeQuery(
                "SELECT c.*,m.grain_entity_ids FROM dh_modeling_logic_model_column c CROSS JOIN dh_modeling_logic_model m")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong("dimension_id")).isEqualTo(9007199254740993L);
            assertThat(row.getString("grain_entity_ids")).isEqualTo("9007199254740993,7");
            assertThat(row.getString("field_category")).isEqualTo("DIMENSION");
            assertThat(row.getInt("unique_flag")).isEqualTo(1);
            assertThat(row.getString("source_model_name")).isEqualTo("测站发布快照");
            assertThat(row.getString("business_condition")).isEqualTo("有效测站");
            assertThat(row.next()).isFalse();
        }
        execute("INSERT INTO dh_modeling_logic_model(id,table_type,entity_id,data_timeliness) VALUES (3,'DIMENSION',7,'1')");
        var duplicate = assertThrows(java.sql.SQLException.class, () -> execute(
                "INSERT INTO dh_modeling_logic_model(id,table_type,entity_id,data_timeliness) VALUES (4,'DIMENSION',7,'1')"));
        assertThat(duplicate.getSQLState()).isEqualTo("23505");
        execute("UPDATE dh_modeling_logic_model SET deleted=1 WHERE id=3; "
                + "INSERT INTO dh_modeling_logic_model(id,table_type,entity_id,data_timeliness) VALUES (5,'DIMENSION',7,'1')");
        assertThat(ModelingMigrationTestSupport.baselineBlock("-- Migration: 0212 ").strip())
                .isEqualTo(sql.replace("\r\n", "\n").strip());
    }

    private void execute(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }
}
