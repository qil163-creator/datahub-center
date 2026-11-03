package cn.iocoder.yudao.module.governance.service.retirement;

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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅在显式本机控制连接下验证自建数据库，禁止访问已有业务数据库。 */
@EnabledIfEnvironmentVariable(named = "DATAHUB_MIGRATION_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class GovernanceRetiredWorkbenchPostgreSqlTest {
    private static final String[] RETIRED_TABLES = {
            "dh_governance_standard_mapping_candidate", "dh_governance_standard_mapping_recommend_run",
            "dh_governance_standard_code_mapping", "dh_governance_standard_source_code_set",
            "dh_governance_lineage_relation", "dh_governance_lineage_node",
            "dh_governance_lineage_event_outbox", "dh_governance_lineage_apply_watermark",
            "dh_dataforge_access_lineage", "dh_dataforge_etl_lineage_delivery",
            "data_lineage_relation", "data_lineage_node", "data_blood_column", "data_blood_tb"
    };
    private static final String[] RETIRED_SEQUENCES = {
            "dh_governance_standard_mapping_candidate_seq", "dh_governance_standard_mapping_recommend_run_seq",
            "dh_governance_standard_code_mapping_seq", "dh_governance_standard_source_code_set_seq",
            "dh_governance_lineage_relation_seq", "dh_governance_lineage_node_seq",
            "dh_governance_lineage_event_outbox_seq", "dh_governance_lineage_apply_watermark_seq",
            "dh_dataforge_etl_lineage_delivery_seq", "seq_data_lineage_relation_id", "seq_data_lineage_node_id"
    };
    private static final String[] RETAINED_TABLES = {
            "dh_governance_standard_mapping", "dh_governance_standard_code_set", "dh_governance_standard_code_item",
            "dh_governance_meta_column", "dh_dataforge_etl_pipeline", "dh_dataforge_etl_record", "dh_governance_quality_task"
    };
    private String controlUrl;
    private String ownedDatabase;
    private Connection connection;
    private Path root;

    @BeforeAll
    void createOwnedDatabase() throws Exception {
        controlUrl = System.getenv("DATAHUB_MIGRATION_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres");
        ownedDatabase = "governance_retirement_" + UUID.randomUUID().toString().replace("-", "");
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
        sql("ROLLBACK; DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\", public");
        sql("""
                CREATE TABLE system_menu(id bigint PRIMARY KEY,name text,permission text DEFAULT '',type smallint,
                    parent_id bigint,path text,component text,deleted smallint DEFAULT 0);
                CREATE TABLE system_role_menu(id bigint PRIMARY KEY,role_id bigint,menu_id bigint,tenant_id bigint,deleted smallint DEFAULT 0);
                CREATE TABLE system_tenant_package(id bigint PRIMARY KEY,menu_ids varchar(4096),deleted smallint DEFAULT 0,
                    updater text DEFAULT 'original',update_time timestamp DEFAULT timestamp '2000-01-01');
                INSERT INTO system_menu(id,name,type,parent_id,path,component,permission) VALUES
                    (1,'数据治理',1,0,'/govern',NULL,''),(2,'数据标准',1,1,'standard',NULL,''),
                    (3,'数据血缘',1,1,'lineage',NULL,''),
                    (10,'智能落标工作台',2,2,'standard-mapping','governance/standardmapping/index','governance:standard-mapping:query'),
                    (20,'跨系统码值对照',2,2,'standard-code-mapping','governance/standardcodemapping/index','governance:standard-code-mapping:query'),
                    (30,'数据血缘',2,2,'lineage','governance/lineage/index','governance:lineage:query'),
                    (40,'标准码表',2,2,'standard-code-set','governance/standardcodeset/index',''),
                    (50,'资产地图',2,0,'map','asset/market/map/index','asset:data-asset:query');
                INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES
                    (11,'确认推荐',3,10,'governance:standard-mapping:confirm'),
                    (21,'导入码值映射',3,20,'governance:standard-code-mapping:import'),
                    (22,'停用码值映射',3,20,'governance:standard-code-mapping:invalidate'),
                    (31,'血缘重试',3,30,'governance:lineage:retry'),
                    (41,'查询标准码表',3,40,'governance:standard-code-set:query'),
                    (51,'资产血缘',3,50,'asset:data-asset:lineage'),
                    (52,'修改资产',3,50,'asset:data-asset:update'),
                    (53,'同步核查',3,50,'dataforge:access:resolve'),(54,'ETL运行',3,50,'dataforge:etl:run'),
                    (55,'手工落标',3,50,'governance:standard-element:mapping');
                INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) VALUES
                    (1,10,10,1,0),(2,10,11,1,0),(3,11,20,2,0),(4,11,21,2,1),
                    (5,12,30,1,0),(6,12,31,1,0),(7,13,51,1,0),
                    (8,13,41,1,0),(9,13,52,1,0),(10,13,53,1,0),(11,13,54,1,0),(12,13,55,1,0);
                INSERT INTO system_tenant_package(id,menu_ids,deleted) VALUES
                    (1,'[1,10,"11",20,21,30,"31",51,40,41,52,53,54,55,null,{"custom":"keep"},40]',0),
                    (2,'[40,"41",999]',0),(3,'["10",30]',1),(4,'[]',0);
                CREATE TABLE infra_job(id bigint PRIMARY KEY,handler_name text,params text);
                INSERT INTO infra_job VALUES(1,'accessLineageDeliveryJob','retired'),
                    (2,'governanceLineageOutboxRelayJob','retired'),(3,'modelingLineageReconcileJob','retired'),
                    (4,'etlPipelineJob','keep');
                CREATE TABLE dh_dataforge_etl_tool(id bigint PRIMARY KEY,code text);
                INSERT INTO dh_dataforge_etl_tool VALUES(1,'standard-map'),(2,'filter');
                CREATE TABLE dh_modeling_deploy_record(id bigint PRIMARY KEY,lineage_status text,lineage_error text,
                    lineage_reported_at timestamp,lineage_payload text,metadata_status text,metadata_payload text);
                INSERT INTO dh_modeling_deploy_record VALUES(1,'PENDING','旧血缘失败',CURRENT_TIMESTAMP,'旧血缘载荷','SUCCEEDED','保留的元数据登记');
                """);
        for (String table : RETAINED_TABLES) {
            sql("CREATE TABLE " + table + "(id bigint PRIMARY KEY,payload text); INSERT INTO " + table + " VALUES(1,'保留的共享业务数据')");
        }
        for (String table : RETIRED_TABLES) {
            sql("CREATE TABLE " + table + "(id bigint PRIMARY KEY,payload text); INSERT INTO " + table + " VALUES(1,'退役功能数据')");
        }
        for (String sequence : RETIRED_SEQUENCES) sql("CREATE SEQUENCE " + sequence);
        sql("ALTER TABLE dh_governance_standard_code_mapping ADD COLUMN source_set_id bigint REFERENCES dh_governance_standard_source_code_set(id)"
                + "; ALTER TABLE dh_governance_standard_code_mapping ADD COLUMN standard_set_id bigint REFERENCES dh_governance_standard_code_set(id)"
                + "; ALTER TABLE dh_governance_lineage_relation ADD COLUMN source_node_id bigint REFERENCES dh_governance_lineage_node(id)");
    }

    @Test
    void removesOnlyRetiredMenusTablesSequencesJobsAndToolWithoutChangingSharedData() throws Exception {
        String retainedGrants = scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row WHERE id>=8");

        sql(migration());

        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(3,10,11,20,21,22,30,31,51)")).isZero();
        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(1,2,40,41,50,52,53,54,55)")).isEqualTo(9);
        assertThat(scalar("SELECT jsonb_agg(to_jsonb(grant_row) ORDER BY id)::text FROM system_role_menu grant_row")).isEqualTo(retainedGrants);
        assertThat(scalar("SELECT menu_ids::jsonb='[1,40,41,52,53,54,55,null,{\"custom\":\"keep\"},40]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
        assertThat(scalar("SELECT menu_ids||'|'||updater FROM system_tenant_package WHERE id=2")).isEqualTo("[40,\"41\",999]|original");
        assertThat(scalar("SELECT menu_ids||'|'||deleted FROM system_tenant_package WHERE id=3")).isEqualTo("[]|1");
        assertThat(count("SELECT count(*) FROM infra_job WHERE handler_name='etlPipelineJob' AND params='keep'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM infra_job")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dh_dataforge_etl_tool WHERE code='filter'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dh_dataforge_etl_tool WHERE code='standard-map'")).isZero();
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_modeling_deploy_record' AND column_name LIKE 'lineage_%'")).isZero();
        assertThat(scalar("SELECT metadata_status||'|'||metadata_payload FROM dh_modeling_deploy_record WHERE id=1")).isEqualTo("SUCCEEDED|保留的元数据登记");
        for (String table : RETIRED_TABLES) {
            assertThat(scalar("SELECT to_regclass('" + table + "') IS NULL")).as(table).isEqualTo("t");
        }
        for (String sequence : RETIRED_SEQUENCES) assertThat(scalar("SELECT to_regclass('" + sequence + "') IS NULL")).as(sequence).isEqualTo("t");
        for (String table : RETAINED_TABLES) assertThat(scalar("SELECT payload FROM " + table + " WHERE id=1")).as(table).isEqualTo("保留的共享业务数据");
        String first = permissionSnapshot();
        sql(migration());
        assertThat(permissionSnapshot()).isEqualTo(first);
    }

    @Test
    void relocatedPermissionButtonsAndLongIdsAreRemovedWithoutGuessingFixedIds() throws Exception {
        sql("UPDATE system_menu SET id=9007199254740993 WHERE id=10"
                + "; UPDATE system_menu SET parent_id=9007199254740993 WHERE parent_id=10"
                + "; UPDATE system_role_menu SET menu_id=9007199254740993 WHERE menu_id=10"
                + "; INSERT INTO system_menu(id,name,type,parent_id,permission) VALUES(800,'移出页面的推荐权限',3,50,'governance:standard-mapping:recommend')"
                + "; UPDATE system_tenant_package SET menu_ids='[\"9007199254740993\",\"800\",40,null,999]' WHERE id=1");

        sql(migration());

        assertThat(count("SELECT count(*) FROM system_menu WHERE id IN(9007199254740993,800)")).isZero();
        assertThat(scalar("SELECT menu_ids::jsonb='[40,null,999]'::jsonb FROM system_tenant_package WHERE id=1")).isEqualTo("t");
    }

    @Test
    void unknownNestedPageRollsBackInsteadOfDeletingUnrelatedFeature() throws Exception {
        sql("INSERT INTO system_menu(id,name,type,parent_id,path,component,permission) VALUES(800,'未知功能',2,20,'custom','custom/index','custom:query')");
        String original = permissionSnapshot();

        assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("未知页面或目录");
        sql("ROLLBACK");

        assertThat(permissionSnapshot()).isEqualTo(original);
        assertThat(count("SELECT count(*) FROM dh_governance_standard_mapping_candidate")).isEqualTo(1);
    }

    @Test
    void unknownDatabaseDependencyStopsDropAndRollsBackPermissionChanges() throws Exception {
        sql("CREATE VIEW custom_recommendation_audit AS SELECT * FROM dh_governance_standard_mapping_candidate");
        String original = permissionSnapshot();

        assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("depend");
        sql("ROLLBACK");

        assertThat(permissionSnapshot()).isEqualTo(original);
        assertThat(count("SELECT count(*) FROM custom_recommendation_audit")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dh_governance_standard_code_mapping")).isEqualTo(1);
    }

    @Test
    void malformedPackageStateCannotPartiallyDeleteMenusOrData() throws Exception {
        sql("UPDATE system_tenant_package SET menu_ids='{}' WHERE id=1");
        String original = permissionSnapshot();

        assertThatThrownBy(() -> sql(migration())).isInstanceOf(SQLException.class).hasMessageContaining("JSON 数组");
        sql("ROLLBACK");

        assertThat(permissionSnapshot()).isEqualTo(original);
        assertThat(count("SELECT count(*) FROM dh_governance_lineage_relation")).isEqualTo(1);
    }

    @Test
    void currentFullBaselineContainsNoRetiredWorkbenchAndKeepsCoreBusinessTables() throws Exception {
        sql("DROP SCHEMA \"datahub-center\" CASCADE");
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        assertThat(baseline).startsWith("-- Included migrations through: 0180").endsWith(migration());
        String initialization = baseline.substring(0, baseline.indexOf("-- Migration: 0180 "));
        for (String table : RETIRED_TABLES) {
            assertThat(Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\"datahub-center\"\\.)?\"?"
                    + Pattern.quote(table) + "\"?\\s*\\(").matcher(initialization).find()).as(table).isFalse();
        }
        for (String sequence : RETIRED_SEQUENCES) {
            assertThat(Pattern.compile("(?i)CREATE\\s+SEQUENCE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\"datahub-center\"\\.)?\"?"
                    + Pattern.quote(sequence) + "\"?(?:\\s|;)").matcher(initialization).find()).as(sequence).isFalse();
        }

        sql(baseline);
        sql("SET search_path TO \"datahub-center\", public");

        assertThat(count("SELECT count(*) FROM system_menu WHERE trim(both '/' from coalesce(component,'')) IN('governance/standardmapping/index','governance/standardcodemapping/index','governance/lineage/index')"
                + " OR permission LIKE 'governance:standard-mapping:%' OR permission LIKE 'governance:standard-code-mapping:%' OR permission LIKE 'governance:lineage:%' OR permission='asset:data-asset:lineage'")).isZero();
        for (String table : RETIRED_TABLES) assertThat(scalar("SELECT to_regclass('" + table + "') IS NULL")).as(table).isEqualTo("t");
        for (String table : new String[]{"dh_governance_standard_mapping", "dh_governance_standard_code_set", "dh_governance_standard_code_item", "dh_dataforge_etl_pipeline"}) {
            assertThat(scalar("SELECT to_regclass('" + table + "') IS NOT NULL")).as(table).isEqualTo("t");
        }
        String first = permissionSnapshot();
        sql(migration());
        assertThat(permissionSnapshot()).isEqualTo(first);
    }

    private String migration() throws Exception {
        return Files.readString(root.resolve("sql/postgresql/migrations/0180_governance_remove-retired-workbenches.sql"));
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

    private String permissionSnapshot() throws SQLException {
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
