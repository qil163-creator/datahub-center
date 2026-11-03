package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.*;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevScheduleMapper;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真正 PostgreSQL 台账验证未知远端结果、租约、旧代停用和重复/迟到触发的恢复。 */
@Testcontainers(disabledWithoutDocker = true)
class DevScheduleCoordinatorPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private Connection control;
    private String schema;
    private DevScheduleMapper bindings;
    private DevScheduleCoordinator coordinator;
    private TransactionTemplate tx;
    private DolphinSchedulerClient client;
    private WorkflowDefinitionApi definitions;
    private TaskDefinitionApi nodes;
    private ScheduleApi schedules;
    private WorkflowInstanceApi instances;
    private DevTaskService tasks;
    private DevDsSubmitter submitter;

    @BeforeEach void setup() throws Exception {
        schema = "dev_tick_" + UUID.randomUUID().toString().replace("-", "");
        control = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("CREATE SCHEMA " + schema); execute("SET search_path TO " + schema);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        String migration = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0164_dataforge_dev-dolphinscheduler-schedules.sql"));
        // 使用正式迁移的台账 DDL，测试自己的隔离 schema，绝不执行应用数据库升级。
        execute(migration.substring(migration.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_dataforge_dev_schedule_seq;"), migration.indexOf("ALTER SEQUENCE dh_dataforge_dev_schedule_seq OWNED")));
        execute("CREATE UNIQUE INDEX schedule_generation ON dh_dataforge_dev_schedule(tenant_id,task_id,generation) WHERE deleted=0");
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword());
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getGlobalConfig(config).getDbConfig().setIdType(IdType.ASSIGN_ID);
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setEnvironment(new Environment("dev-tick-real-pg", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();  config.addInterceptor(interceptor); config.addMapper(DevScheduleMapper.class);
        bindings = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(DevScheduleMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        coordinator = new DevScheduleCoordinator(); client = mock(DolphinSchedulerClient.class);
        definitions = mock(WorkflowDefinitionApi.class); nodes = mock(TaskDefinitionApi.class); schedules = mock(ScheduleApi.class); instances = mock(WorkflowInstanceApi.class);
        when(client.workflowDefinition()).thenReturn(definitions); when(client.taskDefinition()).thenReturn(nodes);
        when(client.schedule()).thenReturn(schedules); when(client.workflowInstance()).thenReturn(instances);
        submitter = mock(DevDsSubmitter.class); when(submitter.executionProject(any())).thenReturn(19L);
        tasks = mock(DevTaskService.class);
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<DevTaskService>) mock(ObjectProvider.class); when(provider.getObject()).thenReturn(tasks);
        ReflectionTestUtils.setField(coordinator, "schedules", bindings); ReflectionTestUtils.setField(coordinator, "submitter", submitter);
        ReflectionTestUtils.setField(coordinator, "client", client); ReflectionTestUtils.setField(coordinator, "tasks", provider);
        ReflectionTestUtils.setField(coordinator, "timezoneId", "Asia/Shanghai"); ReflectionTestUtils.setField(coordinator, "apiTimezoneId", "Asia/Shanghai");
        ReflectionTestUtils.setField(coordinator, "triggerWorkerGroup", "controlled"); ReflectionTestUtils.setField(coordinator, "triggerTenantCode", "isolated");
        TenantContextHolder.setTenantId(7L);
    }

    @AfterEach void cleanup() throws Exception { TenantContextHolder.clear(); execute("DROP SCHEMA " + schema + " CASCADE"); control.close(); }

    @Test void saveOffDoesNotRegisterOrExecuteAnythingAndIntentRollbackKeepsOldGeneration() {
        var old = binding(3, true); bindings.insert(old);
        var task = DevTaskDO.builder().id(1L).currentVersionId(80L).revision(4).cronExpression("0 * * * * ?").scheduleStatus(0).build();
        tx.executeWithoutResult(status -> coordinator.saveIntent(task, snapshot()));
        assertFalse(bindings.selectById(old.getId()).getDesiredEnabled()); assertEquals("PENDING", coordinator.state(1L).status());
        verifyNoInteractions(client, tasks, submitter);
        task.setScheduleStatus(1);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> { coordinator.saveIntent(task, snapshot()); throw new IllegalStateException("rollback"); }));
        assertEquals(1, count()); assertFalse(bindings.selectById(old.getId()).getDesiredEnabled());
        TenantContextHolder.setTenantId(8L); assertNull(bindings.selectById(old.getId())); assertEquals("NOT_CONFIGURED", coordinator.state(1L).status());
    }

    @Test void unknownWorkflowCreateIsAdoptedByExactNameWithoutASecondCreate() {
        var binding = binding(4, true); bindings.insert(binding); wireRemote(binding, "OFFLINE");
        var visible = new java.util.concurrent.atomic.AtomicBoolean();
        when(definitions.queryWorkflowDefinitionListPaging(eq(19L), anyString(), isNull(), isNull(), eq(1), eq(100)))
                .thenAnswer(inv -> page(visible.get() ? List.of(new JSONObject().set("name", name(binding)).set("code", Long.MAX_VALUE)) : List.of()));
        when(nodes.genTaskCodeList(19L, 1)).thenReturn(ok(new JSONArray().put(Long.MAX_VALUE - 1)));
        when(definitions.createWorkflowDefinition(eq(19L), any())).thenAnswer(inv -> { visible.set(true); throw new IllegalStateException("unknown response"); });
        coordinator.process(binding.getId()); assertEquals("ERROR", bindings.selectById(binding.getId()).getSyncStatus());
        coordinator.process(binding.getId());
        assertEquals(Long.MAX_VALUE, bindings.selectById(binding.getId()).getDsWorkflowCode()); assertEquals("SYNCED", bindings.selectById(binding.getId()).getSyncStatus());
        verify(definitions, times(1)).createWorkflowDefinition(eq(19L), any()); verify(schedules).publishScheduleOnline(19L, 12);
    }

    @Test void unknownScheduleCreateIsRecoveredAndManualNoopRunsNeverEnqueueBusiness() {
        var binding = binding(4, true); binding.setDsWorkflowCode(Long.MAX_VALUE); bindings.insert(binding); wireRemote(binding, "OFFLINE");
        var visible = new java.util.concurrent.atomic.AtomicBoolean();
        when(schedules.queryScheduleListPaging(19L, Long.MAX_VALUE, null, 1, 100)).thenAnswer(inv -> page(visible.get() ? List.of(schedule(binding, "OFFLINE")) : List.of()));
        when(schedules.createSchedule(eq(19L), any())).thenAnswer(inv -> { visible.set(true); throw new IllegalStateException("timeout"); });
        coordinator.process(binding.getId()); coordinator.process(binding.getId());
        assertEquals("SYNCED", bindings.selectById(binding.getId()).getSyncStatus()); verify(schedules, times(1)).createSchedule(eq(19L), any());
        when(instances.queryWorkflowInstanceList(eq(19L), any())).thenReturn(page(List.of(new JSONObject().set("id", 6))));
        when(instances.queryWorkflowInstanceById(19L, 6)).thenReturn(ok(instance(binding, 6).set("commandType", "START_PROCESS").set("scheduleTime", null)));
        coordinator.process(binding.getId()); verifyNoInteractions(tasks);
    }

    @Test void oldGenerationMustBeConfirmedOfflineBeforeTheNewGenerationCanGoOnline() {
        var old = binding(3, false); old.setSyncedEnabled(true); old.setDsWorkflowCode(90L); old.setDsScheduleId(10); bindings.insert(old);
        var next = binding(4, true); bindings.insert(next);
        coordinator.process(next.getId()); assertEquals("PENDING", bindings.selectById(next.getId()).getSyncStatus());
        assertEquals(0, bindings.selectById(next.getId()).getAttempts()); verifyNoInteractions(client, tasks);
        executeUnchecked("UPDATE dh_dataforge_dev_schedule SET synced_enabled=false,sync_status='SYNCED' WHERE id=" + old.getId());
        next.setDsWorkflowCode(Long.MAX_VALUE); executeUnchecked("UPDATE dh_dataforge_dev_schedule SET ds_workflow_code=" + Long.MAX_VALUE + " WHERE id=" + next.getId());
        wireRemote(next, "OFFLINE"); coordinator.process(next.getId()); verify(schedules).publishScheduleOnline(19L, 12);
    }

    @Test void headImportUsesDsScheduledTimeAndRevisitsLowerIdAfterCompletion() {
        var binding = binding(4, true); binding.setDsWorkflowCode(Long.MAX_VALUE); binding.setLastDsInstanceId(100L); bindings.insert(binding); wireRemote(binding, "ONLINE");
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(instances.queryWorkflowInstanceList(eq(19L), any())).thenReturn(page(List.of(new JSONObject().set("id", 7), new JSONObject().set("id", 8))));
        when(instances.queryWorkflowInstanceById(19L, 7)).thenAnswer(inv -> ok(instance(binding, 7).set("state", active.get() ? "RUNNING_EXECUTION" : "SUCCESS")));
        when(instances.queryWorkflowInstanceById(19L, 8)).thenReturn(ok(instance(binding, 8).set("dryRun", 1)));
        coordinator.process(binding.getId()); verifyNoInteractions(tasks); assertEquals("SYNCED", bindings.selectById(binding.getId()).getSyncStatus());
        active.set(false); coordinator.process(binding.getId());
        verify(tasks, times(2)).runTaskScheduled(any(), eq(7L), eq(LocalDateTime.of(2026, 10, 13, 23, 59)));
        var request = org.mockito.ArgumentCaptor.forClass(QueryWorkflowInstanceListRequest.class);
        verify(instances, atLeast(2)).queryWorkflowInstanceList(eq(19L), request.capture());
        assertTrue(request.getAllValues().size() <= 4);
        assertTrue(request.getAllValues().stream().allMatch(value -> value.getPageNo() == 1 && value.getPageSize() == 100));
        assertEquals("SYNCED", bindings.selectById(binding.getId()).getSyncStatus());
    }

    @Test void fullHistoricalPageResumesAndFreshHeadIsStillVisited() {
        var binding = binding(4, true); binding.setDsWorkflowCode(Long.MAX_VALUE); binding.setPollEndTime(LocalDateTime.of(2026, 10, 12, 0, 0)); binding.setPollPageNo(2); bindings.insert(binding); wireRemote(binding, "ONLINE");
        var visited = new ArrayList<Integer>();
        when(instances.queryWorkflowInstanceList(eq(19L), any())).thenAnswer(inv -> { QueryWorkflowInstanceListRequest request = inv.getArgument(1); visited.add(request.getPageNo()); return page(List.of()); });
        coordinator.process(binding.getId()); assertEquals(List.of(1, 2), visited);
        assertNull(bindings.selectById(binding.getId()).getPollEndTime()); assertEquals(1, bindings.selectById(binding.getId()).getPollPageNo());
    }

    @Test void foreignProjectMarkersAndFailedTriggersCannotCreateBusinessRuns() {
        var binding = binding(4, true); binding.setDsWorkflowCode(Long.MAX_VALUE); bindings.insert(binding); wireRemote(binding, "ONLINE");
        when(instances.queryWorkflowInstanceList(eq(19L), any())).thenReturn(page(List.of(new JSONObject().set("id", 7))));
        when(instances.queryWorkflowInstanceById(19L, 7)).thenReturn(ok(instance(binding, 7).set("projectCode", 20L)));
        coordinator.process(binding.getId()); assertEquals("ERROR", bindings.selectById(binding.getId()).getSyncStatus()); verifyNoInteractions(tasks);
        when(instances.queryWorkflowInstanceById(19L, 7)).thenReturn(ok(instance(binding, 7).set("globalParams", "[]")));
        coordinator.process(binding.getId()); verifyNoInteractions(tasks);
        when(instances.queryWorkflowInstanceById(19L, 7)).thenReturn(ok(instance(binding, 7).set("state", "FAILURE")));
        coordinator.process(binding.getId());
        verify(tasks, never()).runTaskScheduled(any(), any(), any());
        verify(tasks, times(2)).recordTaskScheduledFailure(any(), eq(7L), any(), eq(DevRecordDO.STATUS_FAILED));
        assertEquals(0, bindings.selectById(binding.getId()).getAttempts());
        assertEquals("SYNCED", bindings.selectById(binding.getId()).getSyncStatus());
    }

    @Test void losingLeaseDuringRemoteReadPreventsOnlineAndImport() {
        var binding = binding(4, true); binding.setDsWorkflowCode(Long.MAX_VALUE); bindings.insert(binding); wireRemote(binding, "OFFLINE");
        when(definitions.queryWorkflowDefinitionByCode(19L, Long.MAX_VALUE)).thenAnswer(inv -> {
            executeUnchecked("UPDATE dh_dataforge_dev_schedule SET lease_owner='replacement' WHERE id=" + binding.getId()); return ok(dag(binding));
        });
        coordinator.process(binding.getId()); assertEquals("replacement", bindings.selectById(binding.getId()).getLeaseOwner());
        verify(schedules, never()).publishScheduleOnline(anyLong(), anyInt()); verifyNoInteractions(tasks);
    }

    @Test void boundedRetryAndTenantScanDoNotKeepSendingAfterFiveFailures() {
        var binding = binding(4, true); binding.setDsWorkflowCode(Long.MAX_VALUE); bindings.insert(binding);
        when(definitions.queryWorkflowDefinitionByCode(19L, Long.MAX_VALUE)).thenThrow(new IllegalStateException("unavailable"));
        for (int i = 0; i < 5; i++) coordinator.process(binding.getId());
        assertEquals(5, bindings.selectById(binding.getId()).getAttempts()); assertTrue(bindings.selectDueMaintenance(LocalDateTime.now()).isEmpty());
        assertFalse(bindings.selectById(binding.getId()).getLastError().contains("unavailable"));
    }

    @Test void ambiguousDefinitionPageCannotLeadToADuplicateCreate() {
        var binding = binding(4, true); bindings.insert(binding);
        when(definitions.queryWorkflowDefinitionListPaging(eq(19L), anyString(), isNull(), isNull(), anyInt(), eq(100)))
                .thenReturn(ok(new JSONObject().set("total", 2000).set("totalList", new JSONArray().put(new JSONObject().set("name", "other")))));
        coordinator.process(binding.getId()); verify(definitions, never()).createWorkflowDefinition(anyLong(), any());
        assertEquals("ERROR", bindings.selectById(binding.getId()).getSyncStatus());
    }

    @Test void savingAgainRetriesRetiredFailureWithoutReenablingTheOldGeneration() {
        var old = binding(3, false); old.setAttempts(5); old.setSyncStatus("ERROR"); old.setSyncedEnabled(true); bindings.insert(old);
        assertTrue(bindings.selectDueMaintenance(LocalDateTime.now()).isEmpty());
        coordinator.retire(1L);
        var retry = bindings.selectById(old.getId()); assertFalse(retry.getDesiredEnabled()); assertEquals(0, retry.getAttempts());
        assertEquals("PENDING", retry.getSyncStatus()); assertEquals(1, bindings.selectDueMaintenance(LocalDateTime.now()).size());
        verifyNoInteractions(client, tasks);
    }

    @Test void enablingADeadOrOutOfRangeCronIsRejectedBeforeRemoteCalls() {
        var snapshot = snapshot(); snapshot.getTask().setCronExpression("0 0 0 1 1 ? 1970");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> coordinator.validateTarget(snapshot));
        snapshot.getTask().setCronExpression("0 0 0 1 1 ? 2100");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> coordinator.validateTarget(snapshot));
        snapshot.getTask().setCronExpression("0 * * * * ?"); assertDoesNotThrow(() -> coordinator.validateTarget(snapshot));
        verifyNoInteractions(client, tasks); assertEquals(0, count());
    }

    private DevScheduleDO binding(int generation, boolean enabled) {
        var value = new DevScheduleDO(); value.setTaskId(1L); value.setVersionId(80L); value.setGeneration(generation);
        value.setCronExpression("0 * * * * ?"); value.setTimezoneId("Asia/Shanghai"); value.setDsProjectCode(19L); value.setWorkerGroup("controlled"); value.setTenantCode("isolated");
        value.setDesiredEnabled(enabled); value.setSyncedEnabled(false); value.setSyncStatus("PENDING"); value.setAttempts(0);
        value.setLastDsInstanceId(0L); value.setPollPageNo(1); value.setPollHighWaterId(0L); value.setScheduleStartTime(LocalDateTime.of(2026, 10, 1, 0, 0)); return value;
    }
    private DevTaskSnapshot snapshot() { var value = new DevTaskSnapshot(); value.setTask(DevTaskDO.builder().id(1L).taskType("SHELL").build()); value.setWorkerGroup("controlled"); value.setTenantCode("isolated"); return value; }
    private void wireRemote(DevScheduleDO binding, String release) {
        when(definitions.queryWorkflowDefinitionByCode(19L, Long.MAX_VALUE)).thenReturn(ok(dag(binding)));
        when(schedules.queryScheduleListPaging(19L, Long.MAX_VALUE, null, 1, 100)).thenReturn(page(List.of(schedule(binding, release))));
        when(schedules.publishScheduleOnline(19L, 12)).thenReturn(ok(null)); when(schedules.offlineSchedule(19L, 12)).thenReturn(ok(null));
        when(definitions.releaseWorkflowDefinition(eq(19L), eq(Long.MAX_VALUE), eq("ONLINE"), anyString())).thenReturn(ok(null));
        when(instances.queryWorkflowInstanceList(eq(19L), any())).thenReturn(page(List.of()));
    }
    private JSONObject dag(DevScheduleDO binding) {
        return new JSONObject().set("workflowDefinition", new JSONObject().set("code", Long.MAX_VALUE).set("version", 1).set("projectCode", 19L).set("name", name(binding)).set("globalParams", markers(binding).toString()))
                .set("taskDefinitionList", new JSONArray().put(new JSONObject().set("version", 1).set("taskType", "SHELL").set("flag", "YES").set("environmentCode", -1).set("workerGroup", "controlled")
                        .set("taskParams", new JSONObject().set("rawScript", "exit 0").set("localParams", new JSONArray()).set("resourceList", new JSONArray()).toString())));
    }
    private JSONObject schedule(DevScheduleDO binding, String release) { return new JSONObject().set("id", 12).set("workflowDefinitionCode", Long.MAX_VALUE).set("crontab", binding.getCronExpression()).set("timezoneId", "Asia/Shanghai").set("workerGroup", "controlled").set("tenantCode", "isolated").set("releaseState", release); }
    private JSONObject instance(DevScheduleDO binding, int id) { return new JSONObject().set("id", id).set("workflowDefinitionCode", Long.MAX_VALUE).set("workflowDefinitionVersion", 1).set("projectCode", 19L).set("workerGroup", "controlled").set("tenantCode", "isolated").set("commandType", "SCHEDULER").set("dryRun", 0).set("globalParams", markers(binding).toString()).set("state", "SUCCESS").set("scheduleTime", "2026-10-13 23:59:00"); }
    private String name(DevScheduleDO binding) { return "dh_dev_tick_7_1_" + binding.getGeneration(); }
    private JSONArray markers(DevScheduleDO binding) { var result = new JSONArray(); Map.of("tenant", 7L, "task", 1L, "version", 80L, "generation", binding.getGeneration().longValue()).forEach((key, value) -> result.put(new JSONObject().set("prop", "dh_" + key).set("value", value.toString()))); return result; }
    private JSONObject page(List<JSONObject> rows) { return ok(new JSONObject().set("total", rows.size()).set("totalList", new JSONArray(rows))); }
    private JSONObject ok(Object data) { return new JSONObject().set("code", 0).set("data", data); }
    private long count() { return bindings.selectCount(null); }
    private void execute(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
    private void executeUnchecked(String sql) { try { execute(sql); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
