package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunMetricsMapper;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunMetricsSnapshot;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.iocoder.yudao.module.dataforge.service.execution.DistributedRunFaultSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Timeout(60)
class RunMetricsPostgreSqlTest {
    private DistributedRunFaultSupport fixture;
    private RunMetricsMapper mapper;
    private MybatisConfiguration mybatis;

    @BeforeEach void setup() throws Exception {
        fixture = new DistributedRunFaultSupport(System.getProperty("datahub.test.postgres.url"));
        var config = new MybatisConfiguration();
        mybatis = config;
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("metrics", new SpringManagedTransactionFactory(),
                source(fixture.url, fixture.schema)));
        config.addMapper(RunMetricsMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(RunMetricsMapper.class);
    }
    @AfterEach void cleanup() throws Exception { if (fixture != null) fixture.close(); }

    @ParameterizedTest @EnumSource(Domain.class)
    void durableSnapshotSeparatesQueueHeldStoppedUnknownAndDeletedHistory(Domain domain) {
        fixture.seed(domain, 10, 1);
        long waiting = fixture.enqueue(domain, 10, 1, "waiting");
        fixture.db.update("UPDATE " + domain.runs + " SET create_time=clock_timestamp()-interval '120 seconds' WHERE id=?", waiting);
        // Legacy state is deliberately included; a held soft-deleted UNKNOWN must not disappear.
        fixture.db.update("INSERT INTO " + domain.runs + "(id," + domain.taskColumn + ",run_status,occupies_slot,review_required,deleted,create_time,stop_requested,stop_requested_at) VALUES (11,11,?,true,true,1,clock_timestamp()-interval '300 seconds',true,clock_timestamp()-interval '40 seconds'),(12,12,?,true,true,0,clock_timestamp()-interval '400 seconds',true,clock_timestamp()-interval '60 seconds'),(13,13,3,false,false,1,clock_timestamp()-interval '900 seconds',false,NULL)",
                domain.unknown, domain.stopped);
        TenantContextHolder.setTenantId(999L);
        RunMetricsSnapshot snapshot = sample(domain);
        assertEquals(1, snapshot.getQueued());
        assertEquals(2, snapshot.getSlots());
        assertEquals(1, snapshot.getUnknown(), "stopped status must not be counted as UNKNOWN");
        assertEquals(2, snapshot.getReview());
        assertEquals(1, snapshot.getStopPending(), "confirmed partial-output stop holds a slot, not a pending stop");
        assertTrue(snapshot.getQueueOldestAge() >= 120 && snapshot.getQueueOldestAge() < 130);
        assertTrue(snapshot.getUnknownOldestRunAge() >= 300 && snapshot.getUnknownOldestRunAge() < 310);
        assertTrue(snapshot.getStopOldestAge() >= 40 && snapshot.getStopOldestAge() < 50);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void expiredLeaseAndUnacceptedScheduleRemainObservable(Domain domain) {
        fixture.seed(domain, 10, 1);
        fixture.seed(domain, 20, 2);
        fixture.db.update("UPDATE " + domain.tasks + " SET next_run_time=clock_timestamp()-interval '60 seconds' WHERE id=10");
        fixture.db.update("UPDATE " + domain.tasks + " SET next_run_time=clock_timestamp()+interval '60 seconds' WHERE id=20");
        long run = fixture.enqueue(domain, 10, 1, "running");
        identity(1);
        fixture.runtime.claim(domain, run, "node").orElseThrow();
        fixture.db.update("UPDATE " + domain.runs + " SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", run);
        var snapshot = sample(domain);
        assertEquals(1, snapshot.getExpired());
        assertEquals(1, snapshot.getDue());
        assertEquals(0, snapshot.getQueued());
        assertEquals(1, snapshot.getSlots());
        assertTrue(snapshot.getOverdueAge() >= 60 && snapshot.getOverdueAge() < 70);
    }

    @Test void etlQualityUnknownCountsAsReviewWithoutPretendingExecutionIsUnknown() {
        fixture.db.update("INSERT INTO dh_dataforge_etl_record(id,pipeline_id,run_status,occupies_slot,quality_status,review_required) VALUES(1,10,3,true,'UNKNOWN',false)");
        var snapshot = mapper.selectEtl();
        assertEquals(1, snapshot.getQualityPending());
        assertEquals(1, snapshot.getReview());
        assertEquals(0, snapshot.getUnknown());
        assertEquals(0, snapshot.getStopPending());
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void realClaimAndCapacityRejectionPublishOnceAndUnknownIsNotConfirmedStop(Domain domain) {
        fixture.seed(domain, 10, 1);
        long run = fixture.enqueue(domain, 10, 1, "run");
        identity(1);
        var lease = fixture.runtime.claim(domain, run, "node").orElseThrow();
        assertEquals(1, count(domain, "claimed"));
        assertTrue(fixture.runtime.claim(domain, run, "other").isEmpty());
        assertEquals(1, count(domain, "claimed"));
        boolean rejected = domain == Domain.ETL
                ? fixture.runtime.etl.releaseForCapacity(run, lease.token()) : fixture.runtime.access.releaseRejected(lease);
        assertTrue(rejected);
        assertEquals(1, count(domain, "capacity.rejected"));
        fixture.db.update("UPDATE " + domain.runs + " SET next_attempt_at=NULL WHERE id=?", run);
        lease = fixture.runtime.claim(domain, run, "node").orElseThrow();
        assertTrue(fixture.runtime.start(domain, lease));
        fixture.runtime.stop(domain, run, "stop");
        fixture.db.update("UPDATE " + domain.runs + " SET stop_requested_at=clock_timestamp()-interval '7 seconds' WHERE id=?", run);
        assertTrue(fixture.runtime.finish(domain, lease, domain.stopped, RunProtocol.Effect.UNKNOWN, false));
        assertEquals(0, fixture.runtime.metricsRegistry.get("dataforge.run.stop.confirmation").tag("domain", domain.name()).timer().count());
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void confirmedStopAndReviewReplayDoNotInflateCounters(Domain domain) {
        fixture.seed(domain, 10, 1);
        long run = fixture.enqueue(domain, 10, 1, "run");
        identity(1);
        var lease = fixture.runtime.claim(domain, run, "node").orElseThrow();
        assertTrue(fixture.runtime.start(domain, lease));
        fixture.runtime.stop(domain, run, "stop");
        fixture.db.update("UPDATE " + domain.runs + " SET stop_requested_at=clock_timestamp()-interval '7 seconds' WHERE id=?", run);
        assertTrue(fixture.runtime.finish(domain, lease, domain.stopped, RunProtocol.Effect.PARTIAL, true));
        var timer = fixture.runtime.metricsRegistry.get("dataforge.run.stop.confirmation").tag("domain", domain.name()).timer();
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS) >= 7);
        long version = fixture.db.queryForObject("SELECT row_version FROM " + domain.runs + " WHERE id=?", Long.class, run);
        if (domain == Domain.ETL) {
            var request = new cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlResolveCommandReqVO();
            request.setRecordId(run); request.setRequestId("review"); request.setExpectedVersion(version);
            request.setOutcome("STOPPED"); request.setConfirmedStopped(true); request.setOutputReviewed(true);
            request.setReason("reviewed"); request.setEvidenceRef("evidence");
            fixture.runtime.etl.resolve(request, ACTOR);
            assertTrue(fixture.runtime.etl.resolve(request, ACTOR).replayed());
        } else {
            fixture.runtime.access.resolve(run, "review", version, "STOPPED", true, true, "reviewed", "evidence");
            assertTrue(fixture.runtime.access.resolve(run, "review", version, "STOPPED", true, true, "reviewed", "evidence").replayed());
        }
        assertEquals(1, count(domain, "resolved"));
        assertEquals(1, timer.count(), "review of previously confirmed partial output must not recount exit");
    }

    @Test void realEtlOuterRollbackDoesNotPublishClaim() {
        fixture.seed(Domain.ETL, 10, 1);
        long run = fixture.enqueue(Domain.ETL, 10, 1, "run");
        identity(1);
        var transaction = new TransactionTemplate(fixture.runtime.app.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            assertTrue(fixture.runtime.claim(Domain.ETL, run, "node").isPresent());
            assertEquals(0, count(Domain.ETL, "claimed"));
            status.setRollbackOnly();
        });
        assertEquals(0, count(Domain.ETL, "claimed"));
        assertFalse(fixture.runtime.records.selectById(run).getOccupiesSlot());
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void metricReadFailureCannotRollBackConfirmedTerminalState(Domain domain) {
        fixture.seed(domain, 10, 1);
        long run = fixture.enqueue(domain, 10, 1, "run");
        identity(1);
        var lease = fixture.runtime.claim(domain, run, "node").orElseThrow();
        assertTrue(fixture.runtime.start(domain, lease));
        fixture.runtime.stop(domain, run, "stop");
        if (domain == Domain.ETL) {
            var original = fixture.runtime.records;
            var failing = org.mockito.Mockito.spy(original);
            org.mockito.Mockito.doAnswer(call -> {
                var record = original.selectById((Long) call.getArgument(0));
                if (record != null && record.getRunStatus() == domain.stopped)
                    throw new org.springframework.dao.DataAccessResourceFailureException("injected metric read");
                return record;
            }).when(failing).selectById(org.mockito.ArgumentMatchers.any(java.io.Serializable.class));
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.runtime.etl, "records", failing);
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("metric-only fault"))
                    .when(failing).selectStopTiming(
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        } else {
            var original = fixture.runtime.accessRuns;
            var failing = org.mockito.Mockito.spy(original);
            org.mockito.Mockito.doAnswer(call -> {
                var record = original.selectOne(
                        (com.baomidou.mybatisplus.core.conditions.Wrapper<cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO>)
                                call.getArgument(0));
                if (record != null && record.getRunStatus() == domain.stopped)
                    throw new org.springframework.dao.DataAccessResourceFailureException("injected metric read");
                return record;
            }).when(failing).selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.runtime.access, "runs", failing);
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("metric-only fault"))
                    .when(failing).selectStopTiming(
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        }
        assertDoesNotThrow(() -> assertTrue(fixture.runtime.finish(domain, lease,
                domain.stopped, RunProtocol.Effect.PARTIAL, true)));
        assertEquals(domain.stopped, fixture.db.queryForObject(
                "SELECT run_status FROM " + domain.runs + " WHERE id=?", Integer.class, run));
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void aggregateUsesActiveIndexWithLargeHistoricalTail(Domain domain) throws Exception {
        fixture.seed(domain, 10, 1);
        fixture.enqueue(domain, 10, 1, "waiting");
        fixture.db.execute("INSERT INTO " + domain.runs + "(id," + domain.taskColumn
                + ",run_status,create_time) SELECT -n,n,3,clock_timestamp()-interval '1 year'"
                + " FROM generate_series(1,30000) n");
        applyMetricsMigration();
        fixture.db.execute("ANALYZE " + domain.runs);
        String sql = mybatis.getMappedStatement(RunMetricsMapper.class.getName()
                + (domain == Domain.ETL ? ".selectEtl" : ".selectAccess")).getBoundSql(null).getSql();
        String plan = fixture.db.queryForObject("EXPLAIN (FORMAT JSON) " + sql, String.class);
        var scans = new java.util.ArrayList<String>();
        collectScans(cn.hutool.json.JSONUtil.parseArray(plan), domain.runs, scans);
        assertFalse(scans.isEmpty(), plan);
        assertFalse(scans.contains("Seq Scan"), plan);
        assertEquals(1, sample(domain).getQueued());
    }

    private void applyMetricsMigration() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        String relative = "sql/postgresql/migrations/0040_dataforge_run-metrics-indexes.sql";
        while (root != null && !java.nio.file.Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root, "numbered metrics migration");
        String sql = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve(relative)).replace("datahub-center", fixture.schema);
        fixture.db.execute(sql);
        fixture.db.execute(sql);
    }

    private void collectScans(Object node, String table, java.util.List<String> scans) {
        if (node instanceof java.util.Map<?, ?> map) {
            if (table.equals(map.get("Relation Name"))) scans.add(String.valueOf(map.get("Node Type")));
            map.values().forEach(value -> collectScans(value, table, scans));
        } else if (node instanceof Iterable<?> list) {
            list.forEach(value -> collectScans(value, table, scans));
        }
    }

    private RunMetricsSnapshot sample(Domain domain) { return domain == Domain.ETL ? mapper.selectEtl() : mapper.selectAccess(); }
    private double count(Domain domain, String event) {
        return fixture.runtime.metricsRegistry.get("dataforge.run." + event).tag("domain", domain.name()).counter().count();
    }
}
