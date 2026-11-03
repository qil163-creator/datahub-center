package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskVersionMapper;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.*;
import java.sql.Connection;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 隔离 PostgreSQL 验证版本和当前定义原子保存、真实 CAS 回滚及租户隔离。 */
@Testcontainers(disabledWithoutDocker = true)
class DevTaskSavePostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private Connection control;
    private String schema;
    private DevTaskServiceImpl service;
    private DevTaskMapper tasks;
    private DevTaskVersionMapper versions;
    private DevDsSubmitter submitter;
    private DevScheduleMapper schedules;
    private DevRecordMapper records;
    private DevScheduleCoordinator coordinator;
    private cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient client;

    @BeforeEach void setup() throws Exception {
        schema = "dev_save_" + UUID.randomUUID().toString().replace("-", "");
        control = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("CREATE SCHEMA " + schema); execute("SET search_path TO " + schema);
        execute("CREATE TABLE dh_dataforge_dev_task (id bigint PRIMARY KEY,revision integer DEFAULT 3,current_version_id bigint,execution_user_id bigint,quality_task_id bigint,dependency_task_ids_json jsonb DEFAULT '[]',dir_id bigint DEFAULT 0,name varchar(100),owner_id bigint,owner_name varchar(100),contact_number varchar(32),scheduler varchar(24),execution_config_json jsonb,task_type varchar(32),program_config_json jsonb,database_id bigint,datasource_name varchar(100),database_name varchar(100),db_type varchar(32),content text,timeout_seconds integer,max_rows integer,ds_datasource_id integer,ds_datasource_type varchar(32),ds_workflow_code bigint,description varchar(500),status integer DEFAULT 1,schedule_status integer DEFAULT 0,cron_expression varchar(100),legacy_cron_expression varchar(100),next_run_time timestamp,latest_version integer DEFAULT 1,tenant_id bigint DEFAULT 1,creator varchar(64),updater varchar(64),create_time timestamp,update_time timestamp,deleted smallint DEFAULT 0)");
        execute("CREATE TABLE dh_dataforge_dev_task_version (id bigint PRIMARY KEY,task_id bigint,version integer,content text,remark varchar(200),snapshot_json jsonb,tenant_id bigint DEFAULT 1,creator varchar(64),updater varchar(64),create_time timestamp,update_time timestamp,deleted smallint DEFAULT 0)");
        execute("CREATE TABLE dh_dataforge_dev_record (id bigint PRIMARY KEY,task_id bigint,task_name varchar(100),database_id bigint,datasource_name varchar(100),trigger_type varchar(16),status varchar(24),start_time timestamp,end_time timestamp,duration_ms bigint,statement_total integer,statement_success integer,affected_rows bigint,ds_instance_id integer,log text,creator varchar(64),create_time timestamp DEFAULT now(),updater varchar(64),update_time timestamp DEFAULT now(),deleted smallint DEFAULT 0,tenant_id bigint,business_date date,run_key varchar(160),version_id bigint,snapshot_json jsonb,execution_user_id bigint,ds_project_code bigint,ds_workflow_code bigint,ds_trigger_code bigint,submission_stage varchar(32),lease_owner varchar(64),lease_until timestamp,heartbeat_time timestamp,cancel_requested boolean,last_error varchar(2000),quality_status varchar(24),quality_instance_id bigint,resolved_by bigint,resolved_at timestamp,resolution_reason varchar(1000))");
        execute("CREATE UNIQUE INDEX dev_record_run_key ON dh_dataforge_dev_record(tenant_id,run_key) WHERE deleted=0 AND run_key IS NOT NULL");
        var root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!java.nio.file.Files.exists(root.resolve("sql/postgresql/migrations/0164_dataforge_dev-dolphinscheduler-schedules.sql"))) root = root.getParent();
        String migration = java.nio.file.Files.readString(root.resolve("sql/postgresql/migrations/0164_dataforge_dev-dolphinscheduler-schedules.sql"));
        execute(migration.substring(migration.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_dataforge_dev_schedule_seq;"), migration.indexOf("ALTER SEQUENCE dh_dataforge_dev_schedule_seq OWNED")));
        execute("CREATE UNIQUE INDEX schedule_generation ON dh_dataforge_dev_schedule(tenant_id,task_id,generation) WHERE deleted=0");
        execute("INSERT INTO dh_dataforge_dev_task(id,name,task_type,content,execution_user_id,timeout_seconds,max_rows,current_version_id) VALUES(1,'task','SHELL','original script',77,300,1000,80)");
        execute("INSERT INTO dh_dataforge_dev_task_version(id,task_id,version,content,snapshot_json) VALUES(80,1,1,'original script','{\"task\":{\"id\":1,\"taskType\":\"SHELL\",\"content\":\"original script\",\"executionUserId\":77}}')");
        execute("INSERT INTO dh_dataforge_dev_record(id,task_id,version_id,snapshot_json) VALUES(9,1,80,'{\"task\":{\"id\":1,\"content\":\"original script\"}}')");
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getGlobalConfig(config).getDbConfig().setIdType(IdType.ASSIGN_ID);
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setEnvironment(new Environment("dev-save-real-pg", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();  config.addInterceptor(interceptor); config.addMapper(DevTaskMapper.class); config.addMapper(DevTaskVersionMapper.class); config.addMapper(DevScheduleMapper.class); config.addMapper(DevRecordMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        tasks = sessions.getMapper(DevTaskMapper.class); versions = sessions.getMapper(DevTaskVersionMapper.class); schedules = sessions.getMapper(DevScheduleMapper.class); records = sessions.getMapper(DevRecordMapper.class);
        service = new DevTaskServiceImpl(); submitter = mock(DevDsSubmitter.class);
        when(submitter.configuredEnvironmentCode()).thenReturn(-1L);
        when(submitter.executionTarget(any())).thenReturn(new DevDsSubmitter.ExecutionTarget("isolated-test", "datahub_test"));
        ReflectionTestUtils.setField(service, "taskMapper", tasks); ReflectionTestUtils.setField(service, "versionMapper", versions);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(new DataSourceTransactionManager(source)));
        ReflectionTestUtils.setField(service, "dirService", mock(DevDirService.class)); ReflectionTestUtils.setField(service, "dsSubmitter", submitter);
        ReflectionTestUtils.setField(service, "executionAccessService", mock(ExecutionAccessService.class)); ReflectionTestUtils.setField(service, "qualityGateService", mock(QualityGateService.class));
        coordinator = new DevScheduleCoordinator(); client = mock(cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient.class);
        when(submitter.executionProject(any())).thenReturn(19L);
        ReflectionTestUtils.setField(coordinator, "schedules", schedules); ReflectionTestUtils.setField(coordinator, "submitter", submitter); ReflectionTestUtils.setField(coordinator, "client", client);
        ReflectionTestUtils.setField(coordinator, "timezoneId", "Asia/Shanghai"); ReflectionTestUtils.setField(coordinator, "apiTimezoneId", "Asia/Shanghai");
        ReflectionTestUtils.setField(coordinator, "triggerWorkerGroup", "controlled"); ReflectionTestUtils.setField(coordinator, "triggerTenantCode", "isolated");
        ReflectionTestUtils.setField(service, "scheduleCoordinator", coordinator); ReflectionTestUtils.setField(service, "scheduleMapper", schedules);
        TenantContextHolder.setTenantId(1L);
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(
                new cn.iocoder.yudao.framework.security.core.LoginUser().setId(77L).setTenantId(1L),
                new org.springframework.mock.web.MockHttpServletRequest());
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext();
        execute("DROP SCHEMA " + schema + " CASCADE"); control.close();
    }

    @Test void saveCommitsCurrentVersionAndJsonbWhileKeepingHistoricalRuns() throws Exception {
        service.updateTask(request(3));
        var task = tasks.selectById(1L); assertEquals(4, task.getRevision()); assertEquals(2, task.getLatestVersion()); assertEquals("saved script", task.getContent());
        assertNotEquals(80L, task.getCurrentVersionId());
        var version = versions.selectById(task.getCurrentVersionId()); assertEquals("saved script", DevTaskSnapshot.read(version.getSnapshotJson()).getTask().getContent());
        when(submitter.enqueue(any(), any(), eq("MANUAL"), anyString(), any())).thenReturn(91L);
        var run = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskRunReqVO();
        run.setId(1L); run.setRequestId("save-and-run"); run.setBusinessDate(java.time.LocalDate.now());
        assertEquals(91L, service.runTask(run));
        verify(submitter).enqueue(any(), argThat(value -> value.getId().equals(task.getCurrentVersionId())),
                eq("MANUAL"), eq("MANUAL:save-and-run"), eq(run.getBusinessDate()));
        assertEquals("object", one("SELECT jsonb_typeof(snapshot_json) FROM dh_dataforge_dev_task_version WHERE id=" + version.getId()));
        assertEquals("original script", versions.selectById(80L).getContent()); assertEquals("80", one("SELECT version_id FROM dh_dataforge_dev_record WHERE id=9"));
        TenantContextHolder.setTenantId(2L);
        assertNull(tasks.selectById(1L)); assertNull(versions.selectById(version.getId())); assertThrows(ServiceException.class, () -> service.getTaskVersionList(1L));
    }

    @Test void concurrentRevisionChangeRollsBackNewVersionAndDefinitionTogether() throws Exception {
        doAnswer(inv -> { execute("UPDATE dh_dataforge_dev_task SET revision=4 WHERE id=1"); return null; }).when(submitter).validateForSave(any());
        assertThrows(ServiceException.class, () -> service.updateTask(request(3)));
        assertEquals("1", one("SELECT count(*) FROM dh_dataforge_dev_task_version"));
        var task = tasks.selectById(1L); assertEquals("original script", task.getContent()); assertEquals(80L, task.getCurrentVersionId()); assertEquals(4, task.getRevision());
        assertEquals("80", one("SELECT version_id FROM dh_dataforge_dev_record WHERE id=9"));
    }

    @Test void enabledScheduleIntentSharesTheTaskVersionTransactionAndSaveDoesNotExecute() throws Exception {
        execute("UPDATE dh_dataforge_dev_task SET schedule_status=1 WHERE id=1");
        var req = request(3); req.setCronExpression("0 * * * * ?"); service.updateTask(req);
        var task = tasks.selectById(1L); var binding = schedules.selectList(null).getFirst();
        assertEquals(task.getCurrentVersionId(), binding.getVersionId()); assertEquals(task.getRevision(), binding.getGeneration());
        assertEquals("PENDING", binding.getSyncStatus()); assertTrue(binding.getDesiredEnabled()); assertFalse(binding.getSyncedEnabled());
        verifyNoInteractions(client); verify(submitter, never()).enqueue(any(), any(), any(), any(), any());
        assertEquals("1", one("SELECT count(*) FROM dh_dataforge_dev_record"));
    }

    @Test void bindingFailureRollsBackNewDefinitionAndVersionTogether() throws Exception {
        execute("UPDATE dh_dataforge_dev_task SET schedule_status=1 WHERE id=1");
        var failed = spy(coordinator); doAnswer(inv -> { coordinator.saveIntent(inv.getArgument(0), inv.getArgument(1)); throw new IllegalStateException("intent rollback"); }).when(failed).saveIntent(any(), any());
        ReflectionTestUtils.setField(service, "scheduleCoordinator", failed);
        var req = request(3); req.setCronExpression("0 * * * * ?");
        assertThrows(IllegalStateException.class, () -> service.updateTask(req));
        assertEquals("original script", tasks.selectById(1L).getContent()); assertEquals(80L, tasks.selectById(1L).getCurrentVersionId());
        assertEquals(3, tasks.selectById(1L).getRevision()); assertEquals("1", one("SELECT count(*) FROM dh_dataforge_dev_task_version"));
        assertEquals(0, schedules.selectCount(null)); verifyNoInteractions(client);
    }

    @Test void scheduledInstanceIsDurablyDeduplicatedAndUsesActualDsDate() throws Exception {
        execute("UPDATE dh_dataforge_dev_task SET schedule_status=1 WHERE id=1");
        var req = request(3); req.setCronExpression("0 * * * * ?"); service.updateTask(req);
        var binding = schedules.selectList(null).getFirst();
        execute("UPDATE dh_dataforge_dev_schedule SET synced_enabled=true,sync_status='SYNCED',ds_workflow_code=99,lease_owner='current',lease_until='" + java.time.LocalDateTime.now().plusMinutes(5) + "' WHERE id=" + binding.getId());
        binding = schedules.selectById(binding.getId());
        var actualSubmitter = new DevDsSubmitter(); ReflectionTestUtils.setField(actualSubmitter, "recordMapper", records); ReflectionTestUtils.setField(actualSubmitter, "projectCode", 19L);
        ReflectionTestUtils.setField(service, "dsSubmitter", actualSubmitter);
        var scheduledTime = java.time.LocalDateTime.of(2026, 10, 12, 23, 59);
        var id = service.runTaskScheduled(binding, 17L, scheduledTime);
        assertEquals(id, service.runTaskScheduled(binding, 17L, scheduledTime)); assertEquals("2", one("SELECT count(*) FROM dh_dataforge_dev_record"));
        var record = records.selectById(id); assertEquals("DS_SCHEDULE:19:17", record.getRunKey()); assertEquals(scheduledTime.toLocalDate(), record.getBusinessDate());
        assertEquals(binding.getVersionId(), record.getVersionId()); assertEquals("WAITING", record.getStatus());
        execute("UPDATE dh_dataforge_dev_schedule SET desired_enabled=false WHERE id=" + binding.getId());
        assertNull(service.runTaskScheduled(binding, 18L, scheduledTime)); assertEquals("2", one("SELECT count(*) FROM dh_dataforge_dev_record"));
        TenantContextHolder.setTenantId(2L); var foreign = binding;
        assertThrows(ServiceException.class, () -> service.runTaskScheduled(foreign, 17L, scheduledTime));
    }

    private DevTaskSaveReqVO request(int revision) {
        var req = new DevTaskSaveReqVO(); req.setId(1L); req.setName("task"); req.setDirId(0L); req.setTaskType("SHELL");
        req.setContent("saved script"); req.setExpectedRevision(revision); req.setExecutionUserId(77L); req.setTimeoutSeconds(300); req.setMaxRows(1000); return req;
    }

    @Test void failedMarkerCreatesOneTerminalRecordWithoutSubmittingBusinessOrBlockingTheNextRun() throws Exception {
        execute("UPDATE dh_dataforge_dev_task SET schedule_status=1 WHERE id=1");
        var request = request(3); request.setCronExpression("0 * * * * ?"); service.updateTask(request);
        var binding = schedules.selectList(null).getFirst();
        execute("UPDATE dh_dataforge_dev_schedule SET synced_enabled=true,sync_status='SYNCED',ds_workflow_code=99,lease_owner='current',lease_until='"
                + java.time.LocalDateTime.now().plusMinutes(5) + "' WHERE id=" + binding.getId());
        binding = schedules.selectById(binding.getId()); ReflectionTestUtils.setField(service, "recordMapper", records);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var time = java.time.LocalDateTime.of(2026, 10, 14, 0, 0);
        Long id = service.recordTaskScheduledFailure(binding, 17L, time, "FAILED");
        assertEquals(id, service.recordTaskScheduledFailure(binding, 17L, time, "FAILED"));
        var failure = records.selectById(id); assertEquals("FAILED", failure.getStatus());
        assertEquals("TRIGGER_FAILED", failure.getSubmissionStage()); assertTrue(failure.getLastError().contains("业务任务未投递"));
        assertEquals("DS_SCHEDULE:19:17", failure.getRunKey()); assertEquals(time.toLocalDate(), failure.getBusinessDate());
        assertEquals("2", one("SELECT count(*) FROM dh_dataforge_dev_record"));
        verify(submitter, never()).enqueue(any(), any(), any(), any(), any());
        var actualSubmitter = new DevDsSubmitter(); ReflectionTestUtils.setField(actualSubmitter, "recordMapper", records);
        ReflectionTestUtils.setField(actualSubmitter, "projectCode", 19L); ReflectionTestUtils.setField(service, "dsSubmitter", actualSubmitter);
        Long nextId = service.runTaskScheduled(binding, 18L, time.plusMinutes(1));
        assertNotEquals(id, nextId); assertEquals("WAITING", records.selectById(nextId).getStatus());
        assertEquals("3", one("SELECT count(*) FROM dh_dataforge_dev_record"));
    }

    private void execute(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
    private String one(String sql) throws Exception { try (var statement = control.createStatement(); var result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getString(1); } }
}
