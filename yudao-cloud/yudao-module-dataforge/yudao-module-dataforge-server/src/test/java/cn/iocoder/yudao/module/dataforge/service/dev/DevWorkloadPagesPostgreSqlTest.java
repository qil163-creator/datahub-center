package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离 PostgreSQL 验证运行模式分页、聚合和菜单迁移；不连接应用数据库。 */
@Testcontainers(disabledWithoutDocker = true)
class DevWorkloadPagesPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "0254_dataforge_offline-realtime-task-menus.sql";

    @Test
    void workloadFilteringRunsBeforePaginationAndAggregatesUseTheSameEffectiveTasks() throws Exception {
        var source = new PooledDataSource("org.postgresql.Driver",
                POSTGRES.getJdbcUrl() + "&currentSchema=datahub-center", POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            reset(sql);
            sql.execute("CREATE TABLE dh_dataforge_dev_task (id bigint PRIMARY KEY, revision integer, current_version_id bigint, "
                    + "execution_user_id bigint, quality_task_id bigint, dependency_task_ids_json jsonb, dir_id bigint, name varchar(100), "
                    + "owner_id bigint, owner_name varchar(100), contact_number varchar(32), scheduler varchar(32), legacy_cron_expression varchar(100), "
                    + "execution_config_json jsonb, task_type varchar(32), program_config_json jsonb, database_id bigint, datasource_name varchar(100), "
                    + "database_name varchar(100), db_type varchar(32), content text, timeout_seconds integer, max_rows integer, ds_datasource_id integer, "
                    + "ds_datasource_type varchar(32), ds_workflow_code bigint, description varchar(500), status integer, schedule_status integer, "
                    + "cron_expression varchar(100), next_run_time timestamp, latest_version integer, create_time timestamp, update_time timestamp, "
                    + "creator varchar(64), updater varchar(64), deleted smallint DEFAULT 0)");
            sql.execute("CREATE TABLE dh_dataforge_dev_record(id bigint PRIMARY KEY,task_id bigint,status varchar(32),start_time timestamp,deleted smallint DEFAULT 0)");
            sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,dir_id,task_type,program_config_json,deleted) VALUES "
                    + "(1,'历史 SQL',1,'SQL',NULL,0),(2,'离线 Flink',1,'FLINK_SQL','{\"workloadMode\":\"BATCH\"}',0),"
                    + "(3,'缺省模式',1,'SPARK_SQL','{}',0),(4,'实时 Flink',1,'FLINK_SQL','{\"workloadMode\":\"STREAMING\"}',0),"
                    + "(5,'已删除实时',1,'FLINK_SQL','{\"workloadMode\":\"STREAMING\"}',1)");
            var now = LocalDateTime.now();
            try (var insert = connection.prepareStatement("INSERT INTO dh_dataforge_dev_record(id,task_id,status,start_time,deleted) VALUES (?,?,?,?,?)")) {
                Object[][] rows = {{11L,1L,"FAILED",0},{12L,2L,"CANCEL_REQUESTED",0},{13L,4L,"RUNNING",0},
                        {14L,4L,"WAITING",0},{15L,4L,"SUCCESS",0},{16L,5L,"RUNNING",0},{17L,99L,"RUNNING",0},{18L,4L,"FAILED",1}};
                for (var row : rows) {
                    insert.setLong(1,(Long) row[0]); insert.setLong(2,(Long) row[1]); insert.setString(3,(String) row[2]);
                    insert.setObject(4,now); insert.setInt(5,(Integer) row[3]); insert.executeUpdate();
                }
            }
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("dev-workload-real-pg",new JdbcTransactionFactory(),source));
            var interceptors = new MybatisPlusInterceptor();
            interceptors.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
            config.addInterceptor(interceptors);
            config.addMapper(DevTaskMapper.class); config.addMapper(DevRecordMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var tasks = session.getMapper(DevTaskMapper.class);
                var records = session.getMapper(DevRecordMapper.class);
                var request = new DevTaskPageReqVO(); request.setPageSize(1); request.setWorkloadMode("BATCH");
                var page = tasks.selectPage(request,List.of(1L));
                assertEquals(3,page.getTotal()); assertEquals(3L,page.getList().getFirst().getId());
                request.setPageNo(3);
                assertEquals(1L,tasks.selectPage(request,List.of(1L)).getList().getFirst().getId());
                request.setPageNo(1); request.setWorkloadMode("STREAMING");
                page = tasks.selectPage(request,List.of(1L));
                assertEquals(1,page.getTotal()); assertEquals(4L,page.getList().getFirst().getId());
                request.setWorkloadMode(null); assertEquals(4,tasks.selectPage(request,List.of(1L)).getTotal());
                request.setWorkloadMode("STREAMING' OR 1=1 --");
                assertEquals(0,tasks.selectPage(request,List.of(1L)).getTotal());
                assertEquals(1,records.countActiveTasks("BATCH"));
                assertEquals(1,records.countActiveTasks("STREAMING"));
                assertEquals(4,records.countActiveTasks());
                var service = new DevTaskServiceImpl(); ReflectionTestUtils.setField(service,"recordMapper",records);
                ReflectionTestUtils.setField(service,"taskMapper",tasks);
                assertEquals(List.of(4L),service.getTaskListByDir(null,"STREAMING").stream().map(task->task.getId()).toList());
                assertEquals(3,service.getTaskListByDir(null,"BATCH").size());
                assertEquals(4,service.getTaskListByDir(null).size());
                var batch = service.getStatistics("BATCH");
                assertEquals(2,batch.getTodayExecutions()); assertEquals(1,batch.getTodayFailed());
                assertEquals(0d,batch.getTodaySuccessRate());
                var streaming = service.getStatistics("STREAMING");
                assertEquals(3,streaming.getTodayExecutions()); assertEquals(0,streaming.getTodayFailed());
                assertEquals(33.33d,streaming.getTodaySuccessRate());
                sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,dir_id,task_type,execution_config_json,timeout_seconds) "
                        + "VALUES(6,'实时页面普通SQL',1,'SQL','{\"workloadMode\":\"STREAMING\"}',60)");
                sql.execute("INSERT INTO dh_dataforge_dev_task(id,name,dir_id,task_type,program_config_json,execution_config_json) "
                        + "VALUES(7,'页面归属优先于引擎模式',1,'FLINK_SQL','{\"workloadMode\":\"BATCH\"}','{\"workloadMode\":\"STREAMING\"}')");
                session.clearCache();
                request.setWorkloadMode("STREAMING");
                assertEquals(3,tasks.selectPage(request,List.of(1L)).getTotal());
                assertEquals(List.of(4L,6L,7L),service.getTaskListByDir(null,"STREAMING").stream().map(task->task.getId()).toList());
                request.setWorkloadMode("BATCH");
                assertEquals(3,tasks.selectPage(request,List.of(1L)).getTotal());
                assertEquals(3L,tasks.selectPage(request,List.of(1L)).getList().getFirst().getId());
                try (var insert = connection.prepareStatement("INSERT INTO dh_dataforge_dev_record(id,task_id,status,start_time) VALUES(19,6,'SUCCESS',?)")) {
                    insert.setObject(1,now); insert.executeUpdate();
                }
                session.clearCache();
                var sqlAndNative = service.getStatistics("STREAMING");
                assertEquals(4,sqlAndNative.getTodayExecutions()); assertEquals(50d,sqlAndNative.getTodaySuccessRate());
            }
        } finally {
            source.forceCloseAll();
        }
    }

    @Test
    void menuMigrationReplaysAndInheritsOnlyExistingWorkbenchVisibility() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            menuFixture(sql);
            var migration = Files.readString(root().resolve("sql/postgresql/migrations/" + MIGRATION));
            sql.execute(migration); sql.execute(migration);
            assertEquals(2,count(sql,"SELECT count(*) FROM system_menu WHERE parent_id=6820 AND path IN ('offline','realtime') AND permission='dataforge:dev-task:query'"));
            assertEquals(1,count(sql,"SELECT count(*) FROM system_menu WHERE id=6821 AND component='dataforge/dev/index'"));
            assertEquals(2,count(sql,"SELECT count(*) FROM system_role_menu r JOIN system_menu m ON m.id=r.menu_id WHERE r.role_id=7 AND m.path IN ('offline','realtime')"));
            assertEquals(0,count(sql,"SELECT count(*) FROM system_role_menu WHERE role_id=8"));
            assertEquals(0,count(sql,"SELECT count(*) FROM system_menu WHERE permission IN ('dataforge:dev-task:save','dataforge:dev-task:run','dataforge:dev-task:execute-native')"));
            sql.execute("UPDATE system_menu SET name='自定义实时名称' WHERE path='realtime'");
            sql.execute(migration);
            assertEquals(1,count(sql,"SELECT count(*) FROM system_menu WHERE name='自定义实时名称'"));
        }
    }

    @Test
    void missingParentOrConflictingPathRollsBackTheWholeMenuIncrement() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            var migration = Files.readString(root().resolve("sql/postgresql/migrations/" + MIGRATION));
            menuFixture(sql); sql.execute("DELETE FROM system_menu WHERE id=6820");
            assertThrows(java.sql.SQLException.class,()->sql.execute(migration)); sql.execute("ROLLBACK");
            assertEquals(0,count(sql,"SELECT count(*) FROM system_menu WHERE path IN ('offline','realtime')"));
            menuFixture(sql);
            sql.execute("INSERT INTO system_menu(id,parent_id,path,component) VALUES(7000,6820,'realtime','custom/page')");
            assertThrows(java.sql.SQLException.class,()->sql.execute(migration)); sql.execute("ROLLBACK");
            assertEquals(0,count(sql,"SELECT count(*) FROM system_menu WHERE path='offline'"));
            assertEquals(1,count(sql,"SELECT count(*) FROM system_role_menu"));
        }
    }

    @Test
    void freshBaselineContainsTheExactMenuIncrement() throws Exception {
        var root = root();
        var delta = Files.readString(root.resolve("sql/postgresql/migrations/" + MIGRATION));
        assertTrue(Files.readString(root.resolve("sql/postgresql/datahub-center.sql"))
                .contains("-- 新库同步增量：" + MIGRATION + "\n" + delta));
    }

    private static void reset(Statement sql) throws Exception {
        sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        sql.execute("CREATE SCHEMA \"datahub-center\""); sql.execute("SET search_path TO \"datahub-center\"");
    }

    private static void menuFixture(Statement sql) throws Exception {
        reset(sql);
        sql.execute("CREATE SEQUENCE system_menu_seq; CREATE SEQUENCE system_menu_id_seq; CREATE SEQUENCE system_role_menu_seq");
        sql.execute("CREATE TABLE system_menu(id bigint PRIMARY KEY,name varchar(100),permission varchar(100),type int,sort int,parent_id bigint,path varchar(255),icon varchar(100),component varchar(255),component_name varchar(100),status int,visible boolean,keep_alive boolean,always_show boolean,creator varchar(100),updater varchar(100),deleted smallint DEFAULT 0)");
        sql.execute("CREATE TABLE system_role_menu(id bigint PRIMARY KEY,role_id bigint,menu_id bigint,tenant_id bigint,creator varchar(100),updater varchar(100),deleted smallint DEFAULT 0)");
        sql.execute("INSERT INTO system_menu(id,name,permission,type,parent_id,path,component) VALUES(5992,'数据研发','',1,0,'/develop',NULL),(6820,'数据开发','',1,5992,'dev',NULL),(6821,'工作台','dataforge:dev-task:query',2,6820,'workbench','dataforge/dev/index')");
        sql.execute("INSERT INTO system_role_menu VALUES(1,7,6821,1,'fixture','fixture',0)");
    }

    private static int count(Statement sql,String query) throws Exception {
        try (var result = sql.executeQuery(query)) { assertTrue(result.next()); return result.getInt(1); }
    }

    private static Path root() {
        var current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("sql/postgresql/migrations/" + MIGRATION))) current = current.getParent();
        assertNotNull(current); return current;
    }
}
