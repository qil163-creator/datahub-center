package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunCommandMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real production mappers and Spring transactions, isolated loopback PG schemas only. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class AccessRunCoordinatorPostgreSqlTest {
    private Connection control;
    private String schema;
    private String baseline;
    private Object coordinator;
    private DataAccessMapper tasks;
    private DataAccessTaskMapper runs;
    private DataAccessTaskDetailMapper details;
    private DataAccessIncreaseLogMapper watermarks;
    private DataAccessCheckpointMapper checkpoints;
    private RunExecutionProperties properties;
    private AccessGroupRewriteCoordinator groupRecovery;
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;
    private final LocalDateTime scheduled = LocalDateTime.of(2026, 1, 1, 0, 0);

    @BeforeEach
    void setup() throws Exception {
        Class<?> type;
        try { type = Class.forName("cn.iocoder.yudao.module.dataforge.service.access.AccessRunCoordinator"); }
        catch (ClassNotFoundException missing) { fail("ACCESS has no durable transactional coordinator", missing); return; }
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "access_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        for (String table : List.of("dh_dataforge_access", "dh_dataforge_access_task", "dh_dataforge_access_task_detail",
                "dh_dataforge_access_increase_log", "dh_dataforge_etl_pipeline", "dh_dataforge_etl_record")) {
            var ddl = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table + "\" \\(.*?\\n\\)\\s*;", Pattern.DOTALL).matcher(baseline);
            assertTrue(ddl.find(), table);
            sql(ddl.group().replace("\"datahub-center\".", "").replaceAll(" DEFAULT nextval\\([^\\n]*?::regclass\\)", ""));
            var primaryKey = Pattern.compile("ALTER TABLE \"datahub-center\"[.]\"" + table
                    + "\" ADD CONSTRAINT \"[^\"]+\" PRIMARY KEY [^;]+;").matcher(baseline);
            assertTrue(primaryKey.find(), table + " 主键");
            sql(primaryKey.group().replace("\"datahub-center\"", schema));
        }
        // 历史增量已收敛至当前基线，只提取本类依赖的结构，保持真实约束与事务且不导入种子数据。
        sql(baselineSection("-- Migration: 0038 ", "-- Migration: 0039 "));
        sql(baselineSection("-- Migration: 0044 ", "-- 数据记录： dh_dataforge_etl_record"));
        applyCheckpointBaseline();
        for (String marker : List.of("DB SWITCH GROUP RECOVERY 0107", "DB SWITCH GROUP COMMAND 0108")) {
            String structure = baselineSection("-- BEGIN " + marker, "-- END " + marker);
            sql(structure); sql(structure);
        }
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var config = new MybatisConfiguration();
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("access-test", new SpringManagedTransactionFactory(), source));
        for (Class<?> mapper : List.of(DataAccessMapper.class, DataAccessTaskMapper.class, RunCommandMapper.class,
                DataAccessTaskDetailMapper.class, DataAccessIncreaseLogMapper.class, DataAccessCheckpointMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        tasks = session.getMapper(DataAccessMapper.class);
        runs = session.getMapper(DataAccessTaskMapper.class);
        details = session.getMapper(DataAccessTaskDetailMapper.class);
        watermarks = session.getMapper(DataAccessIncreaseLogMapper.class);
        checkpoints = session.getMapper(DataAccessCheckpointMapper.class);
        properties = new RunExecutionProperties();
        coordinator = type.getConstructor().newInstance();
        ReflectionTestUtils.setField(coordinator, "metrics",
                new cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ReflectionTestUtils.setField(coordinator, "tasks", tasks);
        ReflectionTestUtils.setField(coordinator, "runs", runs);
        ReflectionTestUtils.setField(coordinator, "commands", session.getMapper(RunCommandMapper.class));
        ReflectionTestUtils.setField(coordinator, "properties", properties);
        ReflectionTestUtils.setField(coordinator, "transactions", new TransactionTemplate(new DataSourceTransactionManager(source)));
        ReflectionTestUtils.setField(coordinator, "authorization", mock(Class.forName("cn.iocoder.yudao.module.dataforge.service.access.AccessRunAuthorization")));
        groupRecovery = mock(AccessGroupRewriteCoordinator.class);
        ReflectionTestUtils.setField(coordinator, "groupRewriteCoordinator", groupRecovery);
        ReflectionTestUtils.setField(coordinator, "dataSource", source);
        context();
        sql("INSERT INTO dh_dataforge_access(id,task_name,engine_type,target_database_id,task_type,status,revision,published_revision,schedule_generation,published_execution_user_id,published_config_json,next_run_time,cron,creator) VALUES(10,'original',3,20,3,1,1,1,3,99,'{\"id\":10,\"taskName\":\"original\",\"engineType\":3,\"targetDatabaseId\":20,\"apiAccessJson\":\"{\\\"mappings\\\":[]}\",\"cron\":\"0 * * * * *\"}','2026-01-01','0 * * * * *','99')");
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext();
        if (dbSwitchRuntime != null) dbSwitchRuntime.close();
        if (control != null) try {
            if (schema != null && schema.matches("access_[a-f0-9]{32}")) {
                sql("ROLLBACK"); sql("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally { control.close(); }
    }

    @Test void occurrenceAndAdvanceAreAtomicAndCatchUpUsesOriginalTime() {
        var first = scheduled(); var replay = scheduled();
        assertEquals(first.recordId(), replay.recordId()); assertTrue(replay.replayed());
        assertEquals(scheduled.plusMinutes(1), tasks.selectById(10L).getNextRunTime());
        var record = runs.selectById(first.recordId());
        assertEquals(1, record.getProtocolVersion()); assertFalse(record.getOccupiesSlot());
        assertEquals(99L, record.getExecutionUserId()); assertEquals(3L, record.getScheduleGeneration());
        assertTrue(record.getRequestHash().matches("[a-f0-9]{64}"));
    }

    @Test void failedInsertNeverAdvancesSchedule() throws Exception {
        reject("dh_dataforge_access_task", "INSERT");
        assertThrows(RuntimeException.class, this::scheduled);
        assertEquals(scheduled, tasks.selectById(10L).getNextRunTime()); assertEquals(0, runs.selectCount(null));
    }

    @Test void failedAdvanceRollsBackInsertedRun() throws Exception {
        reject("dh_dataforge_access", "UPDATE");
        assertThrows(RuntimeException.class, this::scheduled);
        assertEquals(scheduled, tasks.selectById(10L).getNextRunTime()); assertEquals(0, runs.selectCount(null));
    }

    @Test void concurrentSchedulersAcceptOneOccurrence() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<RunReceipt> action = () -> { context(); try { assertTrue(gate.await(5, TimeUnit.SECONDS)); return scheduled(); }
                finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); } };
            var a = pool.submit(action); var b = pool.submit(action); gate.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS).recordId(), b.get(10, TimeUnit.SECONDS).recordId());
        }
        assertEquals(1, runs.selectCount(null));
    }

    @Test void backpressureAndStaleGenerationLeaveDueTimeUntouched() {
        properties.setPerTaskPending(1); manual("one");
        assertTrue(this.<Optional<RunReceipt>>call("enqueueScheduled", 10L, 3L, scheduled).isEmpty());
        assertEquals(scheduled, tasks.selectById(10L).getNextRunTime());
        assertTrue(this.<Optional<RunReceipt>>call("enqueueScheduled", 10L, 2L, scheduled).isEmpty());
    }

    @Test void replayKeepsFrozenSnapshotAndDeletedIdentityCannotExecuteAgain() throws Exception {
        var first = manual("one"); String snapshot = runs.selectById(first.recordId()).getConfigJson();
        sql("UPDATE dh_dataforge_access SET published_config_json='{}',revision=2");
        assertEquals(first.recordId(), manual("one").recordId());
        assertEquals(snapshot, runs.selectById(first.recordId()).getConfigJson());
        sql("UPDATE dh_dataforge_access_task SET deleted=1");
        assertThrows(RuntimeException.class, () -> manual("one")); assertEquals(1, countAll());
    }

    @Test void expiredOwnerCannotRenewStartOrWriteChildren() throws Exception {
        var first = manual("one"); var lease = claim(first);
        expire();
        assertFalse(this.<Boolean>call("renew", lease));
        assertFalse(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        assertFalse(this.<Boolean>call("writeOwned", lease, (Runnable) () -> fail("stale child callback ran")));
        assertTrue(this.<Boolean>call("recoverExpiredRecord", first.recordId()));
        var record = runs.selectById(first.recordId());
        assertEquals(1, record.getRunStatus()); assertFalse(record.getOccupiesSlot());
        assertNotNull(record.getNextAttemptAt()); assertNull(record.getExecutionToken());
    }

    @Test void startedExpiryAndPartialTerminationKeepSlot() throws Exception {
        var one = manual("one"); var two = manual("two"); var lease = claim(one);
        assertTrue(this.<Optional<RunLease>>call("claim", two.recordId(), "node-b").isEmpty());
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED)); expire();
        assertTrue(this.<Boolean>call("recoverExpiredRecord", one.recordId()));
        assertEquals(5, runs.selectById(one.recordId()).getRunStatus());
        assertTrue(runs.selectById(one.recordId()).getOccupiesSlot());
        assertTrue(this.<Optional<RunLease>>call("claim", two.recordId(), "node-b").isEmpty());
    }

    @Test void partialConfirmedExitRetainsReviewGate() {
        var run = manual("one"); var lease = claim(run);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var result = DataAccessTaskDO.builder().id(run.recordId()).runStatus(6).build();
        assertTrue(this.<Boolean>call("finish", lease, result, true, RunProtocol.Effect.PARTIAL));
        assertEquals(6, runs.selectById(run.recordId()).getRunStatus());
        assertTrue(runs.selectById(run.recordId()).getReviewRequired());
        assertTrue(runs.selectById(run.recordId()).getOccupiesSlot());
    }

    @Test void independentStopIsDurableAndReceiptCannotRetargetAnotherRun() {
        var run = manual("one"); var lease = claim(run);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        RunReceipt stop = call("requestStop", run.recordId(), "stop-1", "operator stop");
        assertEquals(2, stop.runStatus()); assertTrue(stop.stopRequested());
        assertTrue(this.<RunReceipt>call("requestStop", run.recordId(), "stop-1", "operator stop").replayed());
        var two = manual("two");
        assertThrows(RuntimeException.class, () -> call("requestStop", two.recordId(), "stop-1", "operator stop"));
        assertFalse(runs.selectById(two.recordId()).getStopRequested());
    }

    @Test void waitingStopConfirmsNoEffectsAndMissingRunCannotBeStopped() {
        var run = manual("one");
        RunReceipt stop = call("requestStop", run.recordId(), "stop-1", "operator stop");
        assertEquals(6, stop.runStatus()); assertFalse(runs.selectById(run.recordId()).getOccupiesSlot());
        TenantContextHolder.setTenantId(2L);
        assertThrows(RuntimeException.class, () -> call("requestStop", -1L, "stop-2", "operator stop"));
        assertTrue(this.<Optional<RunLease>>call("claim", run.recordId(), "node-b").isEmpty());
    }

    @Test void manualResolutionNeedsVersionAndBothEvidenceGates() throws Exception {
        var run = manual("one"); var lease = claim(run);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED)); expire();
        call("recoverExpiredRecord", run.recordId()); long version = runs.selectById(run.recordId()).getRowVersion();
        assertThrows(RuntimeException.class, () -> resolve(run, version, false, true));
        assertThrows(RuntimeException.class, () -> resolve(run, version, true, false));
        assertThrows(RuntimeException.class, () -> resolve(run, version - 1, true, true));
        RunReceipt result = resolve(run, version, true, true);
        assertEquals(6, result.runStatus()); assertFalse(runs.selectById(run.recordId()).getOccupiesSlot());
        assertTrue(resolve(run, version, true, true).replayed());
    }

    @Test void publishingOneTimeTaskAndRunIsOneTransaction() throws Exception {
        sql("UPDATE dh_dataforge_access SET status=0,task_type=2,published_config_json=NULL,api_access_json='{\"mappings\":[]}'");
        reject("dh_dataforge_access_task", "INSERT");
        assertThrows(RuntimeException.class, () -> call("publish", 10L));
        assertEquals(0, tasks.selectById(10L).getStatus()); assertEquals(0, runs.selectCount(null));
        sql("DROP TRIGGER reject_change ON dh_dataforge_access_task");
        call("publish", 10L); call("publish", 10L);
        assertEquals(1, tasks.selectById(10L).getStatus()); assertEquals(1, runs.selectCount(null));
        assertNotNull(tasks.selectById(10L).getPublishedConfigJson());
    }

    @Test void unpublishStopsOnlyPreparationAndCannotEraseStartedSlot() {
        var a = manual("a"); var b = manual("b"); var lease = claim(a);
        call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED);
        call("unpublish", 10L);
        assertEquals(0, tasks.selectById(10L).getStatus());
        assertEquals(6, runs.selectById(b.recordId()).getRunStatus());
        assertEquals(2, runs.selectById(a.recordId()).getRunStatus());
        assertTrue(runs.selectById(a.recordId()).getStopRequested());
        assertTrue(runs.selectById(a.recordId()).getOccupiesSlot());
        assertThrows(RuntimeException.class, () -> call("deleteRuns", List.of(a.recordId())));
    }

    @Test void staleReconcilerCannotOverwriteManualResolution() throws Exception {
        var one = manual("one"); var lease = claim(one);
        call("startEffects", lease, RunProtocol.Stage.SUBMIT_SENT); expire(); call("recoverExpiredRecord", one.recordId());
        Object reconciliation = this.<Optional<?>>call("claimReconciliation", one.recordId()).orElseThrow();
        long version = runs.selectById(one.recordId()).getRowVersion();
        resolve(one, version, true, true);
        assertFalse(this.<Boolean>call("completeReconciliation", reconciliation, "job-123", 3, RunProtocol.Effect.COMPLETE, true, false));
        assertEquals(6, runs.selectById(one.recordId()).getRunStatus());
    }

    @Test void historicKnownRemoteJobUsesPersistedAuthorizedStopActorWithoutNewSnapshot() throws Exception {
        var receipt = manual("historic-remote");
        sql("UPDATE dh_dataforge_access_task SET protocol_version=0,execution_user_id=NULL,config_json=NULL,"
                + "run_status=5,execution_stage='SUBMIT_SENT',effect_state='UNKNOWN',occupies_slot=true,review_required=true,"
                + "seatunnel_job_id='historic-job' WHERE id=" + receipt.recordId());
        call("requestStop", receipt.recordId(), "historic-stop", "stop old remote job");
        var authorization = (AccessRunAuthorization) ReflectionTestUtils.getField(coordinator, "authorization");
        org.mockito.Mockito.doAnswer(inv -> {
            assertEquals(99L, inv.getArgument(1, Long.class), "stop reconciliation must use the authorized persisted stop actor");
            return null;
        }).when(authorization).action(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        try (var remote = org.mockito.Mockito.mockStatic(cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.class)) {
            remote.when(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.getJobInfo("historic-job"))
                    .thenReturn(cn.hutool.json.JSONUtil.parseObj("{\"jobId\":\"historic-job\",\"jobStatus\":\"CANCELED\"}"));
            assertTrue(this.<Boolean>call("reconcileRecord", receipt.recordId()));
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.stopJob(org.mockito.ArgumentMatchers.anyString()));
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.submitJob(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()), org.mockito.Mockito.never());
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.submitJob(
                    anyString(), anyString(), anyString()), never());
        }
        assertEquals(6, runs.selectById(receipt.recordId()).getRunStatus());
        assertTrue(runs.selectById(receipt.recordId()).getOccupiesSlot(), "partial output still requires review");
    }

    @Test void revokedNativeSeatunnelReconciliationStopsKnownJobWithoutSubmittingAgain() throws Exception {
        var receipt = manual("native-revoked");
        var definition = tasks.selectById(10L);
        definition.setEngineType(2); definition.setTaskType(1);
        definition.setConfigFormat("json");
        definition.setSeatunnelConfig("{\"env\":{},\"source\":[{\"plugin_name\":\"Kafka\"}],\"sink\":[{\"plugin_name\":\"Console\"}]}");
        try (var statement = control.prepareStatement("UPDATE dh_dataforge_access_task SET config_json=?::jsonb,"
                + "run_status=5,execution_stage='SUBMIT_SENT',effect_state='UNKNOWN',occupies_slot=true,review_required=true,"
                + "seatunnel_job_id='managed-job' WHERE id=?")) {
            statement.setString(1, AccessRunSnapshot.freeze(definition));
            statement.setLong(2, receipt.recordId());
            assertEquals(1, statement.executeUpdate());
        }
        var authorization = (AccessRunAuthorization) ReflectionTestUtils.getField(coordinator, "authorization");
        clearInvocations(authorization);
        doThrow(new SecurityException("运行权限已撤销")).when(authorization).execution(any(), eq(99L));
        doThrow(new SecurityException("运行权限已撤销")).when(authorization).action(any(), any(), anyString());
        try (var remote = mockStatic(cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.class)) {
            remote.when(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.getJobInfo("managed-job"))
                    .thenReturn(cn.hutool.json.JSONUtil.parseObj("{\"jobId\":\"managed-job\",\"jobStatus\":\"RUNNING\"}"),
                            cn.hutool.json.JSONUtil.parseObj("{\"jobId\":\"managed-job\",\"jobStatus\":\"CANCELED\"}"));
            assertTrue(this.<Boolean>call("reconcileRecord", receipt.recordId()));
            assertEquals(5, runs.selectById(receipt.recordId()).getRunStatus(), "停止请求不等于远端作业已经退出");
            assertTrue(runs.selectById(receipt.recordId()).getOccupiesSlot());
            sql("UPDATE dh_dataforge_access_task SET next_reconcile_at=NULL WHERE id=" + receipt.recordId());
            assertTrue(this.<Boolean>call("reconcileRecord", receipt.recordId()));
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.stopJob(argThat(body -> {
                var request = cn.hutool.json.JSONUtil.parseObj(body);
                return "managed-job".equals(request.getStr("jobId")) && Boolean.FALSE.equals(request.getBool("isStopWithSavePoint"));
            })), times(2));
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.submitJob(anyString(), anyString()), never());
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.submitJob(anyString(), anyString(), anyString()), never());
            remote.verify(() -> cn.iocoder.yudao.framework.seatunnel.SeatunnelRestApi.getAllJobs(), never());
        }
        verify(authorization, times(2)).execution(argThat(task -> Integer.valueOf(2).equals(task.getEngineType())
                && task.getSourceDatabaseId() == null && task.getTargetDatabaseId() == null), eq(99L));
        verify(authorization, never()).action(any(), any(), anyString());
        var stopped = runs.selectById(receipt.recordId());
        assertEquals(6, stopped.getRunStatus());
        assertTrue(stopped.getReviewRequired());
        assertTrue(stopped.getOccupiesSlot(), "远端停止后仍须核查部分写入，不能自动释放占用");
    }

    @Test void acceptedQueueSurvivesLocalExecutorRejection() {
        var run = manual("one");
        var executor = new DataAccessExecutor();
        var pool = mock(java.util.concurrent.ExecutorService.class);
        org.mockito.Mockito.when(pool.submit(org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("full"));
        ReflectionTestUtils.setField(executor, "coordinator", coordinator);
        ReflectionTestUtils.setField(executor, "executor", pool);
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(executor, "submit", run.recordId())));
        DataAccessTaskDO persisted = runs.selectById(run.recordId());
        assertEquals(1, persisted.getRunStatus()); assertFalse(persisted.getOccupiesSlot());
        assertNotNull(persisted.getNextAttemptAt());
        assertEquals(0, persisted.getAttemptCount(), "capacity rejection is not a failed execution attempt");
    }

    @Test void delayedOldCallbackCannotInsertRealDetailsOrWatermarksAfterReclaim() throws Exception {
        var one = manual("one"); var old = claim(one);
        expire(); call("recoverExpiredRecord", one.recordId());
        sql("UPDATE dh_dataforge_access_task SET next_attempt_at=clock_timestamp()-interval '1 second'");
        var replacement = claim(one); assertNotEquals(old.token(), replacement.token());
        Runnable writes = () -> {
            details.insert(cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO.builder()
                    .dataAccessId(10L).taskId(one.recordId()).sourceTableName("orders").targetTableName("orders")
                    .ifSuccess(1).syncCount(5L).build());
            watermarks.insert(cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessIncreaseLogDO.builder()
                    .dataAccessId(10L).tableName("orders").increaseColumn("id").endVal("5").build());
        };
        assertFalse(this.<Boolean>call("writeOwned", old, writes));
        assertEquals(0, details.selectCount(null)); assertEquals(0, watermarks.selectCount(null));
        assertTrue(this.<Boolean>call("writeOwned", replacement, writes));
        assertEquals(1, details.selectCount(null)); assertEquals(1, watermarks.selectCount(null));
    }

    @Test void scanIsBoundedAndAuthorizesTheFrozenActor() {
        var auth = (AccessRunAuthorization) ReflectionTestUtils.getField(coordinator, "authorization");
        org.mockito.Mockito.doAnswer(inv -> {
            assertFalse(TenantContextHolder.isIgnore());
            assertEquals(99L, inv.getArgument(1, Long.class));
            return null;
        }).when(auth).execution(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        TenantContextHolder.setTenantId(77L);
        assertEquals(1, this.<Integer>call("scanScheduled", 1));
        assertEquals(77L, TenantContextHolder.getRequiredTenantId()); assertFalse(TenantContextHolder.isIgnore());
        assertEquals(1, runs.selectCount(null));
    }

    @Test void reconciliationNormalPollingDoesNotConsumeFailureBudget() throws Exception {
        var one = manual("one"); var lease = claim(one);
        call("startEffects", lease, RunProtocol.Stage.SUBMIT_SENT); expire(); call("recoverExpiredRecord", one.recordId());
        Object reconciliation = this.<Optional<?>>call("claimReconciliation", one.recordId()).orElseThrow();
        assertTrue(this.<Boolean>call("completeReconciliation", reconciliation, "job-1", null, RunProtocol.Effect.UNKNOWN, false, false));
        assertEquals(0, runs.selectById(one.recordId()).getReconcileAttempts());
        assertTrue(runs.selectById(one.recordId()).getOccupiesSlot());
    }

    @Test void apiWriterUsesFrozenMappingAndConfirmsRealPostgresCleanup() throws Exception {
        apiWriterScenario(false);
    }

    @Test void timedOutHttpCannotBeDeclaredTerminatedByPoolClosure() throws Exception {
        apiWriterScenario(true);
    }

    private void apiWriterScenario(boolean ambiguousHttp) throws Exception {
        apiWriterScenario(ambiguousHttp, false);
    }

    @Test void targetWriteExceptionRemainsUnknownDespitePoolClosure() throws Exception {
        apiWriterScenario(false, true);
    }

    private void apiWriterScenario(boolean ambiguousHttp, boolean badTarget) throws Exception {
        sql("CREATE TABLE access_output(value varchar(" + (badTarget ? 1 : 64) + "))");
        var frozen = cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO.builder().id(10L)
                .engineType(3).taskName("frozen-api").targetDatabaseId(20L).taskType(2)
                .apiAccessJson("{\"syncExist\":true,\"mappings\":[{\"apiConfig\":{\"apiName\":\"独立接口\",\"requestUrl\":\"https://example.invalid/data\",\"requestMethod\":\"GET\",\"ifPage\":0,\"dataColumns\":[{\"fieldName\":\"value\",\"fieldType\":12,\"displaySize\":64}]},\"targetTable\":\"access_output\",\"columnMaps\":[{\"sourceColumn\":\"value\",\"targetColumn\":\"value\",\"importFlg\":1}]}]}").build();
        try (var statement = control.prepareStatement("UPDATE dh_dataforge_access SET published_config_json=CAST(? AS jsonb)")) {
            statement.setString(1, AccessRunSnapshot.freeze(frozen)); statement.executeUpdate();
        }
        var run = manual("api-one");
        sql("UPDATE dh_dataforge_access SET published_config_json='{}',api_access_json='{}'");
        var executor = new DataAccessExecutor();
        ReflectionTestUtils.setField(executor, "coordinator", coordinator);
        ReflectionTestUtils.setField(executor, "authorization", ReflectionTestUtils.getField(coordinator, "authorization"));
        ReflectionTestUtils.setField(executor, "properties", properties);
        ReflectionTestUtils.setField(executor, "taskMapper", runs);
        ReflectionTestUtils.setField(executor, "taskDetailMapper", details);
        dbSwitchRuntime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", dbSwitchRuntime);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(20L); connection.setSchema(schema);
        connection.setJdbcUrl(System.getProperty("datahub.test.postgres.url"));
        connection.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        connection.setPassword(""); connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        var resolver = mock(AccessDatabaseResolver.class);
        org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(connection);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        var client = mock(cn.iocoder.yudao.module.dataforge.service.dataapi.DataApiClient.class);
        org.mockito.Mockito.when(client.fetchRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(Map.of("value", "accepted")));
        if (ambiguousHttp) org.mockito.Mockito.when(client.fetchRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("HTTP response lost"));
        ReflectionTestUtils.setField(executor, "dataApiClient", client);
        var pool = mock(ExecutorService.class);
        org.mockito.Mockito.when(pool.submit(org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run(); return CompletableFuture.completedFuture(null);
        });
        ReflectionTestUtils.setField(executor, "executor", pool);
        assertTrue(executor.submit(run.recordId()));
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).fetchRows(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        if (ambiguousHttp || badTarget) {
            assertEquals(5, runs.selectById(run.recordId()).getRunStatus());
            assertEquals("UNKNOWN", runs.selectById(run.recordId()).getEffectState());
            assertTrue(runs.selectById(run.recordId()).getOccupiesSlot());
            assertTrue(runs.selectById(run.recordId()).getReviewRequired());
            return;
        }
        assertEquals(3, runs.selectById(run.recordId()).getRunStatus());
        assertFalse(runs.selectById(run.recordId()).getOccupiesSlot());
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT value FROM access_output")) {
            assertTrue(result.next()); assertEquals("accepted", result.getString(1)); assertFalse(result.next());
        }
        assertEquals(1, details.selectCount(null));
    }

    @Test void groupRecoveryAdmissionAndCommandCommitBeforeSingleDispatchAndReplay() throws Exception {
        var record = interruptedGroupRun();
        var worker = recoveryWorker();
        var admission = mock(AccessGroupRewriteCoordinator.RecoveryAdmission.class);
        var handle = mock(AccessGroupRewriteCoordinator.RecoveryHandle.class);
        var prepared = mock(DataAccessExecutor.PreparedGroupRecovery.class);
        when(prepared.admission()).thenReturn(admission);
        when(worker.prepareGroupRecovery(any(), any(), anyString())).thenReturn(prepared);
        when(groupRecovery.admitRecovery(any(), same(admission))).thenAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            assertFalse(connection.getAutoCommit());
            try (var statement = connection.createStatement(); var result = statement.executeQuery(
                    "SELECT reconcile_token,stop_requested FROM dh_dataforge_access_task WHERE id=" + record.getId())) {
                assertTrue(result.next()); assertNotNull(result.getString(1)); assertFalse(result.getBoolean(2));
            }
            assertNull(runsOnControl(record.getId(), "reconcile_token"), "claim must not be visible before commit");
            return handle;
        });
        RunReceipt accepted = call("recoverGroups", record.getId(), "group-1", record.getRowVersion(), "recover", "audit:group");
        assertFalse(accepted.replayed()); assertEquals(5, accepted.runStatus());
        assertNotNull(runsOnControl(record.getId(), "reconcile_token"));
        var run = runs.selectById(record.getId());
        assertFalse(run.getStopRequested()); assertTrue(run.getOccupiesSlot()); assertTrue(run.getReviewRequired());
        verify(worker).submitGroupRecovery(eq(record.getId()), eq(run.getReconcileToken()), eq(99L), same(prepared), same(handle));
        RunReceipt replay = call("recoverGroups", record.getId(), "group-1", record.getRowVersion(), "recover", "audit:group");
        assertTrue(replay.replayed());
        verify(worker, times(1)).submitGroupRecovery(anyLong(), anyString(), anyLong(), any(), any());
        verify(groupRecovery, times(1)).admitRecovery(any(), any());
        assertThrows(RuntimeException.class, () -> call("recoverGroups", record.getId(), "group-1", record.getRowVersion(), "different", "audit:group"));
        try (var statement = control.createStatement(); var result = statement.executeQuery(
                "SELECT action,before_json->>'stopRequested',after_json->'state'->>'stopRequested' FROM dh_dataforge_run_command WHERE request_id='group-1'")) {
            assertTrue(result.next()); assertEquals("GROUP_RECOVER", result.getString(1));
            assertEquals("true", result.getString(2)); assertEquals("false", result.getString(3)); assertFalse(result.next());
        }
    }

    @Test void lostAdmissionCommitResponseRetainsZeroTargetCallDrainWitnessWithoutDispatch() throws Exception {
        var record = interruptedGroupRun(); var worker = recoveryWorker();
        var prepared = mock(DataAccessExecutor.PreparedGroupRecovery.class);
        when(prepared.admission()).thenReturn(mock(AccessGroupRewriteCoordinator.RecoveryAdmission.class));
        when(worker.prepareGroupRecovery(any(), any(), anyString())).thenReturn(prepared);
        var handle = mock(AccessGroupRewriteCoordinator.RecoveryHandle.class);
        when(groupRecovery.admitRecovery(any(), any())).thenReturn(handle);
        TransactionTemplate original = (TransactionTemplate) ReflectionTestUtils.getField(coordinator, "transactions");
        var loseResponse = new java.util.concurrent.atomic.AtomicBoolean(true);
        var unreliable = new TransactionTemplate() {
            @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) {
                T result = original.execute(callback);
                if (loseResponse.compareAndSet(true, false)) throw new IllegalStateException("commit response lost");
                return result;
            }
        };
        ReflectionTestUtils.setField(coordinator, "transactions", unreliable);
        assertThrows(IllegalStateException.class, () -> call("recoverGroups", record.getId(), "group-unknown-commit",
                record.getRowVersion(), "recover", "audit:commit"));
        verify(handle).finished(true);
        verify(worker, never()).submitGroupRecovery(anyLong(), anyString(), anyLong(), any(), any());
        assertNotNull(runs.commandIdentity( "group-unknown-commit"));
        var run = runs.selectById(record.getId());
        assertNull(run.getReconcileToken()); assertTrue(run.getOccupiesSlot()); assertTrue(run.getReviewRequired());
        RunReceipt replay = call("recoverGroups", record.getId(), "group-unknown-commit", record.getRowVersion(), "recover", "audit:commit");
        assertTrue(replay.replayed());
        verify(worker, never()).submitGroupRecovery(anyLong(), anyString(), anyLong(), any(), any());
    }

    @Test void rejectedGroupAdmissionRollsBackNewOwnerStopResetAndCommand() throws Exception {
        var record = interruptedGroupRun();
        var worker = recoveryWorker();
        var prepared = mock(DataAccessExecutor.PreparedGroupRecovery.class);
        when(prepared.admission()).thenReturn(mock(AccessGroupRewriteCoordinator.RecoveryAdmission.class));
        when(worker.prepareGroupRecovery(any(), any(), anyString())).thenReturn(prepared);
        when(groupRecovery.admitRecovery(any(), any())).thenThrow(new IllegalStateException("prior executor not drained"));
        assertThrows(IllegalStateException.class, () -> call("recoverGroups", record.getId(), "group-rejected", record.getRowVersion(), "recover", "audit:group"));
        var run = runs.selectById(record.getId());
        assertEquals(record.getRowVersion(), run.getRowVersion()); assertTrue(run.getStopRequested());
        assertNull(run.getReconcileToken()); assertNull(run.getReconcileLeaseUntil());
        assertNull(runs.commandIdentity( "group-rejected"));
        verify(worker, never()).submitGroupRecovery(anyLong(), anyString(), anyLong(), any(), any());
    }

    @Test void serverGroupGuardPreventsBooleanResolutionBypassWithinSameTransaction() throws Exception {
        var record = interruptedGroupRun();
        doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            assertFalse(connection.getAutoCommit());
            assertEquals(10L, invocation.getArgument(1, Long.class));
            throw new IllegalStateException("pending group");
        }).when(groupRecovery).assertResolved(any(), eq(10L), eq(record.getId()));
        assertThrows(IllegalStateException.class, () -> call("resolve", record.getId(), "bypass", record.getRowVersion(),
                "SUCCESS", true, true, "user says stopped", "audit:unchecked"));
        assertTrue(runs.selectById(record.getId()).getOccupiesSlot());
        assertEquals(5, runs.selectById(record.getId()).getRunStatus());
        assertNull(runs.commandIdentity( "bypass"));
    }

    @Test void groupHeartbeatStopAndCompletionDoNotReleaseOriginalOutputReview() throws Exception {
        var record = interruptedGroupRun();
        sql("UPDATE dh_dataforge_access_task SET reconcile_token='recovery-token',reconcile_lease_until=clock_timestamp()+interval '2 minutes',stop_requested=false WHERE id=" + record.getId());
        assertTrue(this.<Boolean>call("renewGroupRecovery", record.getId(), "recovery-token"));
        assertFalse(this.<Boolean>call("renewGroupRecovery", record.getId(), "old-token"));
        assertFalse(this.<Boolean>call("shouldStopGroupRecovery", record.getId(), "recovery-token"));
        call("requestStop", record.getId(), "stop-recovery", "stop new recovery");
        assertTrue(this.<Boolean>call("shouldStopGroupRecovery", record.getId(), "recovery-token"));
        assertFalse(this.<Boolean>call("renewGroupRecovery", record.getId(), "recovery-token"));
        call("finishGroupRecovery", record.getId(), "old-token", true, true);
        assertEquals("recovery-token", runs.selectById(record.getId()).getReconcileToken());
        call("finishGroupRecovery", record.getId(), "recovery-token", true, true);
        var run = runs.selectById(record.getId());
        assertNull(run.getReconcileToken()); assertEquals(5, run.getRunStatus());
        assertTrue(run.getOccupiesSlot()); assertTrue(run.getReviewRequired()); assertEquals("UNKNOWN", run.getEffectState());
    }

    private DataAccessTaskDO interruptedGroupRun() {
        var receipt = manual("group-source"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        assertTrue(this.<Boolean>call("finish", lease, DataAccessTaskDO.builder().runStatus(5).build(), false, RunProtocol.Effect.UNKNOWN));
        call("requestStop", receipt.recordId(), "stop-old-group", "stop old execution");
        return runs.selectById(receipt.recordId());
    }

    @SuppressWarnings("unchecked")
    private DataAccessExecutor recoveryWorker() {
        var worker = mock(DataAccessExecutor.class);
        var provider = (org.springframework.beans.factory.ObjectProvider<DataAccessExecutor>) mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(worker);
        ReflectionTestUtils.setField(coordinator, "executor", provider);
        return worker;
    }

    private String runsOnControl(Long id, String column) throws SQLException {
        assertEquals("reconcile_token", column);
        try (var statement = control.createStatement(); var result = statement.executeQuery(
                "SELECT reconcile_token FROM dh_dataforge_access_task WHERE id=" + id)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }

    private RunReceipt resolve(RunReceipt r, long version, boolean stopped, boolean reviewed) {
        return call("resolve", r.recordId(), "resolve-1", version, "STOPPED", stopped, reviewed, "reviewed", "evidence:case-1");
    }
    @Test void revokedActionCannotReplayRunOrStopReceipt() {
        var record = manual("auth-run");
        call("requestStop", record.recordId(), "auth-stop", "stop");
        var authorization = (AccessRunAuthorization) ReflectionTestUtils.getField(coordinator, "authorization");
        org.mockito.Mockito.doThrow(new SecurityException("revoked")).when(authorization)
                .action(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.anyString());
        assertThrows(SecurityException.class, () -> manual("auth-run"));
        assertThrows(SecurityException.class, () -> call("requestStop", record.recordId(), "auth-stop", "stop"));
    }

    @Test @Timeout(45)
    void twoIndependentJvmClaimsHaveOnlyOneDatabaseSlot() throws Exception {
        var record = manual("two-jvm");
        sql("CREATE TABLE access_claim_barrier(worker varchar(64) PRIMARY KEY)");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var logs = new ArrayList<Path>(); var arguments = new ArrayList<Path>(); var processes = new ArrayList<Process>();
        try {
            for (int i = 0; i < 2; i++) {
                Path log = Files.createTempFile("access-claim-", ".log"); logs.add(log);
                Path arg = Files.createTempFile("access-claim-", ".args"); arguments.add(arg);
                Files.writeString(arg, "-Ddatahub.test.postgres.user=" + System.getProperty("datahub.test.postgres.user", "dataforge_test") + "\n-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
                        + AccessClaimProcess.class.getName() + "\n" + System.getProperty("datahub.test.postgres.url")
                        + "\n" + schema + "\n" + record.recordId() + "\njvm-" + i + "\n");
                processes.add(new ProcessBuilder(java, "@" + arg)
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start());
            }
            for (int i = 0; i < processes.size(); i++) {
                assertTrue(processes.get(i).waitFor(25, TimeUnit.SECONDS), "child JVM timed out");
                assertEquals(0, processes.get(i).exitValue(), Files.readString(logs.get(i)));
            }
            long claimed = logs.stream().map(path -> {
                try { return Files.readString(path); } catch (Exception failure) { throw new RuntimeException(failure); }
            }).filter(text -> text.contains("ACCESS_CLAIM=true")).count();
            assertEquals(1, claimed); assertEquals(1, runs.countSlots( 10L));
        } finally {
            for (Process process : processes) if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            for (Path log : logs) Files.deleteIfExists(log);
            for (Path arg : arguments) Files.deleteIfExists(arg);
        }
    }

    @Test void manualSnapshotPersistsAuthorizedExpansionWithoutCredentials() throws Exception {
        sql("UPDATE dh_dataforge_access SET status=0");
        var authorization = (AccessRunAuthorization) ReflectionTestUtils.getField(coordinator, "authorization");
        org.mockito.Mockito.doAnswer(invocation -> {
            cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO definition = invocation.getArgument(0);
            definition.setApiAccessJson("{\"mappings\":[],\"frozenMarker\":true}");
            return null;
        }).when(authorization).execution(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(99L));
        var receipt = manual("freeze-authorized");
        assertTrue(runs.selectById(receipt.recordId()).getConfigJson().contains("frozenMarker"));
    }

    @Test void boundedScanIncludesAnotherSourceBehindAHotSourceBacklog() throws Exception {
        sql("INSERT INTO dh_dataforge_access_task(id,data_access_id,protocol_version,run_key,request_hash,"
                + "config_json,execution_user_id,schedule_generation,run_status,execution_stage,effect_state,resource_ids,create_time) "
                + "SELECT 1000+i,1000+i,1,'scan-'||i,repeat('a',64),'{}'::jsonb,99,0,1,'PREPARE','NONE','[42]'::jsonb,"
                + "clock_timestamp()-interval '1 minute'+i*interval '1 millisecond' "
                + "FROM generate_series(1,20) AS g(i)");
        sql("INSERT INTO dh_dataforge_access_task(id,data_access_id,protocol_version,run_key,request_hash,config_json,execution_user_id,schedule_generation,run_status,execution_stage,effect_state,resource_ids,create_time) VALUES(2000,2000,1,'scan-other',repeat('b',64),'{}'::jsonb,99,0,1,'PREPARE','NONE','[77]'::jsonb,clock_timestamp())");
        var candidates = runs.waitingCandidates(2, 32, 8).stream().map(DataAccessTaskDO::getId).toList();
        assertEquals(2, candidates.size());
        assertTrue(candidates.contains(2000L), "热点数据源的排队记录不能耗尽全部扫描名额");
    }

    @Test void laterClaimCannotOvertakeEarlierBackoffRun() throws Exception {
        var first = manual("fifo-1");
        sql("UPDATE dh_dataforge_access_task SET next_attempt_at=clock_timestamp()+interval '30 seconds' WHERE id=" + first.recordId());
        var later = manual("fifo-2");
        assertTrue(this.<Optional<RunLease>>call("claim", later.recordId(), "node-b").isEmpty());
        assertTrue(runs.waitingCandidates(10, 32, 8).isEmpty());
    }

    @Test void unsafeWaitingStageCannotBeClaimed() throws Exception {
        var receipt = manual("wrong-stage");
        sql("UPDATE dh_dataforge_access_task SET execution_stage='SUBMIT_SENT',effect_state='UNKNOWN' WHERE id=" + receipt.recordId());
        assertTrue(this.<Optional<RunLease>>call("claim", receipt.recordId(), "node-b").isEmpty());
    }

    @Test void resolveReplayStillRequiresCurrentIndependentPermission() {
        var receipt = manual("review-auth");
        var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var result = DataAccessTaskDO.builder().runStatus(5).build();
        assertTrue(this.<Boolean>call("finish", lease, result, false, RunProtocol.Effect.UNKNOWN));
        long version = runs.selectById(receipt.recordId()).getRowVersion();
        call("resolve", receipt.recordId(), "resolve-auth", version, "STOPPED", true, true, "verified", "audit:case");
        var authorization = (AccessRunAuthorization) ReflectionTestUtils.getField(coordinator, "authorization");
        org.mockito.Mockito.doThrow(new SecurityException("revoked")).when(authorization)
                .action(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.eq("dataforge:access:resolve"));
        assertThrows(SecurityException.class, () -> call("resolve", receipt.recordId(), "resolve-auth", version,
                "STOPPED", true, true, "verified", "audit:case"));
    }

    private RunReceipt scheduled() { return this.<Optional<RunReceipt>>call("enqueueScheduled", 10L, 3L, scheduled).orElseThrow(); }

    @Test void syncBindingAndTerminalManifestSurviveNewDatabaseReads() {
        var receipt = manual("sync-proof"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var before = AccessSyncEvidenceTest.binding(receipt.recordId(), runs.selectById(receipt.recordId()).getAttemptCount());
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, before));
        assertEquals(before, syncEvidence(receipt.recordId()));
        assertThrows(RuntimeException.class, () -> call("recordSyncBinding", lease, before), "冻结绑定不可被重复覆盖");
        var after = AccessSyncEvidence.finish(before, AccessSyncEvidenceTest.success());
        var result = DataAccessTaskDO.builder().runStatus(3)
                .syncEvidence(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(after)).build();
        assertTrue(this.<Boolean>call("finish", lease, result, true, RunProtocol.Effect.COMPLETE));
        assertEquals(after, syncEvidence(receipt.recordId()));
        assertFalse(this.<Boolean>call("finish", lease, result, true, RunProtocol.Effect.COMPLETE));
    }

    @Test void staleAndOtherLeaseOwnersCannotWriteSyncBindingOrResults() throws Exception {
        var receipt = manual("sync-stale"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var binding = AccessSyncEvidenceTest.binding(receipt.recordId(), runs.selectById(receipt.recordId()).getAttemptCount());
        var wrongLease = new RunLease(lease.recordId(), "another-owner", lease.leaseUntil());
        assertFalse(this.<Boolean>call("recordSyncBinding", wrongLease, binding));
        context(); assertNull(syncEvidence(receipt.recordId()));
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, binding)); expire();
        assertFalse(this.<Boolean>call("recordSyncBinding", lease, binding));
        var result = DataAccessTaskDO.builder().runStatus(3)
                .syncEvidence(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                        AccessSyncEvidence.finish(binding, AccessSyncEvidenceTest.success()))).build();
        assertFalse(this.<Boolean>call("finish", lease, result, true, RunProtocol.Effect.COMPLETE));
        assertEquals(binding, syncEvidence(receipt.recordId()));
        assertTrue(this.<Boolean>call("recoverExpiredRecord", receipt.recordId()));
        assertEquals(5, runs.selectById(receipt.recordId()).getRunStatus());
        assertEquals(binding, syncEvidence(receipt.recordId()), "节点崩溃不能补造成功清单");
    }

    @Test void preparationAndWrongAttemptCannotFreezeBinding() {
        var receipt = manual("sync-stage"); var lease = claim(receipt);
        int attempt = runs.selectById(receipt.recordId()).getAttemptCount();
        var binding = AccessSyncEvidenceTest.binding(receipt.recordId(), attempt);
        assertThrows(RuntimeException.class, () -> call("recordSyncBinding", lease, binding));
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        assertThrows(RuntimeException.class, () -> call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(receipt.recordId(), attempt + 1)));
        assertThrows(RuntimeException.class, () -> call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(receipt.recordId() + 1, attempt)));
        assertNull(syncEvidence(receipt.recordId()));
    }

    @Test void terminalCallbackCannotReplaceFrozenExecutionIdentity() {
        var receipt = manual("sync-rebind"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        int attempt = runs.selectById(receipt.recordId()).getAttemptCount();
        var binding = AccessSyncEvidenceTest.binding(receipt.recordId(), attempt);
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, binding));
        var result = DataAccessTaskDO.builder().runStatus(3)
                .syncEvidence(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                        AccessSyncEvidence.finish(AccessSyncEvidenceTest.binding(receipt.recordId(), attempt + 1), AccessSyncEvidenceTest.success()))).build();
        assertThrows(RuntimeException.class, () -> call("finish", lease, result, true, RunProtocol.Effect.COMPLETE));
        assertEquals(2, runs.selectById(receipt.recordId()).getRunStatus()); assertEquals(binding, syncEvidence(receipt.recordId()));
        result.setSyncEvidence(null); result.setRunStatus(5);
        assertTrue(this.<Boolean>call("finish", lease, result, false, RunProtocol.Effect.UNKNOWN));
        assertEquals(binding, syncEvidence(receipt.recordId()), "缺失结果也不能擦除运行前的绑定");
    }

    @Test void terminalEvidenceAndStateRollbackTogetherOnPersistenceFailure() throws Exception {
        var receipt = manual("sync-rollback"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var binding = AccessSyncEvidenceTest.binding(receipt.recordId(), runs.selectById(receipt.recordId()).getAttemptCount());
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, binding));
        reject("dh_dataforge_access", "UPDATE");
        var result = DataAccessTaskDO.builder().runStatus(3)
                .syncEvidence(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                        AccessSyncEvidence.finish(binding, AccessSyncEvidenceTest.success()))).build();
        assertThrows(RuntimeException.class, () -> call("finish", lease, result, true, RunProtocol.Effect.COMPLETE));
        assertEquals(binding, syncEvidence(receipt.recordId()));
        assertEquals(2, runs.selectById(receipt.recordId()).getRunStatus());
        assertEquals(lease.token(), runs.selectById(receipt.recordId()).getExecutionToken());
    }

    @Test void syncEvidenceBaselineColumnsReplayWithoutInventingHistoricalEvidence() throws Exception {
        var receipt = manual("legacy-no-proof");
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT pg_typeof(sync_evidence)::text FROM dh_dataforge_access_task")) {
            assertTrue(r.next()); assertEquals("jsonb", r.getString(1), "当前基线包含正确类型");
        }
        // 仅在当前随机隔离 schema 模拟升级前状态，绝不连接业务库。
        sql("ALTER TABLE dh_dataforge_access_task DROP COLUMN sync_evidence");
        String columns = baselineSection("-- 0049、0051、0053 的新增列", "CREATE TABLE IF NOT EXISTS \"datahub-center\".dh_dataforge_sync_clock");
        sql(columns); sql(columns);
        assertNull(syncEvidence(receipt.recordId()));
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT col_description('dh_dataforge_access_task'::regclass,attnum) "
                + "FROM pg_attribute WHERE attrelid='dh_dataforge_access_task'::regclass AND attname='sync_evidence'")) {
            assertTrue(r.next()); assertEquals("实际同步配置修订及批次结果；不等于目标可见性或整改水位证明；历史为空", r.getString(1));
        }
        assertEquals(1, countAll());
    }

    private cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO syncEvidence(long id) {
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(runs.selectById(id).getSyncEvidence(),
                cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchEvidenceDTO.class);
    }

    @Test void batchLookupIsTargetBoundedAndDoesNotProjectPrivateColumns() throws Exception {
        properties.setPerTaskPending(50);
        for (int i = 0; i < 23; i++) manual("window-" + i);
        var selected = runs.selectSyncObservations( "20", 21);
        assertEquals(21, selected.size());
        for (int i = 1; i < selected.size(); i++) assertTrue(selected.get(i-1).getId() > selected.get(i).getId());
        assertTrue(runs.selectSyncObservations( "21", 21).isEmpty());
        assertTrue(runs.selectSyncObservations( "20' OR 1=1 --", 21).isEmpty());
        assertNull(selected.getFirst().getConfigJson());
        assertNull(selected.getFirst().getTaskName()); assertNull(selected.getFirst().getRealTimeLog());
    }

    @Test void batchLookupKeepsOccupiedTombstoneAndDoesNotCacheOldEvidence() throws Exception {
        var receipt = manual("observed-tombstone");
        sql("UPDATE dh_dataforge_access_task SET deleted=1,occupies_slot=true,run_status=5,effect_state='UNKNOWN',review_required=true");
        var selected = runs.selectSyncObservations( "20", 21);
        assertEquals(receipt.recordId(), selected.getFirst().getId());
        assertEquals(5, selected.getFirst().getRunStatus());
        assertTrue(selected.getFirst().getOccupiesSlot());
        sql("UPDATE dh_dataforge_access_task SET occupies_slot=false");
        assertTrue(runs.selectSyncObservations( "20", 21).isEmpty());
    }

    @Test void batchLookupCapsDamagedEvidenceBeforeLoadingIntoApplication() throws Exception {
        manual("oversized");
        sql("UPDATE dh_dataforge_access_task SET sync_evidence=jsonb_build_object('unexpected',repeat('x',2097153))");
        var selected = runs.selectSyncObservations( "20", 21);
        assertEquals(1, selected.size()); assertNull(selected.getFirst().getSyncEvidence());
        sql("UPDATE dh_dataforge_access_task SET sync_evidence='{\"schemaVersion\":1}'::jsonb");
        assertTrue(runs.selectSyncObservations( "20", 21).getFirst().getSyncEvidence().contains("schemaVersion"));
    }

    @Test void baselineLookupIndexServesTheBoundedQuery() throws Exception {
        // 当前基线的索引定义面向新库，重建同一定义验证查询计划，不声称全量基线可重放。
        sql("DROP INDEX idx_dh_access_sync_evidence_target");
        sql(baselineSection("CREATE INDEX idx_dh_access_sync_evidence_target ", "COMMENT ON COLUMN dh_dataforge_access_task.run_key").replace("tenant_id,", ""));
        manual("indexed");
        sql("SET enable_seqscan=off");
        try (var statement = control.createStatement(); var rs = statement.executeQuery(
                "EXPLAIN SELECT id FROM dh_dataforge_access_task WHERE config_json->>'targetDatabaseId'='20' "
                        + "AND protocol_version=1 AND (deleted=0 OR occupies_slot) ORDER BY id DESC LIMIT 21")) {
            var plan = new StringBuilder(); while (rs.next()) plan.append(rs.getString(1));
            assertTrue(plan.toString().contains("idx_dh_access_sync_evidence_target"), plan.toString());
        } finally { sql("RESET enable_seqscan"); }
    }

    private RunReceipt manual(String request) { return call("enqueueManual", 10L, request); }

    private SyncBoundaryService boundaryService() {
        var service = new SyncBoundaryService();
        var authorization = org.mockito.Mockito.mock(SyncBatchObservationService.class);
        org.mockito.Mockito.when(authorization.authorize(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var request = call.getArgument(0, cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO.class);
            var db = cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO.builder()
                    .id(request.databaseId()).databaseSchema("public").status(0).build();
            db.setUpdateTime(LocalDateTime.of(2026, 9, 25, 10, 0));
            return new SyncBatchObservationService.Target( db,
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL,
                    cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.parseRelationReference(
                            cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL,
                            request.tableReference(), "public"));
        });
        ReflectionTestUtils.setField(service, "observations", authorization);
        ReflectionTestUtils.setField(service, "runs", runs);
        service.configureTransactions(((TransactionTemplate) ReflectionTestUtils.getField(coordinator, "transactions")).getTransactionManager());
        return service;
    }

    private cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO boundaryRequest(char key, long database) {
        return new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO("quality:" + String.valueOf(key).repeat(64),
                new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO(database, "public.orders", 99L));
    }

    @Test void committedBoundaryIsStableAcrossLostResponseAndDifferentNodes() {
        var first = boundaryService().ensure(boundaryRequest('a', 20));
        var replay = boundaryService().ensure(boundaryRequest('a', 20));
        assertEquals(first, replay); assertEquals(1L, first.position());
        assertEquals(2L, boundaryService().ensure(boundaryRequest('b', 20)).position());
        assertEquals(first, boundaryService().ensure(boundaryRequest('a', 20)));
    }

    @Test void startBeforeBoundaryRemainsOldEvenIfItFinishesAfterBoundary() throws Exception {
        var first = manual("old-start"); var lease = claim(first);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(first.recordId(), 1)));
        long oldPosition = runs.selectById(first.recordId()).getSyncStartPosition();
        var boundary = boundaryService().ensure(boundaryRequest('a', 20));
        assertTrue(oldPosition < boundary.position());
        assertTrue(this.<Boolean>call("finish", lease, DataAccessTaskDO.builder().runStatus(3).build(), true, RunProtocol.Effect.COMPLETE));
        sql("UPDATE dh_dataforge_access_task SET end_time='2099-01-01' WHERE id=" + first.recordId());
        assertEquals(oldPosition, runs.selectById(first.recordId()).getSyncStartPosition());
        var later = manual("new-start"); var laterLease = claim(later);
        assertTrue(this.<Boolean>call("startEffects", laterLease, RunProtocol.Stage.EXECUTION_STARTED));
        assertTrue(this.<Boolean>call("recordSyncBinding", laterLease, AccessSyncEvidenceTest.binding(later.recordId(), 1)));
        sql("UPDATE dh_dataforge_access_task SET start_time='2000-01-01' WHERE id=" + later.recordId());
        assertTrue(runs.selectById(later.recordId()).getSyncStartPosition() > boundary.position());
        assertEquals(boundary, boundaryService().ensure(boundaryRequest('a', 20)));
    }

    @Test void boundaryIdentityCannotBeReboundToAnotherDatabaseOrTable() {
        var service = boundaryService(); var first = service.ensure(boundaryRequest('a', 20));
        assertThrows(RuntimeException.class, () -> service.ensure(boundaryRequest('a', 21)));
        var changed = new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO(first.requestKey(),
                new cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBatchQueryReqDTO(20L, "public.other", 99L));
        assertThrows(RuntimeException.class, () -> service.ensure(changed));
        assertEquals(first, service.ensure(boundaryRequest('a', 20)));
    }

    @Test void sequenceIsTargetScopedAndIdempotencyKeysAreGlobal() {
        var service = boundaryService(); var first = service.ensure(boundaryRequest('a', 20));
        assertEquals(1L, service.ensure(boundaryRequest('b', 21)).position());
        assertEquals(2L, service.ensure(boundaryRequest('c', 20)).position());
        assertEquals(first, service.ensure(boundaryRequest('a', 20)));
        assertEquals(2L, service.ensure(boundaryRequest('d', 21)).position());
    }

    @Test void failedStartBindingRollsBackAllocatedOrderAndEvidenceTogether() throws Exception {
        boundaryService().ensure(boundaryRequest('a', 20));
        var receipt = manual("failed-order"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        reject("dh_dataforge_access_task", "UPDATE");
        assertThrows(RuntimeException.class, () -> call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(receipt.recordId(), 1)));
        assertNull(runs.selectById(receipt.recordId()).getSyncStartPosition());
        assertNull(runs.selectById(receipt.recordId()).getSyncEvidence());
        assertEquals(2L, boundaryService().ensure(boundaryRequest('b', 20)).position());
    }

    @Test void staleLeaseCannotAllocateNewStartOrder() throws Exception {
        boundaryService().ensure(boundaryRequest('a', 20));
        var receipt = manual("stale-order"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED)); expire();
        assertFalse(this.<Boolean>call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(receipt.recordId(), 1)));
        assertNull(runs.selectById(receipt.recordId()).getSyncStartPosition());
        assertEquals(2L, boundaryService().ensure(boundaryRequest('b', 20)).position());
    }

    @Test void boundaryInsertFailureLeavesNoCommittedClockOrReceipt() throws Exception {
        reject("dh_dataforge_sync_boundary", "INSERT");
        assertThrows(RuntimeException.class, () -> boundaryService().ensure(boundaryRequest('a', 20)));
        assertNull(runs.findSyncBoundary( boundaryRequest('a', 20).requestKey()));
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM dh_dataforge_sync_clock")) {
            assertTrue(result.next()); assertEquals(0, result.getInt(1));
        }
        sql("DROP TRIGGER reject_change ON dh_dataforge_sync_boundary");
        assertEquals(1L, boundaryService().ensure(boundaryRequest('a', 20)).position());
    }

    @Test void duplicateConcurrentNodesAllocateOneBoundary() throws Exception {
        var service = boundaryService();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            Callable<cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryDTO> action = () -> {
                context(); ready.countDown();
                try { assertTrue(go.await(5, TimeUnit.SECONDS)); return service.ensure(boundaryRequest('a', 20)); }
                finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
            };
            var a = pool.submit(action); var b = pool.submit(action);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(2L, service.ensure(boundaryRequest('b', 20)).position());
    }

    @Test void boundaryAndStartSqlWorkWithoutTenantInterceptor() {
        var manager = (DataSourceTransactionManager) ((TransactionTemplate)
                ReflectionTestUtils.getField(coordinator, "transactions")).getTransactionManager();
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("boundary-platform-test", new SpringManagedTransactionFactory(), manager.getDataSource()));
        var interceptors = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        config.addInterceptor(interceptors);
        config.addMapper(DataAccessTaskMapper.class);
        var guarded = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(DataAccessTaskMapper.class);
        var service = boundaryService();
        ReflectionTestUtils.setField(service, "runs", guarded);
        var boundary = service.ensure(boundaryRequest('a', 20));
        var receipt = manual("platform-start"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        ReflectionTestUtils.setField(coordinator, "runs", guarded);
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(receipt.recordId(), 1)));
        assertEquals(2L, guarded.selectById(receipt.recordId()).getSyncStartPosition());
        TenantContextHolder.setTenantId(2L);
        assertNotNull(guarded.selectById(receipt.recordId()));
        assertEquals(1L, service.ensure(boundaryRequest('a', 20)).position());
        context();
        assertEquals(boundary, service.ensure(boundaryRequest('a', 20)));
    }

    @Test void concurrentStartAndBoundaryShareOneCommittedOrder() throws Exception {
        var receipt = manual("concurrent-boundary"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var service = boundaryService();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            var started = pool.submit(() -> {
                context(); ready.countDown();
                try {
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    return this.<Boolean>call("recordSyncBinding", lease, AccessSyncEvidenceTest.binding(receipt.recordId(), 1));
                } finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
            });
            var registered = pool.submit(() -> {
                context(); ready.countDown();
                try { assertTrue(go.await(5, TimeUnit.SECONDS)); return service.ensure(boundaryRequest('a', 20)); }
                finally { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertTrue(started.get(10, TimeUnit.SECONDS));
            var boundary = registered.get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(1L, 2L), Set.of(boundary.position(), runs.selectById(receipt.recordId()).getSyncStartPosition()));
            assertEquals(boundary, boundaryService().ensure(boundaryRequest('a', 20)));
        }
        assertEquals(3L, service.ensure(boundaryRequest('b', 20)).position());
    }

    @Test void boundaryBaselineStructureReplaysWithoutBackfillingOldRuns() throws Exception {
        var receipt = manual("no-historical-order");
        var first = boundaryService().ensure(boundaryRequest('a', 20));
        String boundary = baselineSection("CREATE TABLE IF NOT EXISTS \"datahub-center\".dh_dataforge_sync_clock", "CREATE INDEX idx_dh_access_sync_evidence_target ");
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        assertNull(runs.selectById(receipt.recordId()).getSyncStartPosition());
        assertEquals(first, boundaryService().ensure(boundaryRequest('a', 20)));
    }
    @Test void syncStartGetsDurableOrderBeforeEngineCanRead() {
        var receipt = manual("ordered-start"); var lease = claim(receipt);
        assertTrue(this.<Boolean>call("startEffects", lease, RunProtocol.Stage.EXECUTION_STARTED));
        var binding = AccessSyncEvidenceTest.binding(receipt.recordId(), 1);
        assertTrue(this.<Boolean>call("recordSyncBinding", lease, binding));
        Object position = ReflectionTestUtils.getField(runs.selectById(receipt.recordId()), "syncStartPosition");
        assertInstanceOf(Long.class, position);
        assertTrue((Long) position > 0);
    }
    @Test void typedCheckpointUsesRealOwnedTransactionAndRejectsExpiredLease() throws Exception {
        var receipt=manual("typed-checkpoint");var lease=claim(receipt);
        assertTrue(this.<Boolean>call("startEffects",lease,RunProtocol.Stage.EXECUTION_STARTED));
        var row=checkpointRow(receipt.recordId());
        assertTrue(this.<Boolean>call("writeOwned",lease,(Runnable)()->checkpoints.insert(row)));
        assertEquals(0L,checkpoints.current(10L,"public","source").getRevision());
        assertNull(checkpoints.current(11L,"public","source"));
        expire();
        assertFalse(this.<Boolean>call("writeOwned",lease,(Runnable)()->
                checkpoints.compareAndSet(row.getId(),10L,"public","source",0,receipt.recordId(),"{\"version\":1}")));
        assertEquals(0L,checkpoints.current(10L,"public","source").getRevision());
    }

    @Test void typedCheckpointCasIncludesTaskTableAndRevisionAndHasOneWinner() throws Exception {
        var row=checkpointRow(100L);checkpoints.insert(row);
        String json="{\"version\":1,\"marker\":\"updated\"}";
        assertEquals(0,checkpoints.compareAndSet(row.getId(),11L,"public","source",0,100L,json));
        assertEquals(0,checkpoints.compareAndSet(row.getId(),10L,"other","source",0,100L,json));
        assertEquals(0,checkpoints.compareAndSet(row.getId(),10L,"public","other",0,100L,json));
        assertEquals(0,checkpoints.compareAndSet(row.getId(),10L,"public","source",1,100L,json));
        try(var pool=Executors.newFixedThreadPool(2)){
            var go=new CountDownLatch(1);
            Callable<Integer> write=()->{context();try{go.await();return checkpoints.compareAndSet(row.getId(),10L,"public","source",0,100L,json);}
                finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}};
            var first=pool.submit(write);var second=pool.submit(write);go.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1L,checkpoints.current(10L,"public","source").getRevision());
    }

    @Test void typedCheckpointBaselineReplaysWithoutReplacingExistingPosition() throws Exception {
        var row=checkpointRow(100L);checkpoints.insert(row);
        assertEquals(1,checkpoints.compareAndSet(row.getId(),10L,"public","source",0,100L,"{\"version\":1,\"marker\":\"keep\"}"));
        applyCheckpointBaseline();
        var restored=checkpoints.current(10L,"public","source");assertEquals(1L,restored.getRevision());
        assertTrue(restored.getCheckpointJson().contains("keep"));
        assertThrows(Exception.class,()->sql("INSERT INTO dh_dataforge_access_checkpoint(data_access_id,schema_name,table_name,watermark_column,checkpoint_json,owner_run_id) VALUES(11,'public','bad','id','{}',100)"));
    }

    private cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessCheckpointDO checkpointRow(Long run){
        var row=new cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessCheckpointDO();
        row.setDataAccessId(10L);row.setSchemaName("public");row.setTableName("source");
        row.setWatermarkColumn("stamp");row.setRevision(0L);row.setOwnerRunId(run);row.setCheckpointJson("{\"version\":1}");return row;
    }

    @Test void typedCheckpointV2BaselineReplaysAndPreservesV1UntilOwnedCasAdvancesIt() throws Exception {
        var receipt=manual("typed-v2");var lease=claim(receipt);
        assertTrue(this.<Boolean>call("startEffects",lease,RunProtocol.Stage.EXECUTION_STARTED));
        var row=checkpointRow(receipt.recordId());checkpoints.insert(row);
        applyCheckpointBaseline();applyCheckpointBaseline();
        assertEquals("{\"version\": 1}",checkpoints.current(10L,"public","source").getCheckpointJson());
        String json=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(v2Checkpoint(false));
        assertTrue(this.<Boolean>call("writeOwned",lease,(Runnable)()->
                assertEquals(1,checkpoints.compareAndSet(row.getId(),10L,"public","source",0,receipt.recordId(),json))));
        var actual=checkpoints.current(10L,"public","source");
        assertEquals(1L,actual.getRevision());assertEquals(v2Checkpoint(false),AccessCursorCheckpointStore.decode(actual.getCheckpointJson()));
        assertEquals(0,checkpoints.compareAndSet(row.getId(),11L,"public","source",1,receipt.recordId(),json));
        assertEquals(0,checkpoints.compareAndSet(row.getId(),10L,"public","source",0,receipt.recordId(),json));
        expire();assertFalse(this.<Boolean>call("writeOwned",lease,(Runnable)()->fail("expired owner must not write V2")));
        assertEquals(1L,checkpoints.current(10L,"public","source").getRevision());
    }

    @Test void typedCheckpointV2HasOneCasWinnerAndDistinguishesJdbcFromPostgresWitness() throws Exception {
        applyCheckpointBaseline();var row=checkpointRow(100L);checkpoints.insert(row);
        String json=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(v2Checkpoint(false));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var go=new CountDownLatch(1);
            Callable<Integer> write=()->{context();try{go.await();return checkpoints.compareAndSet(row.getId(),10L,"public","source",0,100L,json);}
                finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}};
            var first=pool.submit(write);var second=pool.submit(write);go.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        String witnessed=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(v2Checkpoint(true));
        assertEquals(1,checkpoints.compareAndSet(row.getId(),10L,"public","source",1,100L,witnessed));
        assertEquals(v2Checkpoint(true),AccessCursorCheckpointStore.decode(checkpoints.current(10L,"public","source").getCheckpointJson()));
    }

    @Test void typedCheckpointV2DatabaseRejectsMissingAndContradictoryEvidence() throws Exception {
        applyCheckpointBaseline();var row=checkpointRow(100L);checkpoints.insert(row);
        String json=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(v2Checkpoint(false));
        var mapper=cn.iocoder.yudao.framework.common.util.json.JsonUtils.getObjectMapper();
        for(var invalid:List.of(Map.entry("version",3),Map.entry("sourceProduct","UNKNOWN"),Map.entry("targetProduct","INVENTED"),
                Map.entry("sourceProduct",""),Map.entry("lastCommitAcknowledgement","UNKNOWN"),
                Map.entry("lastCommitAcknowledgement","NONE"),Map.entry("lastCommitAcknowledgement","POSTGRESQL_XID8_VERIFIED"),
                Map.entry("sequence",0),Map.entry("sequence",-1),Map.entry("sequence","9223372036854775808"),
                Map.entry("bindingFingerprint","invalid"),Map.entry("complete","true"),Map.entry("fixedUpper","invalid"))) {
            var value=new HashMap<String,Object>(mapper.readValue(json,Map.class));value.put(invalid.getKey(),invalid.getValue());
            String payload=mapper.writeValueAsString(value);
            assertThrows(Exception.class,()->checkpoints.compareAndSet(row.getId(),10L,"public","source",0,100L,payload),invalid.getKey());
        }
        for(String missing:List.of("version","sourceProduct","targetProduct","lastCommitAcknowledgement","sequence","bindingFingerprint",
                "schemaFingerprint","targetSchemaFingerprint","complete","applied","fixedUpper")) {
            var value=new HashMap<String,Object>(mapper.readValue(json,Map.class));value.remove(missing);
            String payload=mapper.writeValueAsString(value);
            assertThrows(Exception.class,()->checkpoints.compareAndSet(row.getId(),10L,"public","source",0,100L,payload),missing);
        }
        var empty=new cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint(2,"a".repeat(64),"b".repeat(64),"c".repeat(64),null,null,0,true,null,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.NONE,
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL,cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MARIADB);
        assertEquals(1,checkpoints.compareAndSet(row.getId(),10L,"public","source",0,100L,mapper.writeValueAsString(empty)));
        assertEquals(empty,AccessCursorCheckpointStore.decode(checkpoints.current(10L,"public","source").getCheckpointJson()));
    }

    private void applyCheckpointBaseline() throws Exception {
        if (checkpoints != null) {
            cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
            return;
        }
        sql(baselineSection("-- 类型化增量检查点（0086）", "-- 数据接入业务配置版本约束，与 0087"));
    }

    /** 按唯一语义标记提取当前基线原文；基线变更导致标记漂移时直接报错，避免悄悄加载不完整夹具。 */
    private String baselineSection(String startMarker, String endMarker) {
        int start = baseline.indexOf(startMarker);
        assertTrue(start >= 0, "基线缺少起始标记：" + startMarker);
        assertEquals(-1, baseline.indexOf(startMarker, start + startMarker.length()), "基线起始标记不唯一：" + startMarker);
        int end = baseline.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "基线缺少结束标记：" + endMarker);
        return baseline.substring(start, end).replace("\"datahub-center\"", schema);
    }
    private cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint v2Checkpoint(boolean postgresWitness) {
        var cursor=new cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor(1,List.of(
                cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue.of(Types.BIGINT,"BIGINT",1L)));
        var witness=postgresWitness?new cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1","123","d".repeat(64)):null;
        return new cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint(2,"a".repeat(64),"b".repeat(64),"c".repeat(64),cursor,cursor,1,true,witness,
                postgresWitness?cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED
                        :cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.JDBC_COMMIT_RETURNED,
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL,
                postgresWitness?cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL:cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MARIADB);
    }

    private RunLease claim(RunReceipt r) { return this.<Optional<RunLease>>call("claim", r.recordId(), "node-a").orElseThrow(); }
    private <T> T call(String name, Object... args) { return ReflectionTestUtils.invokeMethod(coordinator, name, args); }
    private void expire() throws Exception { sql("UPDATE dh_dataforge_access_task SET lease_until=clock_timestamp()-interval '1 second'"); }
    private void context() { TenantContextHolder.setTenantId(1L); SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new LoginUser().setId(99L), null)); }
    private long countAll() throws Exception { try(var s=control.createStatement(); var r=s.executeQuery("SELECT count(*) FROM dh_dataforge_access_task")) { r.next(); return r.getLong(1); } }
    private void reject(String table, String event) throws Exception {
        sql("CREATE FUNCTION reject_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$");
        sql("CREATE TRIGGER reject_change BEFORE " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION reject_change()");
    }
    private void sql(String sql) throws Exception { try (var s=control.createStatement()) { s.execute(sql); } }
}
