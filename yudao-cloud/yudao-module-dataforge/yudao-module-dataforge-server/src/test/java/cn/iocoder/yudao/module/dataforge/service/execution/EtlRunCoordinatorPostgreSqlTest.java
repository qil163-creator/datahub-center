package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlRunCoordinator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EtlRunCoordinatorPostgreSqlTest {
    private static PostgreSQLContainer<?> POSTGRES;
    private static boolean externalPostgres;
    private Connection control;
    private String schema;
    private AnnotationConfigApplicationContext app;
    private EtlRunCoordinator coordinator;
    private EtlPipelineMapper pipelines;
    private EtlRecordMapper records;
    private final AtomicBoolean assertRecordInsertHasTransaction = new AtomicBoolean();
    private final AtomicInteger recordInsertProbeHits = new AtomicInteger();
    private final LocalDateTime scheduled = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(
            type = org.apache.ibatis.executor.Executor.class, method = "update",
            args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    private static final class RecordInsertTransactionProbe implements org.apache.ibatis.plugin.Interceptor {
        private final AtomicBoolean enabled;
        private final AtomicInteger hits;

        private RecordInsertTransactionProbe(AtomicBoolean enabled, AtomicInteger hits) {
            this.enabled = enabled;
            this.hits = hits;
        }

        @Override
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            boolean recordParameter = parameter instanceof EtlRecordDO
                    || (parameter instanceof java.util.Map<?, ?> values
                    && values.values().stream().anyMatch(EtlRecordDO.class::isInstance));
            boolean recordInsert = statement.getSqlCommandType() == org.apache.ibatis.mapping.SqlCommandType.INSERT
                    && (recordParameter || statement.getId().startsWith(EtlRecordMapper.class.getName() + "."));
            if (recordInsert) {
                hits.incrementAndGet();
                if (enabled.get()) assertTrue(
                        org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                        "record insertion must run in the coordinator transaction");
            }
            return invocation.proceed();
        }

        @Override public Object plugin(Object target) { return org.apache.ibatis.plugin.Plugin.wrap(target, this); }
        @Override public void setProperties(java.util.Properties properties) { }
    }

    @BeforeAll
    static void startDatabase() {
        String url = System.getProperty("datahub.test.postgres.url");
        externalPostgres = url != null && !url.isBlank();
        if (externalPostgres) POSTGRES = new ExternalPostgres(url);
        else { POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer(); POSTGRES.start(); }
    }

    @AfterAll
    static void stopDatabase() {
        if (POSTGRES != null && !externalPostgres) POSTGRES.stop();
    }

    private static final class ExternalPostgres extends PostgreSQLContainer<ExternalPostgres> {
        private final String url;
        private ExternalPostgres(String url) { super("postgres:16-alpine"); this.url = url; }
        @Override public String getJdbcUrl() { return url; }
        @Override public String getUsername() { return System.getProperty("datahub.test.postgres.user", "etl_fixture"); }
        @Override public String getPassword() { return System.getProperty("datahub.test.postgres.password", ""); }
    }

    @BeforeEach
    void setup() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        String user = POSTGRES.getUsername();
        control = DriverManager.getConnection(url, user, POSTGRES.getPassword());
        schema = "etl_coord_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        // 最小旧库夹具保留生产字段与 PostgreSQL 原生语义，不使用 H2。
        execute("CREATE TABLE dh_dataforge_access(id bigint,tenant_id bigint,deleted smallint)");
        execute("CREATE TABLE dh_dataforge_access_task(id bigint,data_access_id bigint,tenant_id bigint,run_status smallint,deleted smallint default 0)");
        execute("CREATE TABLE dh_dataforge_etl_pipeline(environment_id bigint,id bigint primary key,tenant_id bigint,name varchar(50),task_type smallint,cron varchar(200),"
                + "next_run_time timestamp,status smallint,schedule_status smallint,run_status smallint,start_time timestamp,end_time timestamp,"
                + "release_time timestamp,release_user_id bigint,description text,nodes_json jsonb,edges_json jsonb,params_json jsonb,org_id bigint,"
                + "creator varchar(64),updater varchar(64),create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0)");
        execute("CREATE TABLE dh_dataforge_etl_record(id bigint primary key,pipeline_id bigint,tenant_id bigint,name varchar(50),run_status smallint,execute_type smallint,"
                + "start_time timestamp,end_time timestamp,log text,config_json jsonb,org_id bigint,creator varchar(64),updater varchar(64),"
                + "create_time timestamp default now(),update_time timestamp default now(),deleted smallint default 0)");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        execute(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0038_dataforge_run-protocol.sql"))
                .replace("datahub-center", schema));
        execute(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0049_dataforge_run-datasource-capacity.sql"))
                .replace("datahub-center", schema));
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, POSTGRES.getPassword());
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var mybatis = new MybatisConfiguration();
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(mybatis).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        mybatis.addInterceptor(new RecordInsertTransactionProbe(assertRecordInsertHasTransaction, recordInsertProbeHits));
        mybatis.setEnvironment(new Environment("real-pg", new SpringManagedTransactionFactory(), source));
        mybatis.addMapper(EtlPipelineMapper.class);
        mybatis.addMapper(EtlRecordMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(mybatis));
        app = new AnnotationConfigApplicationContext();
        app.register(Transactions.class);
        app.registerBean(DataSource.class, () -> source);
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        app.registerBean(EtlPipelineMapper.class, () -> sessions.getMapper(EtlPipelineMapper.class));
        app.registerBean(EtlRecordMapper.class, () -> sessions.getMapper(EtlRecordMapper.class));
        app.registerBean(RunExecutionProperties.class);
        app.registerBean(io.micrometer.core.instrument.MeterRegistry.class,
                io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        app.registerBean(RunMetrics.class);
        app.getBeanFactory().registerSingleton("qualityBatches", org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlQualityBatchMapper.class));
        app.getBeanFactory().registerSingleton("targetBatches", org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlTargetBatchMapper.class));
        app.getBeanFactory().registerSingleton("replays", org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlReplayMapper.class));
        app.getBeanFactory().registerSingleton("versionService", org.mockito.Mockito.mock(
                cn.iocoder.yudao.module.dataforge.service.etl.EtlVersionService.class));
        app.getBeanFactory().registerSingleton("archives", org.mockito.Mockito.mock(cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlArchiveMapper.class));

                app.registerBean(EtlRunCoordinator.class);
        app.refresh();
        coordinator = app.getBean(EtlRunCoordinator.class);
        pipelines = app.getBean(EtlPipelineMapper.class);
        records = app.getBean(EtlRecordMapper.class);
        TenantContextHolder.setTenantId(1L);
        execute("INSERT INTO dh_dataforge_etl_pipeline(id,name,task_type,status,schedule_status,revision,published_revision,schedule_generation,published_execution_user_id,published_config_json,next_run_time) VALUES(10,'test',2,1,1,2,1,3,99,'{\"name\":\"published\",\"revision\":1,\"nodes\":[],\"edges\":[],\"parameters\":[],\"cron\":\"0 * * * * *\"}','2026-01-01 00:00:00')");
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContextHolder.clear();
        if (app != null) app.close();
        if (control != null) {
            try {
                if (schema != null && schema.matches("etl_coord_[a-f0-9]{32}")) {
                    execute("ROLLBACK");
                    execute("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally { control.close(); }
        }
    }

    @Test
    void scheduledSnapshotAndNextTimeCommitTogetherAndReplayReturnsSameRun() throws Exception {
        var observed = pipelines.selectById(10L);
        var first = coordinator.enqueueScheduled(observed, prepared()).orElseThrow();
        var replay = coordinator.enqueueScheduled(observed, prepared()).orElseThrow();
        assertEquals(first.recordId(), replay.recordId());
        assertTrue(replay.replayed());
        assertEquals(scheduled.plusMinutes(1), pipelines.selectById(10L).getNextRunTime());
        assertEquals(1, records.selectCount(null));
        assertEquals(1, records.selectById(first.recordId()).getProtocolVersion());
        assertFalse(records.selectById(first.recordId()).getOccupiesSlot());
    }

    @Test
    void insertFailureRollsBackNextTime() throws Exception {
        execute("CREATE FUNCTION reject_run() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected insert failure'; END $$");
        execute("CREATE TRIGGER reject_run BEFORE INSERT ON dh_dataforge_etl_record FOR EACH ROW EXECUTE FUNCTION reject_run()");
        var observed = pipelines.selectById(10L);
        assertThrows(RuntimeException.class, () -> coordinator.enqueueScheduled(observed, prepared()));
        assertEquals(scheduled, pipelines.selectById(10L).getNextRunTime());
        assertEquals(0, records.selectCount(null));
    }

    @Test
    void advancingScheduleFailureRollsBackAlreadyInsertedRun() throws Exception {
        execute("CREATE FUNCTION reject_schedule() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected schedule failure'; END $$");
        execute("CREATE TRIGGER reject_schedule BEFORE UPDATE ON dh_dataforge_etl_pipeline FOR EACH ROW EXECUTE FUNCTION reject_schedule()");
        var observed = pipelines.selectById(10L);
        assertThrows(RuntimeException.class, () -> coordinator.enqueueScheduled(observed, prepared()));
        assertEquals(scheduled, pipelines.selectById(10L).getNextRunTime());
        assertEquals(0, records.selectCount(null));
    }

    @Test
    void expiredOwnerCannotRenewStartOrWriteAndSafePreparationCanBeRetried() throws Exception {
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "request-a");
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        execute("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second'");
        assertFalse(coordinator.renew(receipt.recordId(), lease.token()));
        assertFalse(coordinator.startEffects(receipt.recordId(), lease.token()));
        assertFalse(coordinator.owns(receipt.recordId(), lease.token()));
        assertTrue(coordinator.recoverExpired(receipt.recordId()));
        EtlRecordDO current = records.selectById(receipt.recordId());
        assertEquals(1, current.getRunStatus());
        assertFalse(current.getOccupiesSlot());
        assertNull(current.getExecutionToken());
    }

    @Test
    void onlyOneQueuedRunCanClaimAndUnknownExecutionDoesNotReleaseSlot() throws Exception {
        var observed = pipelines.selectById(10L);
        var one = coordinator.enqueueManual(observed, prepared(), "request-a");
        var two = coordinator.enqueueManual(observed, prepared(), "request-b");
        var lease = coordinator.claim(one.recordId(), "node-a").orElseThrow();
        assertTrue(coordinator.claim(two.recordId(), "node-b").isEmpty());
        assertTrue(coordinator.startEffects(one.recordId(), lease.token()));
        execute("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second' WHERE id=" + one.recordId());
        assertTrue(coordinator.recoverExpired(one.recordId()));
        EtlRecordDO current = records.selectById(one.recordId());
        assertEquals(6, current.getRunStatus());
        assertTrue(current.getOccupiesSlot());
        assertTrue(current.getReviewRequired());
        assertFalse(coordinator.renew(one.recordId(), lease.token()));
        assertTrue(coordinator.claim(two.recordId(), "node-b").isEmpty());
    }

    @Test
    void rejectedExecutorKeepsAcceptedWorkWithBackoffAndNoScheduleRewind() throws Exception {
        var one = coordinator.enqueueScheduled(pipelines.selectById(10L), prepared()).orElseThrow();
        var lease = coordinator.claim(one.recordId(), "node-a").orElseThrow();
        assertTrue(coordinator.releaseBeforeExecution(one.recordId(), lease.token()));
        EtlRecordDO current = records.selectById(one.recordId());
        assertEquals(1, current.getRunStatus());
        assertNotNull(current.getNextAttemptAt());
        assertFalse(current.getOccupiesSlot());
        assertTrue(coordinator.claim(one.recordId(), "node-b").isEmpty());
        assertEquals(scheduled.plusMinutes(1), pipelines.selectById(10L).getNextRunTime());
    }

    @Test
    void oldWorkerCannotWriteChildrenOrOverwriteNewerTaskSummary() throws Exception {
        var one = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "write-a");
        var lease = coordinator.claim(one.recordId(), "node-a").orElseThrow();
        assertTrue(coordinator.startEffects(one.recordId(), lease.token()));
        assertTrue(coordinator.writeOwned(one.recordId(), lease.token(), () ->
                records.updateById(EtlRecordDO.builder().id(one.recordId()).log("owned").build())));
        execute("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second'");
        assertFalse(coordinator.writeOwned(one.recordId(), lease.token(), () -> fail("expired callback ran")));
        assertFalse(coordinator.finish(one.recordId(), lease.token(), 3, RunProtocol.Effect.COMPLETE, true, "late"));
        assertEquals("owned", records.selectById(one.recordId()).getLog());
    }

    @Test
    void finishedEffectsAndQualityDecideOccupancyRatherThanStatusAlone() throws Exception {
        var one = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "partial-a");
        var lease = coordinator.claim(one.recordId(), "node-a").orElseThrow();
        assertTrue(coordinator.startEffects(one.recordId(), lease.token()));
        assertTrue(coordinator.finish(one.recordId(), lease.token(), 4, RunProtocol.Effect.PARTIAL, true, "partly written"));
        var result = records.selectById(one.recordId());
        assertEquals(4, result.getRunStatus());
        assertTrue(result.getOccupiesSlot());
        assertTrue(result.getReviewRequired());
    }

    @Test
    void aStopRequestIsPersistentButDoesNotDeclareRunningResourcesTerminated() throws Exception {
        var one = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "stop-a");
        var lease = coordinator.claim(one.recordId(), "node-a").orElseThrow();
        assertTrue(coordinator.startEffects(one.recordId(), lease.token()));
        coordinator.requestStop(one.recordId());
        var current = records.selectById(one.recordId());
        assertTrue(current.getStopRequested());
        assertEquals(2, current.getRunStatus());
        assertTrue(current.getOccupiesSlot());
        assertTrue(coordinator.shouldStop(one.recordId(), lease.token()));
    }

    @Test
    void twoIndependentThreadsAcceptOnlyOneScheduledOccurrence() throws Exception {
        var observed = pipelines.selectById(10L);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<RunReceipt> action = () -> {
                TenantContextHolder.setTenantId(1L);
                try {
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    return coordinator.enqueueScheduled(observed, prepared()).orElseThrow();
                } finally { TenantContextHolder.clear(); }
            };
            var one = workers.submit(action);
            var two = workers.submit(action);
            gate.countDown();
            assertEquals(one.get(10, TimeUnit.SECONDS).recordId(), two.get(10, TimeUnit.SECONDS).recordId());
        }
        assertEquals(1, records.selectCount(null));
    }

    @Test
    void staleScheduleGenerationCannotCreateWork() throws Exception {
        var observed = pipelines.selectById(10L);
        execute("UPDATE dh_dataforge_etl_pipeline SET schedule_generation=4");
        assertTrue(coordinator.enqueueScheduled(observed, prepared()).isEmpty());
        assertEquals(0, records.selectCount(null));
    }

    @Test
    void capacityDoesNotDiscardDueOccurrence() throws Exception {
        app.getBean(RunExecutionProperties.class).setPerTaskPending(1);
        coordinator.enqueueScheduled(pipelines.selectById(10L), prepared()).orElseThrow();
        var next = pipelines.selectById(10L);
        assertTrue(coordinator.enqueueScheduled(next, prepared()).isEmpty());
        assertEquals(scheduled.plusMinutes(1), pipelines.selectById(10L).getNextRunTime());
    }

    @Test
    void manualReplayKeepsOriginalSnapshotAfterDraftChanges() throws Exception {
        var observed = pipelines.selectById(10L);
        var first = coordinator.enqueueManual(observed, prepared(), "client-request-1");
        execute("UPDATE dh_dataforge_etl_pipeline SET revision=revision+1,published_revision=9");
        var changed = prepared();
        changed.setConfigJson("{\"name\":\"changed\"}");
        var replay = coordinator.enqueueManual(pipelines.selectById(10L), changed, "client-request-1");
        assertEquals(first.recordId(), replay.recordId());
        assertTrue(replay.replayed());
        assertFalse(records.selectById(first.recordId()).getConfigJson().contains("changed"));
    }

    @Test
    void parameterFingerprintReplaySurvivesCoordinatorRecreationAndRejectsChangedInput() {
        String sameInput = cn.hutool.crypto.digest.DigestUtil.sha256Hex("revision=2;id=9007199254740993");
        String changedValue = cn.hutool.crypto.digest.DigestUtil.sha256Hex("revision=2;id=9007199254740994");
        String changedRevision = cn.hutool.crypto.digest.DigestUtil.sha256Hex("revision=3;id=9007199254740993");
        assertRecordInsertHasTransaction.set(true);
        int probesBefore = recordInsertProbeHits.get();
        var first = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "typed-parameters", sameInput);
        assertTrue(recordInsertProbeHits.get() > probesBefore, "the test must observe the ETL run INSERT");
        assertEquals(1, records.selectCount(null));

        app.getDefaultListableBeanFactory().destroySingleton("etlRunCoordinator");
        coordinator = app.getBean(EtlRunCoordinator.class);
        var retry = coordinator.findManualReceipt(10L, 99L, "typed-parameters", sameInput).orElseThrow();
        assertEquals(first.recordId(), retry.recordId());
        assertTrue(retry.replayed());
        assertThrows(RuntimeException.class, () -> coordinator.findManualReceipt(
                10L, 99L, "typed-parameters", changedValue));
        assertThrows(RuntimeException.class, () -> coordinator.findManualReceipt(
                10L, 99L, "typed-parameters", changedRevision));
        assertThrows(RuntimeException.class, () -> coordinator.enqueueManual(
                pipelines.selectById(10L), prepared(), "typed-parameters", changedValue));
        assertThrows(RuntimeException.class, () -> coordinator.enqueueManual(
                pipelines.selectById(10L), prepared(), "typed-parameters", changedRevision));
        assertEquals(1, records.selectCount(null));
        assertRecordInsertHasTransaction.set(false);
    }

    @Test
    void parameterizedManualRequestConcurrentReplayInsertsOnceInsideTransaction() throws Exception {
        assertRecordInsertHasTransaction.set(true);
        int probesBefore = recordInsertProbeHits.get();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> submit = () -> {
                TenantContextHolder.setTenantId(1L);
                try {
                    start.await();
                    return coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "typed-concurrent", "same-fingerprint");
                } finally { TenantContextHolder.clear(); }
            };
            var one = pool.submit(submit);
            var two = pool.submit(submit);
            start.countDown();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertEquals(first.recordId(), second.recordId());
            assertEquals(1, records.selectCount(null));
            assertTrue(recordInsertProbeHits.get() > probesBefore, "the test must observe the ETL run INSERT");
        } finally {
            pool.shutdownNow();
            assertRecordInsertHasTransaction.set(false);
        }
    }


    @Test
    void aNewManualHintCannotJumpOlderPendingOrBackoffWork() throws Exception {
        var older = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "older");
        var newer = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "newer");
        execute("UPDATE dh_dataforge_etl_record SET next_attempt_at=clock_timestamp()+interval '30 seconds' WHERE id=" + older.recordId());
        assertTrue(coordinator.claim(newer.recordId(), "node-b").isEmpty());
        assertTrue(coordinator.claim(older.recordId(), "node-a").isEmpty());
        execute("UPDATE dh_dataforge_etl_record SET next_attempt_at=NULL WHERE id=" + older.recordId());
        assertTrue(coordinator.claim(older.recordId(), "node-a").isPresent());
    }

    @Test
    void recoveryUpdatesOnlyTheSummaryOfItsLatestRun() throws Exception {
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "summary");
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        coordinator.startEffects(receipt.recordId(), lease.token());
        execute("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second'");
        coordinator.recoverExpired(receipt.recordId());
        assertEquals(6, pipelines.selectById(10L).getRunStatus());
    }

    @Test
    void repeatedPublicationOfSameRevisionDoesNotCreateAnotherRun() throws Exception {
        execute("UPDATE dh_dataforge_etl_pipeline SET task_type=1");
        var observed = pipelines.selectById(10L);
        var first = coordinator.publish(observed, prepared().getConfigJson(), 99L, prepared());
        var second = coordinator.publish(observed, prepared().getConfigJson(), 99L, prepared());
        assertEquals(first.recordId(), second.recordId());
        assertEquals(1, records.selectCount(null));
        assertEquals(4L, pipelines.selectById(10L).getScheduleGeneration());
    }

    @Test
    void repeatedEnableDoesNotResetScheduleOrCancelAcceptedWork() throws Exception {
        var receipt = coordinator.enqueueScheduled(pipelines.selectById(10L), prepared()).orElseThrow();
        coordinator.changeSchedule(10L, true);
        assertEquals(3L, pipelines.selectById(10L).getScheduleGeneration());
        assertEquals(scheduled.plusMinutes(1), pipelines.selectById(10L).getNextRunTime());
        assertEquals(1, records.selectById(receipt.recordId()).getRunStatus());
    }

    @Test
    void deletionCannotRemovePendingOrUnreviewedPartialRuns() {
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "delete");
        Runnable children = () -> fail("children must not be deleted");
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "deleteRecord", receipt.recordId(), children));
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        coordinator.startEffects(receipt.recordId(), lease.token());
        coordinator.finish(receipt.recordId(), lease.token(), 4, RunProtocol.Effect.PARTIAL, true, "partial");
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "deleteRecord", receipt.recordId(), children));
        assertNotNull(records.selectById(receipt.recordId()));
    }

    @Test
    void deletingResolvedRunKeepsItsHistoricalRequestIdentity() {
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "delete-resolved");
        coordinator.stopCommand(receipt.recordId(), "stop-before-start", 99L);
        var children = new java.util.concurrent.atomic.AtomicBoolean();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "deleteRecord", receipt.recordId(), (Runnable) () -> children.set(true));
        assertTrue(children.get());
        assertNull(records.selectById(receipt.recordId()));
        assertThrows(RuntimeException.class, () -> coordinator.enqueueManual(
                pipelines.selectById(10L), prepared(), "delete-resolved"));
    }


    @Test
    void pureCapacityRejectionDoesNotConsumeExecutionFailureBudget() {
        app.getBean(RunExecutionProperties.class).setAutomaticFailureLimit(1);
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "capacity-only");
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        Boolean released = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "releaseForCapacity", receipt.recordId(), lease.token());
        assertEquals(true, released);
        var current = records.selectById(receipt.recordId());
        assertEquals(1, current.getRunStatus());
        assertEquals(0, current.getAttemptCount());
        assertFalse(current.getOccupiesSlot());
        assertNotNull(current.getNextAttemptAt());
    }

    @Test
    void exhaustedPrepareRecoveryIsVisibleAndRequiresReview() throws Exception {
        app.getBean(RunExecutionProperties.class).setAutomaticFailureLimit(1);
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "crashes");
        coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        execute("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second'");
        coordinator.recoverExpired(receipt.recordId());
        assertTrue(records.selectById(receipt.recordId()).getReviewRequired());
        assertTrue(records.selectById(receipt.recordId()).getOccupiesSlot());
        assertEquals(4, records.selectById(receipt.recordId()).getRunStatus());
    }

    @Test
    void persistedStopWinsBeforeEffectsStart() {
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "stop-before-effects");
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        coordinator.stopCommand(receipt.recordId(), "stop-before-effects-command", 99L);
        assertFalse(coordinator.startEffects(receipt.recordId(), lease.token()));
        assertTrue(coordinator.releaseBeforeExecution(receipt.recordId(), lease.token()));
        assertEquals(5, records.selectById(receipt.recordId()).getRunStatus());
    }


    @Test
    void unknownQualityCanOnlyBeResolvedAsFailedWithoutChangingExecutionSuccess() throws Exception {
        var prepared = prepared(); prepared.setQualityTaskId(8L);
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared, "quality-review");
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        coordinator.startEffects(receipt.recordId(), lease.token());
        coordinator.finish(receipt.recordId(), lease.token(), 3, RunProtocol.Effect.COMPLETE, true, "done");
        execute("UPDATE dh_dataforge_etl_record SET quality_status='UNKNOWN',review_required=true WHERE id=" + receipt.recordId());
        var request = new cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlResolveCommandReqVO();
        request.setRecordId(receipt.recordId()); request.setRequestId("quality-resolution");
        request.setExpectedVersion(records.selectById(receipt.recordId()).getRowVersion());
        request.setConfirmedStopped(true); request.setOutputReviewed(true);
        request.setOutcome("SUCCESS"); request.setReason("原实例失败"); request.setEvidenceRef("incident:quality");
        assertThrows(RuntimeException.class, () -> coordinator.resolve(request, 99L));
        request.setOutcome("FAILED");
        coordinator.resolve(request, 99L);
        var after = records.selectById(receipt.recordId());
        assertEquals(3, after.getRunStatus()); assertEquals("FAILED", after.getQualityStatus());
        assertFalse(after.getOccupiesSlot());
    }

    private EtlRecordDO prepared() {
        return EtlRecordDO.builder().pipelineId(10L).pipelineRevision(1).name("published")
                .executionUserId(99L).qualityStatus("NOT_CONFIGURED")
                .configJson("{\"name\":\"published\",\"nodes\":[],\"edges\":[],\"parameters\":[]}").build();
    }

    @Test
    void stopCommandReplayReturnsOriginalReceiptAndCannotTargetAnotherRun() throws Exception {
        var first = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "first");
        var second = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "second");
        var lease = coordinator.claim(first.recordId(), "node-a").orElseThrow();
        coordinator.startEffects(first.recordId(), lease.token());
        RunReceipt receipt = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "stopCommand", first.recordId(), "stop-stable", 99L);
        assertNotNull(receipt);
        assertTrue(receipt.stopRequested());
        assertEquals(2, receipt.runStatus());
        RunReceipt replay = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "stopCommand", first.recordId(), "stop-stable", 99L);
        assertNotNull(replay);
        assertTrue(replay.replayed());
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "stopCommand", second.recordId(), "stop-stable", 99L));
        assertFalse(records.selectById(second.recordId()).getStopRequested());
    }

    @Test
    void manualResolutionRequiresVersionAndExitAndOutputEvidenceAndPersistsReceipt() throws Exception {
        var first = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "review");
        var lease = coordinator.claim(first.recordId(), "node-a").orElseThrow();
        coordinator.startEffects(first.recordId(), lease.token());
        execute("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second'");
        coordinator.recoverExpired(first.recordId());
        var request = new cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlResolveCommandReqVO();
        request.setRecordId(first.recordId()); request.setRequestId("resolve-stable");
        request.setExpectedVersion(records.selectById(first.recordId()).getRowVersion());
        request.setConfirmedStopped(true); request.setOutputReviewed(false); request.setOutcome("FAILED");
        request.setReason("检查执行节点退出及目标写入范围"); request.setEvidenceRef("incident:test-1");
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "resolve", request, 99L));
        assertTrue(records.selectById(first.recordId()).getOccupiesSlot());
        request.setOutputReviewed(true);
        RunReceipt result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator, "resolve", request, 99L);
        assertNotNull(result);
        assertFalse(records.selectById(first.recordId()).getOccupiesSlot());
        RunReceipt replay = org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator, "resolve", request, 99L);
        assertNotNull(replay);
        assertTrue(replay.replayed());
        request.setRequestId("different-request");
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "resolve", request, 99L));
    }

    @Test
    void qualityHasIndependentLeaseAndLateResponseCannotOverwriteNewerDecision() throws Exception {
        var prepared = prepared();
        prepared.setQualityTaskId(200L);
        var receipt = coordinator.enqueueManual(pipelines.selectById(10L), prepared, "quality-a");
        var lease = coordinator.claim(receipt.recordId(), "node-a").orElseThrow();
        coordinator.startEffects(receipt.recordId(), lease.token());
        assertTrue(coordinator.finish(receipt.recordId(), lease.token(), 3, RunProtocol.Effect.COMPLETE, true, "done"));
        assertTrue(records.selectById(receipt.recordId()).getOccupiesSlot());
        @SuppressWarnings("unchecked")
        var claim = (java.util.Optional<EtlRecordDO>) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "claimQuality", receipt.recordId());
        assertNotNull(claim);
        var checking = claim.orElseThrow();
        assertNotNull(checking.getReconcileToken());
        assertNull(checking.getExecutionToken());
        execute("UPDATE dh_dataforge_etl_record SET row_version=row_version+1,quality_status='FAILED',"
                + "occupies_slot=false,reconcile_token=NULL,reconcile_lease_until=NULL WHERE id=" + receipt.recordId());
        Boolean applied = org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator, "completeQuality",
                checking, new cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService.Result("PASSED", 300L, "late"));
        assertEquals(false, applied);
        assertEquals("FAILED", records.selectById(receipt.recordId()).getQualityStatus());
    }

    @Test
    void oneTimePublishAndItsDurableRunAreAtomic() throws Exception {
        execute("UPDATE dh_dataforge_etl_pipeline SET task_type=1");
        var observed = pipelines.selectById(10L);
        var receipt = (RunReceipt) org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator,
                "publish", observed, prepared().getConfigJson(), 99L, prepared());
        assertNotNull(receipt);
        assertEquals(2, records.selectById(receipt.recordId()).getExecuteType());
        assertEquals(4L, pipelines.selectById(10L).getScheduleGeneration());
        assertEquals(2, pipelines.selectById(10L).getPublishedRevision());
        assertNull(pipelines.selectById(10L).getNextRunTime());
    }

    @Test
    void failedOneTimeRunInsertionRollsBackPublication() throws Exception {
        execute("UPDATE dh_dataforge_etl_pipeline SET task_type=1");
        execute("CREATE FUNCTION reject_run() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'publish insertion failed'; END $$");
        execute("CREATE TRIGGER reject_run BEFORE INSERT ON dh_dataforge_etl_record FOR EACH ROW EXECUTE FUNCTION reject_run()");
        var observed = pipelines.selectById(10L);
        assertThrows(RuntimeException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator,
                "publish", observed, prepared().getConfigJson(), 99L, prepared()));
        assertEquals(1, pipelines.selectById(10L).getPublishedRevision());
        assertEquals(3L, pipelines.selectById(10L).getScheduleGeneration());
        assertEquals(scheduled, pipelines.selectById(10L).getNextRunTime());
    }

    @Test
    void scheduleDisablePreservesAlreadyAcceptedAutomaticAndManualRuns() throws Exception {
        var auto = coordinator.enqueueScheduled(pipelines.selectById(10L), prepared()).orElseThrow();
        var manual = coordinator.enqueueManual(pipelines.selectById(10L), prepared(), "manual-survives");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator, "changeSchedule", 10L, false);
        assertEquals(1, records.selectById(auto.recordId()).getRunStatus());
        assertEquals(1, records.selectById(manual.recordId()).getRunStatus());
        assertNull(pipelines.selectById(10L).getNextRunTime());
        assertEquals(4L, pipelines.selectById(10L).getScheduleGeneration());
    }

    private void execute(String sql) throws SQLException {
        try (var statement = control.createStatement()) { statement.execute(sql); }
    }
}
