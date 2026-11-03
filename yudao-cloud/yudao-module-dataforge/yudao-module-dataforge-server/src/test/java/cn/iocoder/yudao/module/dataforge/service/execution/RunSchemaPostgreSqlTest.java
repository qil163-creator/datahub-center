package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 显式测试连接上的真实 PostgreSQL；每个用例只操作自己创建的随机 schema。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class RunSchemaPostgreSqlTest {
    private Connection db;
    private String schema;
    private Path root;

    @BeforeEach
    void setup() throws Exception {
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) {
            root = root.getParent();
            assertNotNull(root, "repository root");
        }
        db = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        schema = "run_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        // 只复用当前基线四张表的 DDL；不导入平台种子数据、账号或日志。
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        for (String table : List.of("dh_dataforge_access", "dh_dataforge_access_task",
                "dh_dataforge_etl_pipeline", "dh_dataforge_etl_record")) {
            var match = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table
                    + "\" \\(.*?\\n\\)\\s*;", Pattern.DOTALL).matcher(baseline);
            assertTrue(match.find(), table);
            String ddl = match.group().replace("\"datahub-center\".", "")
                    .replaceAll(" DEFAULT nextval\\([^\\n]*?::regclass\\)", "");
            execute(ddl);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (db != null) {
            try {
                if (schema != null && schema.matches("run_test_[a-f0-9]{32}")) {
                    execute("ROLLBACK");
                    execute("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally {
                db.close();
            }
        }
    }

    @Test
    void replayableMigrationSeparatesWaitingFromExclusiveSlotAndTenantIdentity() throws Exception {
        migrate();
        migrate();
        insertEtl(1, 100, 1, "manual-a");
        insertEtl(2, 100, 1, "manual-b");
        insertEtl(3, 100, 2, "manual-a");
        assertEquals(1, update("UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=1"));
        assertSqlState("23505", "UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=2");
        assertEquals(1, update("UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=3"));
        execute("UPDATE dh_dataforge_etl_record SET run_status=6,deleted=1 WHERE id=1");
        assertSqlState("23505", "UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=2");
        assertSqlState("23505", insertEtlSql(4, 101, 1, "manual-a"));
        execute("UPDATE dh_dataforge_etl_record SET occupies_slot=false WHERE id=1");
        assertEquals(1, update("UPDATE dh_dataforge_etl_record SET occupies_slot=true WHERE id=2"));
    }

    @Test
    void accessUsesItsOwnStatusNumbersAndRetainsUnknownOccupancy() throws Exception {
        migrate();
        execute("INSERT INTO dh_dataforge_access_task(id,data_access_id,run_status,protocol_version,run_key,request_hash,execution_stage,effect_state,occupies_slot,config_json,execution_user_id,schedule_generation) VALUES(1,100,5,1,'a',repeat('a',64),'EXECUTION_STARTED','UNKNOWN',true,'{}',99,0)");
        execute("INSERT INTO dh_dataforge_access_task(id,data_access_id,run_status,protocol_version,run_key,request_hash,execution_stage,effect_state,config_json,execution_user_id,schedule_generation) VALUES(2,100,1,1,'b',repeat('b',64),'PREPARE','NONE','{}',99,0)");
        assertSqlState("23505", "UPDATE dh_dataforge_access_task SET occupies_slot=true WHERE id=2");
    }

    @Test
    void dispatchBatchGivesAnotherTenantAChanceBeforeHotTenantSecondTask() throws Exception {
        migrate();
        execute(Files.readString(root.resolve("sql/postgresql/migrations/0049_dataforge_run-datasource-capacity.sql"))
                .replace("\"datahub-center\"", schema));
        insertEtl(1, 100, 1, "etl-a");
        insertEtl(2, 101, 1, "etl-b");
        insertEtl(3, 102, 2, "etl-c");
        execute("INSERT INTO dh_dataforge_access_task(id,data_access_id,run_status,protocol_version,run_key,request_hash,execution_stage,effect_state,config_json,execution_user_id,schedule_generation) VALUES(11,200,1,1,'access-a',repeat('a',64),'PREPARE','NONE','{}',99,0),(12,201,1,1,'access-b',repeat('b',64),'PREPARE','NONE','{}',99,0),(13,202,1,1,'access-c',repeat('c',64),'PREPARE','NONE','{}',99,0)");
        assertEquals(List.of(1L, 3L), candidateIds(EtlRecordMapper.class, "selectDispatchCandidates"));
        assertEquals(List.of(11L, 13L), candidateIds(DataAccessTaskMapper.class, "waitingCandidates"));
        // A full tenant must not consume the scan budget of another tenant, even across domains.
        execute("UPDATE dh_dataforge_etl_record SET occupies_slot=true,run_status=6,deleted=1 WHERE id=1");
        assertEquals(List.of(3L), candidateIds(EtlRecordMapper.class, "selectDispatchCandidates"));
        assertEquals(List.of(13L), candidateIds(DataAccessTaskMapper.class, "waitingCandidates"));
    }

    @Test
    void legacyActivityIsQuarantinedWithoutInventingRequestIdentity() throws Exception {
        execute("INSERT INTO dh_dataforge_etl_record(id,pipeline_id,run_status) VALUES(1,10,2),(2,20,3)");
        execute("INSERT INTO dh_dataforge_access_task(id,data_access_id,run_status) VALUES(3,10,1)");
        migrate();
        assertEquals("6:true:true:0:null", value("SELECT run_status||':'||occupies_slot||':'||review_required||':'||protocol_version||':'||COALESCE(run_key,'null') FROM dh_dataforge_etl_record WHERE id=1"));
        assertEquals("3:false:false:0", value("SELECT run_status||':'||occupies_slot||':'||review_required||':'||protocol_version FROM dh_dataforge_etl_record WHERE id=2"));
        assertEquals("5:true", value("SELECT run_status||':'||occupies_slot FROM dh_dataforge_access_task WHERE id=3"));
        migrate();
        assertEquals("6:true", value("SELECT run_status||':'||occupies_slot FROM dh_dataforge_etl_record WHERE id=1"));
    }

    @Test
    void legacyConflictsAbortEntireMigrationInsteadOfChoosingARecord() throws Exception {
        execute("INSERT INTO dh_dataforge_etl_record(id,pipeline_id,run_status) VALUES(11,10,1),(12,10,2)");
        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertTrue(failure.getMessage().contains("11"));
        assertTrue(failure.getMessage().contains("12"));
        execute("ROLLBACK");
        assertEquals("0", value("SELECT count(*) FROM information_schema.columns WHERE table_schema='" + schema
                + "' AND table_name='dh_dataforge_etl_record' AND column_name='protocol_version'"));
        assertEquals("1,2", value("SELECT string_agg(run_status::text,',' ORDER BY id) FROM dh_dataforge_etl_record"));
    }

    @Test
    void commandIdentityCannotBeReusedAfterSoftDeletion() throws Exception {
        migrate();
        execute("INSERT INTO dh_dataforge_run_command(id,domain,record_id,request_id,action,request_hash,actor_id,result_json) VALUES(1,'ETL',10,'request','STOP',repeat('a',64),99,'{}')");
        execute("UPDATE dh_dataforge_run_command SET deleted=1 WHERE id=1");
        assertSqlState("23505", "INSERT INTO dh_dataforge_run_command(id,tenant_id,domain,record_id,request_id,action,request_hash,actor_id,result_json)"
                + " VALUES(2,1,'ETL',11,'request','STOP',repeat('a',64),99,'{}')");
    }

    @Test
    void recognizesAndReplacesLegacyWaitingOccupancyIndex() throws Exception {
        execute("ALTER TABLE dh_dataforge_etl_record ADD COLUMN execution_token varchar(64),ADD COLUMN quality_status varchar(32)");
        execute("CREATE UNIQUE INDEX etl_one_active ON dh_dataforge_etl_record(tenant_id,pipeline_id)"
                + " WHERE deleted=0 AND (run_status IN(1,2) OR (run_status=6 AND execution_token IS NOT NULL)"
                + " OR (run_status=3 AND quality_status IN ('PENDING','UNKNOWN')))");
        migrate();
        insertEtl(1, 10, 1, "a");
        insertEtl(2, 10, 1, "b");
        assertEquals("0", value("SELECT count(*) FROM pg_indexes WHERE schemaname='" + schema + "' AND indexname='etl_one_active'"));
        migrate();
    }

    @Test
    void unknownTaskWideUniqueIndexAbortsInsteadOfDroppingCustomConstraint() throws Exception {
        execute("CREATE UNIQUE INDEX custom_active_policy ON dh_dataforge_access_task(tenant_id,data_access_id) WHERE deleted=0");
        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertTrue(failure.getMessage().contains("custom_active_policy"));
        execute("ROLLBACK");
        assertEquals("1", value("SELECT count(*) FROM pg_indexes WHERE schemaname='" + schema + "' AND indexname='custom_active_policy'"));
        assertEquals("0", value("SELECT count(*) FROM information_schema.columns WHERE table_schema='" + schema
                + "' AND table_name='dh_dataforge_access_task' AND column_name='protocol_version'"));
    }

    @Test
    void v1RequiresDurableIdentityAndSnapshotButHistoryRemainsNullable() throws Exception {
        migrate();
        for (String table : List.of("dh_dataforge_etl_record", "dh_dataforge_access_task")) {
            String parent = table.endsWith("record") ? "pipeline_id" : "data_access_id";
            assertSqlState("23514", "INSERT INTO " + table + "(id," + parent + ",tenant_id,run_status,protocol_version)"
                    + " VALUES(100,100,1,1,1)");
            execute("INSERT INTO " + table + "(id," + parent + ",run_status,protocol_version) VALUES(101,100,3,0)");
        }
        insertEtl(1, 100, 1, "valid");
        for (String assignment : List.of("run_key=NULL", "run_key=' '", "request_hash=NULL", "request_hash='invalid'",
                "config_json=NULL", "config_json='[]'", "execution_user_id=NULL", "execution_stage=NULL",
                "effect_state=NULL", "schedule_generation=NULL", "row_version=-1")) {
            assertSqlState("23514", "UPDATE dh_dataforge_etl_record SET " + assignment + " WHERE id=1");
        }
    }


    @Test
    void resolutionPermissionsReplayWithoutGrantingExistingRoles() throws Exception {
        menuFixture();
        migratePermissions();
        String first = value("SELECT string_agg(id::text,',' ORDER BY permission) FROM system_menu WHERE permission LIKE '%:resolve'");
        migratePermissions();
        assertEquals(first, value("SELECT string_agg(id::text,',' ORDER BY permission) FROM system_menu WHERE permission LIKE '%:resolve'"));
        assertEquals("2", value("SELECT count(*) FROM system_menu WHERE permission IN('dataforge:etl:resolve','dataforge:access:resolve') AND type=3 AND deleted=0"));
        assertEquals("1", value("SELECT count(*) FROM system_role_menu"));
    }

    @Test
    void missingParentPermissionRollsBackAllPermissionChanges() throws Exception {
        menuFixture();
        execute("DELETE FROM system_menu WHERE permission='dataforge:access:run'");
        assertThrows(SQLException.class, this::migratePermissions);
        execute("ROLLBACK");
        assertEquals("0", value("SELECT count(*) FROM system_menu WHERE permission LIKE '%:resolve'"));
    }

    @Test
    void existingCustomResolutionPermissionIsNotSilentlyReassigned() throws Exception {
        menuFixture();
        execute("INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES(99,'custom','dataforge:etl:resolve',3,999)");
        assertThrows(SQLException.class, this::migratePermissions);
        execute("ROLLBACK");
        assertEquals("999", value("SELECT parent_id FROM system_menu WHERE id=99"));
    }

    private void menuFixture() throws Exception {
        execute("CREATE SEQUENCE system_menu_seq START 10000");
        execute("CREATE TABLE system_menu(id bigint PRIMARY KEY DEFAULT nextval('system_menu_seq'),"
                + "name varchar(50) NOT NULL,permission varchar(100) NOT NULL DEFAULT '',type smallint NOT NULL,"
                + "sort integer NOT NULL DEFAULT 0,parent_id bigint NOT NULL DEFAULT 0,path varchar(200) NOT NULL DEFAULT '',"
                + "icon varchar(100) NOT NULL DEFAULT '',component varchar(255),component_name varchar(255),"
                + "status smallint NOT NULL DEFAULT 0,visible boolean NOT NULL DEFAULT true,keep_alive boolean NOT NULL DEFAULT true,"
                + "always_show boolean NOT NULL DEFAULT true,creator varchar(64) DEFAULT '',updater varchar(64) DEFAULT '',"
                + "create_time timestamp NOT NULL DEFAULT now(),update_time timestamp NOT NULL DEFAULT now(),deleted smallint NOT NULL DEFAULT 0)");
        execute("INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES"
                + "(10,'ETL','',2,0),(11,'运行','dataforge:etl:run',3,10),"
                + "(20,'接入','',2,0),(21,'运行','dataforge:access:run',3,20)");
        execute("CREATE TABLE system_role_menu(id bigint,role_id bigint,menu_id bigint,deleted smallint DEFAULT 0)");
        execute("INSERT INTO system_role_menu VALUES(1,1,11,0)");
    }

    private void migratePermissions() throws Exception {
        execute(Files.readString(root.resolve("sql/postgresql/migrations/0039_dataforge_run-resolution-permissions.sql"))
                .replace("\"datahub-center\"", schema));
    }


    @Test
    void legacyPredicateWithExtraExpressionKeyIsNeverAutoDropped() throws Exception {
        execute("CREATE UNIQUE INDEX custom_expression_policy ON dh_dataforge_etl_record(tenant_id,pipeline_id,lower(name))"
                + " WHERE deleted=0 AND run_status IN(1,2)");
        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertTrue(failure.getMessage().contains("custom_expression_policy"));
        execute("ROLLBACK");
        assertEquals("1", value("SELECT count(*) FROM pg_indexes WHERE schemaname='" + schema + "' AND indexname='custom_expression_policy'"));
        assertEquals("0", value("SELECT count(*) FROM information_schema.columns WHERE table_schema='" + schema
                + "' AND table_name='dh_dataforge_etl_record' AND column_name='protocol_version'"));
    }

    private void migrate() throws Exception {
        List<Path> files;
        try (var stream = Files.list(root.resolve("sql/postgresql/migrations"))) {
            files = stream.filter(file -> file.getFileName().toString().matches("[0-9]{4}_dataforge_run-protocol[.]sql")).toList();
        }
        assertEquals(1, files.size(), "one numbered run-protocol migration");
        execute(Files.readString(files.getFirst()).replace("\"datahub-center\"", schema));
    }

    private void insertEtl(long id, long task, long tenant, String key) throws Exception {
        execute(insertEtlSql(id, task, tenant, key));
    }

    private String insertEtlSql(long id, long task, long tenant, String key) {
        return "INSERT INTO dh_dataforge_etl_record(id,pipeline_id,tenant_id,run_status,protocol_version,run_key,request_hash,execution_stage,effect_state,config_json,execution_user_id,schedule_generation)"
                + " VALUES(" + id + "," + task + "," + tenant + ",1,1,'" + key + "',repeat('a',64),'PREPARE','NONE','{}',99,0)";
    }

    private void assertSqlState(String expected, String sql) {
        assertEquals(expected, assertThrows(SQLException.class, () -> execute(sql)).getSQLState());
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    private int update(String sql) throws SQLException {
        try (var statement = db.createStatement()) { return statement.executeUpdate(sql); }
    }

    private String value(String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private List<Long> candidateIds(Class<?> mapper, String method) throws Exception {
        String sql = String.join(" ", mapper.getMethod(method, int.class, int.class, int.class).getAnnotation(Select.class).value())
                .replace("#{limit}", "2").replace("#{tenantCapacity}", "1").replace("#{dataSourceCapacity}", "8");
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            List<Long> ids = new java.util.ArrayList<>();
            while (rows.next()) ids.add(rows.getLong(1));
            return ids;
        }
    }
}
