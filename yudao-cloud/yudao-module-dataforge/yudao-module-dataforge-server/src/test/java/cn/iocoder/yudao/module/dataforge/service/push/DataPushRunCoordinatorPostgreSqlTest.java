package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.*;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.push.channel.DbPushChannel;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实生产 Mapper 和事务，仅测试回环库的随机命名空间。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class DataPushRunCoordinatorPostgreSqlTest {
    @Test void crossNodeCancellationMustPersistAlertWithoutAfterFinishedCallback() throws Exception {
        var run = claim();
        assertTrue(coordinator.startSending(run));
        assertTrue(node().cancel(10L));
        assertFalse(node().cancel(10L));
        long count = 0;
        try (var statement = control.createStatement();
             var exists = statement.executeQuery("SELECT to_regclass('dh_dataforge_push_alert')")) {
            if (exists.next() && exists.getString(1) != null) {
                try (var lookup = control.createStatement();
                     var result = lookup.executeQuery("SELECT count(*) FROM dh_dataforge_push_alert WHERE run_id=" + run.getId())) {
                    result.next(); count = result.getLong(1);
                }
            }
        }
        assertEquals(1, count, "取消状态提交时必须已有持久告警，不依赖原节点回调");
    }

    private Connection control;
    private String schema;
    private Path root;
    private DataPushTaskMapper tasks;
    private DataPushTaskLogMapper runs;
    private DataPushIncreaseLogMapper watermarks;
    private DataSourceTransactionManager manager;
    private DataPushRunCoordinator coordinator;
    private DataPushAlertMapper alertMapper;
    private DataPushAlertService alertService;
    private cn.iocoder.yudao.module.dataforge.framework.rpc.PushAlertNotifyClient notifyClient;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "pushguard_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.createBaselineTables(control, schema,
                "dh_dataforge_push_task", "dh_dataforge_push_task_log", "dh_dataforge_push_increase_log",
                "dh_dataforge_push_alert", "dh_dataforge_push_alert_disposition");
        // 夹具仅抽取 CREATE TABLE，补齐 0246 后续添加的当前 Mapper 列。
        sql("ALTER TABLE dh_dataforge_push_task ADD COLUMN IF NOT EXISTS category_id bigint");
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        manager = new DataSourceTransactionManager(source);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var config = new MybatisConfiguration();
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("push-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(DataPushTaskMapper.class, DataPushTaskLogMapper.class, DataPushIncreaseLogMapper.class, DataPushAlertMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        tasks = session.getMapper(DataPushTaskMapper.class); runs = session.getMapper(DataPushTaskLogMapper.class);
        watermarks = session.getMapper(DataPushIncreaseLogMapper.class);
        alertMapper = session.getMapper(DataPushAlertMapper.class);
        notifyClient = mock(cn.iocoder.yudao.module.dataforge.framework.rpc.PushAlertNotifyClient.class);
        alertService = new DataPushAlertService();
        ReflectionTestUtils.setField(alertService, "alerts", alertMapper);
        ReflectionTestUtils.setField(alertService, "transactionManager", manager);
        ReflectionTestUtils.setField(alertService, "notifyClient", notifyClient);
        coordinator = node(); tenant(1);
        seedTask(10, 1); seedTask(20, 2);
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear();
        if (control != null) try {
            if (schema != null && schema.matches("pushguard_[a-f0-9]{32}")) sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally { control.close(); }
    }

    private DataPushRunCoordinator node() {
        var result = new DataPushRunCoordinator();
        ReflectionTestUtils.setField(result, "tasks", tasks); ReflectionTestUtils.setField(result, "runs", runs);
        ReflectionTestUtils.setField(result, "watermarks", watermarks); ReflectionTestUtils.setField(result, "transactionManager", manager);
        ReflectionTestUtils.setField(result, "alerts", alertService);
        return result;
    }
    private static void tenant(long id) { TenantContextHolder.setTenantId(id); TenantContextHolder.setIgnore(false); }
    private void seedTask(long id, long tenant) throws Exception {
        sql("INSERT INTO dh_dataforge_push_task(id,push_config_id,name,source_database_id,table_name,push_type,push_mode,task_type,status,creator) VALUES (" + id + ",1,'test',1,'orders',1,2,1,1,'99')");
    }
    private void sql(String value) throws Exception { try (var statement = control.createStatement()) { statement.execute(value); } }
    private void assumeHistoricalMigration(String number) throws Exception {
        try (var files = Files.list(root.resolve("sql/postgresql/migrations"))) {
            org.junit.jupiter.api.Assumptions.assumeTrue(files.anyMatch(p -> p.getFileName().toString().startsWith(number + "_")),
                    "Historical numbered migration was consolidated into baseline");
        }
    }

    private void migrate() throws Exception {
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0054_dataforge_push-unknown-guard.sql")).replace("\"datahub-center\"", schema));
    }
    private void migrateDispatch() throws Exception {
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0055_dataforge_push-durable-dispatch.sql")).replace("\"datahub-center\"", schema));
    }
    private DataPushTaskLogDO claim() {
        var accepted = coordinator.claim(10L, PushRunTypeEnum.HAND);
        return accepted == null ? null : coordinator.acquireDispatch(accepted);
    }
    private void migrateResolution() throws Exception {
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0056_dataforge_push-resolution-audit.sql")).replace("\"datahub-center\"", schema));
    }

    private void migrateAlert() throws Exception {
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
    }

    private DataPushAlertDO alert() {
        return alertMapper.selectList().getFirst();
    }

    private void migrateAlertTemplate() throws Exception {
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0059_dataforge_push-alert-template.sql")).replace("\"datahub-center\"", schema));
    }

    @Test void templateMigrationRequiresRealPrerequisiteAndReplaysWithoutOverwrite() throws Exception {
        assumeHistoricalMigration("0059");
        assertThrows(SQLException.class, this::migrateAlertTemplate); sql("ROLLBACK");
        sql("CREATE TABLE system_notify_template(id bigserial PRIMARY KEY,name varchar(100) NOT NULL,code varchar(100) NOT NULL,type smallint NOT NULL,nickname varchar(100),content varchar(2000),params varchar(1000),status smallint,remark varchar(500),creator varchar(64),updater varchar(64),deleted smallint DEFAULT 0)");
        sql("INSERT INTO system_notify_template(id,name,code,type,status) VALUES(1,'other','other',1,0)");
        migrateAlertTemplate(); migrateAlertTemplate();
        try (var statement=control.createStatement(); var result=statement.executeQuery("SELECT count(*),max(content) FROM system_notify_template WHERE code='dataforge_push_outcome'")) {
            result.next(); assertEquals(1,result.getInt(1)); assertTrue(result.getString(2).contains("{alertId}"));
        }
        sql("UPDATE system_notify_template SET status=1 WHERE code='dataforge_push_outcome'");
        assertThrows(SQLException.class,this::migrateAlertTemplate); sql("ROLLBACK");
        sql("UPDATE system_notify_template SET status=0,content='operator-change' WHERE code='dataforge_push_outcome'");
        assertThrows(SQLException.class,this::migrateAlertTemplate); sql("ROLLBACK");
        try(var statement=control.createStatement(); var result=statement.executeQuery("SELECT content FROM system_notify_template WHERE code='dataforge_push_outcome'")) {
            result.next(); assertEquals("operator-change",result.getString(1));
        }
    }

    @Test void baselineAlertTablesMatchMigrationSlice() throws Exception {
        var baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        String alternate=schema+"_baseline"; sql("CREATE SCHEMA "+alternate);
        try {
            for(String table:List.of("dh_dataforge_push_alert","dh_dataforge_push_alert_disposition")) {
                var ddl=Pattern.compile("CREATE TABLE IF NOT EXISTS \\\"datahub-center\\\"[.]"+table+" \\(.*?\\n\\);",Pattern.DOTALL).matcher(baseline);
                assertTrue(ddl.find(),table); sql(ddl.group().replace("\"datahub-center\"",alternate));
                cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, alternate);
                String definition="SELECT column_name,data_type,is_nullable,character_maximum_length FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position";
                List<String> left=new ArrayList<>(),right=new ArrayList<>();
                try(var prepared=control.prepareStatement(definition)) {
                    prepared.setString(1,schema); prepared.setString(2,table);
                    try(var rs=prepared.executeQuery()) { while(rs.next()) left.add(rs.getString(1)+":"+rs.getString(2)+":"+rs.getString(3)+":"+rs.getString(4)); }
                    prepared.setString(1,alternate);
                    try(var rs=prepared.executeQuery()) { while(rs.next()) right.add(rs.getString(1)+":"+rs.getString(2)+":"+rs.getString(3)+":"+rs.getString(4)); }
                }
                assertEquals(left,right);
            }
        } finally { sql("DROP SCHEMA "+alternate+" CASCADE"); }
    }

    @Test void notificationLostReceiptCanProduceDuplicateAlertsButNeverNewBusinessRun() throws Exception {
        unknown(); var row=alert(); var received=new java.util.concurrent.atomic.AtomicInteger();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            if(received.incrementAndGet()==1) throw new java.net.SocketTimeoutException("lost response after receiver accepted");
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(321L);
        });
        assertFalse(alertService.deliver(row.getId()));
        sql("UPDATE dh_dataforge_push_alert SET available_at=clock_timestamp()-interval '1 second'");
        assertTrue(alertService.deliver(row.getId())); assertEquals(2,received.get());
        assertEquals(1,runs.selectList().size()); assertEquals(5,runs.selectById(row.getRunId()).getRunStatus());
        assertEquals(2,alert().getAttempts());
    }

    @Test void auditFailureRollsBackManualRetry() throws Exception {
        unknown(); var row=alert(); sql("UPDATE dh_dataforge_push_alert SET status=3");
        sql("ALTER TABLE dh_dataforge_push_alert_disposition ADD CONSTRAINT fail_audit CHECK(false)");
        assertThrows(RuntimeException.class,()->alertService.dispose(row.getId(),"RETRY","CASE-4","xxl-job:4:5"));
        assertEquals(3,alert().getStatus()); assertEquals(0,alertMapper.commandExists("xxl-job:4:5"));
    }

    @Test void unknownFinishAndOutboxRollbackTogether() throws Exception {
        var run = claim(); coordinator.startSending(run); run.setRunStatus(5);
        var broken = node(); var failing = mock(DataPushAlertService.class);
        doThrow(new IllegalStateException("fixture")).when(failing).enqueue(any(), any(), any());
        ReflectionTestUtils.setField(broken, "alerts", failing);
        assertThrows(RuntimeException.class, () -> broken.finish(run, 2, null));
        assertEquals(2, runs.selectById(run.getId()).getRunStatus()); assertTrue(alertMapper.selectList().isEmpty());
        assertTrue(coordinator.finish(run, 2, null)); assertEquals(1, alertMapper.selectList().size());
        assertFalse(coordinator.finish(run, 2, null)); assertEquals(1, alertMapper.selectList().size());
    }

    @Test void recoveryAndDispatchExhaustionProduceDurableAlerts() throws Exception {
        var run = claim(); coordinator.startSending(run); expireLease(run); run.setRunStatus(2);
        assertEquals(1, coordinator.recoverStale(run, 0)); assertEquals("UNKNOWN", alert().getEventType());
        tenant(2); var waiting = coordinator.claim(20L, PushRunTypeEnum.HAND);
        sql("UPDATE dh_dataforge_push_task_log SET dispatch_attempts=5 WHERE id=" + waiting.getId());
        assertNull(coordinator.acquireDispatch(waiting));
        assertTrue(alertMapper.selectList().stream().anyMatch(a -> "DISPATCH_EXHAUSTED".equals(a.getEventType()))); assertEquals(4, runs.selectById(waiting.getId()).getRunStatus());
    }

    @Test void cancellationBeforeSendingDoesNotCreateUnknownAlert() {
        claim(); coordinator.cancel(10L); assertTrue(alertMapper.selectList().isEmpty());
    }

    @Test void orphanedRunningRecordStillRecoversToUnknownAndDeadAlert() throws Exception {
        var run=claim(); coordinator.startSending(run); run.setRunStatus(2); expireLease(run);
        sql("DELETE FROM dh_dataforge_push_task WHERE id=10");
        assertEquals(1,coordinator.recoverStale(run,0));
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());
        assertEquals(3,alert().getStatus()); assertEquals("NO_RECIPIENT",alert().getLastError());
    }

    @Test void publicationCancellationKeepsOriginalRecipientAndHistoryAfterResolution() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET release_user_id=101 WHERE id=10");
        var run = claim(); coordinator.startSending(run); coordinator.cancelPublication(10L);
        assertEquals(101L, alert().getRecipientId());
        expireLease(run); coordinator.resolve(review(run.getId()), 99L);
        assertEquals("UNKNOWN", alert().getEventType()); assertEquals(0, alert().getStatus());
    }

    @Test void criticalAlertSurvivesMissingRecipientAndAllowsAuditedManualAcknowledgment() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET creator='not-an-id',release_user_id=NULL WHERE id=10");
        unknown(); var row = alert(); assertEquals(3, row.getStatus()); assertEquals("NO_RECIPIENT", row.getLastError());
        assertThrows(RuntimeException.class, () -> alertService.dispose(row.getId(), "RETRY", "CASE-1", "xxl-job:1:1"));
        alertService.dispose(row.getId(), "ACKNOWLEDGE", "CASE-1 人工通知已接管", "xxl-job:1:2");
        alertService.dispose(row.getId(), "ACKNOWLEDGE", "CASE-1 人工通知已接管", "xxl-job:1:2");
        assertEquals(4, alert().getStatus()); assertEquals(5, runs.selectById(row.getRunId()).getRunStatus());
        assertThrows(RuntimeException.class, () -> alertService.dispose(row.getId(), "ACKNOWLEDGE", "changed", "xxl-job:1:2"));
    }

    @Test void senderChecksReceiptAndRunsOutsideTransaction() throws Exception {
        unknown(); var row = alert();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(String.valueOf(row.getId()), ((cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO) call.getArgument(0)).getTemplateParams().get("alertId"));
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(9007199254740993L);
        });
        assertTrue(alertService.deliver(row.getId())); assertTrue(alertService.deliver(row.getId()));
        verify(notifyClient, times(1)).sendSingleMessageToAdmin(any());
        assertEquals(2, alert().getStatus()); assertEquals(9007199254740993L, alert().getNotifyMessageId());
        assertEquals(5, runs.selectById(row.getRunId()).getRunStatus());
    }

    @Test void lostReceiptRetriesOnlyNotificationAndStopsAtLimit() throws Exception {
        unknown(); var row = alert();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("sensitive-fixture"));
        for (int i=0; i<5; i++) {
            sql("UPDATE dh_dataforge_push_alert SET available_at=clock_timestamp()-interval '1 second'");
            assertFalse(alertService.deliver(row.getId()));
        }
        assertEquals(3, alert().getStatus()); assertEquals(5, alert().getAttempts());
        assertEquals("DELIVERY_UNCONFIRMED", alert().getLastError());
        alertService.deliver(row.getId()); verify(notifyClient, times(5)).sendSingleMessageToAdmin(any());
        assertEquals(5, runs.selectById(row.getRunId()).getRunStatus()); assertNull(watermarks.selectNewest(10L));
        alertService.dispose(row.getId(), "RETRY", "CASE-2 允许重复告警", "xxl-job:2:3");
        assertEquals(0, alert().getStatus()); assertEquals(0, alert().getAttempts());
        assertEquals(1, alertMapper.matchesCommand( "xxl-job:2:3", row.getId(), "RETRY", "CASE-2 允许重复告警"));
    }

    @Test void nullAndBusinessErrorReceiptsNeverMarkDelivered() throws Exception {
        unknown(); var row = alert();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(null));
        assertFalse(alertService.deliver(row.getId())); assertEquals("MISSING_RECEIPT", alert().getLastError());
        sql("UPDATE dh_dataforge_push_alert SET available_at=clock_timestamp()-interval '1 second'");
        when(notifyClient.sendSingleMessageToAdmin(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.error(400, "sensitive-fixture"));
        assertFalse(alertService.deliver(row.getId())); assertEquals("REMOTE_REJECTED", alert().getLastError());
    }

    @Test void alertClaimsAreExclusiveAndOldLeaseCannotWriteBack() throws Exception {
        unknown(); var row = alert(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> action = () -> { tenant(1); try { gate.await(); return alertMapper.claim(row.getId(), UUID.randomUUID().toString()); } finally { TenantContextHolder.clear(); } };
            var one=pool.submit(action); var two=pool.submit(action); gate.countDown();
            assertEquals(1, one.get(5, TimeUnit.SECONDS)+two.get(5, TimeUnit.SECONDS));
        }
        String old = alert().getLeaseToken();
        sql("UPDATE dh_dataforge_push_alert SET lease_until=clock_timestamp()-interval '1 second',available_at=clock_timestamp()-interval '1 second'");
        assertEquals(1, alertMapper.claim(row.getId(), "new-token"));
        assertEquals(0, alertMapper.delivered(row.getId(), old, 1L));
        assertEquals(0, alertMapper.failed(row.getId(), old, "OLD"));
        assertEquals(1, alertMapper.delivered(row.getId(), "new-token", 2L));
    }

    @Test void twoAlertWorkersWithRealTransactionsSendOnlyOnceWhileLeaseActive() throws Exception {
        unknown(); var id=alert().getId(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(123L);
        });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->{tenant(1);try{return alertService.deliver(id);}finally{TenantContextHolder.clear();}});
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(alertService.deliver(id));
            } finally { release.countDown(); }
            assertTrue(first.get(5,TimeUnit.SECONDS));
        }
        verify(notifyClient,times(1)).sendSingleMessageToAdmin(any()); assertEquals(2,alert().getStatus());
    }

    @Test void manualDispositionAuditIsNeverRemovedByNormalAlertCleanup() throws Exception {
        unknown(); var row=alert(); sql("UPDATE dh_dataforge_push_alert SET status=3");
        alertService.dispose(row.getId(),"RETRY","CASE-5","xxl-job:5:6");
        sql("UPDATE dh_dataforge_push_alert SET status=2,handled_at=clock_timestamp()-interval '100 days'");
        sql("DELETE FROM dh_dataforge_push_task_log WHERE id="+row.getRunId());
        assertEquals(0,alertMapper.cleanDelivered());
        assertEquals(1,alertMapper.commandExists("xxl-job:5:6"));
    }

    @Test void finalAttemptCrashBecomesDeadAndNeverSendsAgain() throws Exception {
        unknown(); var row = alert();
        sql("UPDATE dh_dataforge_push_alert SET status=1,attempts=5,lease_token='lost',lease_until=clock_timestamp()-interval '1 second',available_at=clock_timestamp()-interval '1 second'");
        alertService.deliver(row.getId()); assertEquals(3, alert().getStatus()); verifyNoInteractions(notifyClient);
    }

    @Test void alertIdentityAndTransactionBoundaryAreEnforced() throws Exception {
        unknown(); var id=alert().getId();
        assertThrows(RuntimeException.class, () -> alertService.enqueue(tasks.selectById(10L), null, "UNKNOWN"));
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(manager).execute(status -> alertService.deliver(id)));
        assertTrue(alertService.deliver(-1L));
        assertThrows(RuntimeException.class, () -> alertService.dispose(-1L, "ACKNOWLEDGE", "CASE-3", "xxl-job:3:4"));
        verifyNoInteractions(notifyClient);
    }

    @Test void reconcileBackfillsMissingUnknownWithoutReplayingBusinessAndDoesNotRecreateDelivered() throws Exception {
        var run=claim(); sql("UPDATE dh_dataforge_push_task_log SET run_status=5 WHERE id="+run.getId());
        var candidates = alertMapper.missingUnknown(); assertEquals(1,candidates.size());
        coordinator.reconcileAlert(candidates.getFirst()); coordinator.reconcileAlert(candidates.getFirst());
        assertEquals(1,alertMapper.selectList().size()); assertTrue(alertMapper.missingUnknown().isEmpty());
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());
    }

    @Test void alertMigrationReplayKeepsInFlightLeaseAndCleanupNeverDeletesLiveEvidence() throws Exception {
        unknown(); var row=alert(); alertMapper.claim(row.getId(),"live"); migrateAlert(); migrateAlert();
        assertEquals("live",alert().getLeaseToken());
        assertEquals(0,alertMapper.cleanDelivered());
        alertMapper.delivered(row.getId(),"live",123L);
        sql("UPDATE dh_dataforge_push_alert SET handled_at=clock_timestamp()-interval '100 days'");
        assertEquals(0,alertMapper.cleanDelivered()); // 原未知执行仍在，必须保留去重证据。
        sql("DELETE FROM dh_dataforge_push_task_log WHERE id="+row.getRunId());
        assertEquals(1,alertMapper.cleanDelivered());
    }

    private cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushResolveReqVO review(Long id) {
        var req = new cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushResolveReqVO();
        req.setRecordId(id); req.setRequestId(UUID.randomUUID().toString()); req.setOutcome("NO_EFFECT");
        req.setReason("已关联本次执行核对所有批次和远端请求"); req.setConfirmedStopped(true); req.setOutputReviewed(true);
        req.setStoppedEvidenceRef("CASE-STOP-1"); req.setOutputEvidenceRef("CASE-OUTPUT-1"); return req;
    }
    private DataPushTaskLogDO unknown() throws Exception {
        var run=claim(); assertTrue(coordinator.startSending(run)); assertTrue(coordinator.cancel(10L));
        expireLease(run); return runs.selectById(run.getId());
    }

    @Test void resolutionPreservesUnknownEvidenceAndWatermarkAndStopsScheduleAtomically() throws Exception {
        dueTask(); var observed=tasks.selectById(10L);
        var run=unknown(); var end=run.getEndTime(); String error=run.getErrorInfo();
        watermarks.upsert(watermark(10L)); var req=review(run.getId());
        var result=coordinator.resolve(req,99L);
        assertEquals(6,result.getRunStatus()); assertEquals(error,result.getErrorInfo()); assertEquals(end,result.getEndTime());
        assertEquals(99L,result.getResolvedBy()); assertNotNull(result.getResolvedAt());
        assertEquals(req.getRequestId(),result.getResolutionRequestId()); assertNull(result.getDispatchToken());
        assertEquals("2",watermarks.selectNewest(10L).getEndVal()); assertFalse(coordinator.isBlocked(10L));
        var task=tasks.selectById(10L); assertEquals(0,task.getStatus()); assertNull(task.getNextRunTime());
        assertEquals(observed.getScheduleGeneration()+1,task.getScheduleGeneration());
        assertNull(coordinator.enqueueScheduled(10L,observed.getScheduleGeneration(),observed.getNextRunTime()));
        assertNull(coordinator.claim(10L,PushRunTypeEnum.AUTO)); // 不自动补跑；手动恢复另行授权。
    }

    @Test void resolutionReplayReturnsSameReceiptWithoutPausingLaterPublication() throws Exception {
        var req=review(unknown().getId()); var first=coordinator.resolve(req,99L);
        sql("UPDATE dh_dataforge_push_task SET status=1,schedule_generation=20 WHERE id=10");
        var replay=coordinator.resolve(req,99L);
        assertEquals(first.getResolvedAt(),replay.getResolvedAt()); assertEquals(1,tasks.selectById(10L).getStatus());
        assertEquals(20L,tasks.selectById(10L).getScheduleGeneration());
        req.setReason("修改了证据"); assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertEquals(first.getResolutionReason(),runs.selectById(first.getId()).getResolutionReason());
    }

    @Test void resolutionRejectsDifferentActorOrSecondDecision() throws Exception {
        var req=review(unknown().getId()); coordinator.resolve(req,99L);
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,100L));
        req.setRequestId(UUID.randomUUID().toString());
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
    }

    @Test void activeLeaseAndMissingProofNeverReleaseUnknown() throws Exception {
        var run=claim(); coordinator.startSending(run); coordinator.cancel(10L); var req=review(run.getId());
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L)); // 尚未过期的领取仍保护旧工作者。
        expireLease(run);
        req.setConfirmedStopped(false); assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        req.setConfirmedStopped(true); req.setOutputReviewed(false);
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        req.setOutputReviewed(true); req.setStoppedEvidenceRef(" ");
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        req.setStoppedEvidenceRef("CASE-STOP-1"); req.setOutputEvidenceRef("");
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        req.setOutputEvidenceRef("CASE-OUTPUT-1"); req.setOutcome("SUCCESS");
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertTrue(coordinator.isBlocked(10L)); assertEquals(1,tasks.selectById(10L).getStatus());
    }

    @Test void resolutionRejectsMissingRecordAndNonUnknown() throws Exception {
        var run=unknown(); var req=review(-1L);
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        req.setRecordId(run.getId());
        sql("UPDATE dh_dataforge_push_task_log SET run_status=3 WHERE id="+run.getId());
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertNull(runs.selectById(run.getId()).getResolutionRequestId());
    }

    @Test void lateWorkerCannotOverwriteResolvedOrAdvanceWatermark() throws Exception {
        var run=unknown(); coordinator.resolve(review(run.getId()),99L);
        run.setRunStatus(3); assertFalse(coordinator.finish(run,2,watermark(10L)));
        assertFalse(coordinator.progress(run,10,"late")); assertFalse(coordinator.heartbeat(run.getId(),run.getDispatchToken()));
        assertEquals(6,runs.selectById(run.getId()).getRunStatus()); assertNull(watermarks.selectNewest(10L));
    }

    @Test void concurrentReviewersOneDecisionAndIdempotentReplay() throws Exception {
        var req=review(unknown().getId()); var gate=new CountDownLatch(1); var second=node();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<DataPushTaskLogDO> a=()->{tenant(1); try {gate.await(); return coordinator.resolve(req,99L);} finally {TenantContextHolder.clear();}};
            Callable<DataPushTaskLogDO> b=()->{tenant(1); try {gate.await(); return second.resolve(req,99L);} finally {TenantContextHolder.clear();}};
            var one=pool.submit(a);var two=pool.submit(b);gate.countDown();
            assertEquals(one.get(5,TimeUnit.SECONDS).getResolvedAt(),two.get(5,TimeUnit.SECONDS).getResolvedAt());
        }
        assertEquals(1L,tasks.selectById(10L).getScheduleGeneration());
    }

    @Test void auditAndPauseRollbackTogetherWhenTaskUpdateFails() throws Exception {
        var run=unknown(); var broken=node(); var failing=mock(DataPushTaskMapper.class);
        when(failing.lockOwned(10L)).thenReturn(tasks.selectById(10L));
        when(failing.update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);
        ReflectionTestUtils.setField(broken,"tasks",failing);
        assertThrows(RuntimeException.class,()->broken.resolve(review(run.getId()),99L));
        assertEquals(5,runs.selectById(run.getId()).getRunStatus()); assertNull(runs.selectById(run.getId()).getResolvedAt());
        assertEquals(1,tasks.selectById(10L).getStatus());
    }

    @Test void unknownSiblingsRemainBlockedAndReviewCannotCancelAnotherActiveRun() throws Exception {
        var first=unknown(); sql("UPDATE dh_dataforge_push_task_log SET run_status=4 WHERE id="+first.getId());
        var second=claim(); sql("UPDATE dh_dataforge_push_task_log SET run_status=5 WHERE id="+first.getId());
        assertThrows(RuntimeException.class,()->coordinator.resolve(review(first.getId()),99L));
        coordinator.startSending(second); coordinator.cancel(10L); expireLease(second);
        var req=review(first.getId()); req.setOutcome("COMPENSATED"); coordinator.resolve(req,99L);
        assertTrue(coordinator.isBlocked(10L)); assertEquals(5,runs.selectById(second.getId()).getRunStatus());
        coordinator.resolve(review(second.getId()),99L); assertFalse(coordinator.isBlocked(10L));
    }

    @Test void resolutionRequestIsGloballyUniqueAcrossTasks() throws Exception {
        var req=review(unknown().getId()); coordinator.resolve(req,99L); seedTask(30,1);
        var other=coordinator.claim(30L,PushRunTypeEnum.HAND);
        sql("UPDATE dh_dataforge_push_task_log SET run_status=5 WHERE id="+other.getId());
        req.setRecordId(other.getId()); assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertEquals(5,runs.selectById(other.getId()).getRunStatus());
        tenant(2); var tenantRun=coordinator.claim(20L,PushRunTypeEnum.HAND);
        sql("UPDATE dh_dataforge_push_task_log SET run_status=5 WHERE id="+tenantRun.getId());
        req.setRecordId(tenantRun.getId()); assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertEquals(6,coordinator.resolve(review(tenantRun.getId()),99L).getRunStatus());
    }

    @Test void resolutionAndOriginalRunSurviveOrdinaryCleanupAndTaskDeletion() throws Exception {
        var resolved=coordinator.resolve(review(unknown().getId()),99L);
        var pending=claim(); assertNotNull(pending);
        runs.deleteOldByTaskId(10L,1); runs.deleteByTaskId(10L); tasks.deleteById(10L);
        assertEquals(6,runs.selectById(resolved.getId()).getRunStatus());
        assertEquals(99L,runs.selectById(resolved.getId()).getResolvedBy());
    }

    @Test void resolutionMigrationReplayPreservesUnknownAndDecision() throws Exception {
        var resolved=coordinator.resolve(review(unknown().getId()),99L);
        var pending=claim(); coordinator.startSending(pending); coordinator.cancel(10L);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        assertEquals(6,runs.selectById(resolved.getId()).getRunStatus());
        assertEquals(resolved.getResolvedAt(),runs.selectById(resolved.getId()).getResolvedAt());
        assertEquals(5,runs.selectById(pending.getId()).getRunStatus());
        String index=indexDefinition("uk_dataforge_push_resolution_request");
        sql("DROP INDEX uk_dataforge_push_resolution_request");
        var ddl=Pattern.compile("CREATE UNIQUE INDEX IF NOT EXISTS uk_dataforge_push_resolution_request.*?;",Pattern.DOTALL)
                .matcher(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));
        assertTrue(ddl.find()); sql(ddl.group().replace("\"datahub-center\".","").replace("tenant_id, ",""));
        assertEquals(index,indexDefinition("uk_dataforge_push_resolution_request"));
    }

    @Test void resolutionCommitAckLostCanReplayWithoutSecondMutation() throws Exception {
        var req=review(unknown().getId()); var uncertain=node();
        var lostAck=new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition d) { return manager.getTransaction(d); }
            public void commit(org.springframework.transaction.TransactionStatus s) { manager.commit(s); throw new org.springframework.transaction.TransactionSystemException("fixture: lost ack"); }
            public void rollback(org.springframework.transaction.TransactionStatus s) { manager.rollback(s); }
        };
        ReflectionTestUtils.setField(uncertain,"transactionManager",lostAck);
        assertThrows(RuntimeException.class,()->uncertain.resolve(req,99L));
        assertEquals(6,coordinator.resolve(req,99L).getRunStatus());
        assertEquals(1L,tasks.selectById(10L).getScheduleGeneration());
    }

    private void seedReviewMenu() throws Exception {
        sql("CREATE SEQUENCE system_menu_seq");
        sql("CREATE TABLE system_menu(id bigint PRIMARY KEY DEFAULT nextval('system_menu_seq'),name text,permission text,type int,sort int,parent_id bigint,path text,icon text,component text,component_name text,status int,visible boolean,keep_alive boolean,always_show boolean,creator text,updater text,create_time timestamp,update_time timestamp,deleted int DEFAULT 0)");
        sql("INSERT INTO system_menu(id,type,status,deleted) VALUES (1,2,0,0)");
        sql("INSERT INTO system_menu(id,type,parent_id,status,deleted,permission) VALUES (2,3,1,0,0,'dataforge:data-push:run')");
        sql("CREATE TABLE system_role_menu(role_id bigint,menu_id bigint)");
        sql("INSERT INTO system_role_menu VALUES (1,2)");
    }
    private void migrateReviewMenu() throws Exception {
        sql(cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve("sql/postgresql/migrations/0057_dataforge_push-resolution-permission.sql")).replace("\"datahub-center\"",schema));
    }
    private int scalar(String query) throws Exception {
        try(var st=control.createStatement();var rs=st.executeQuery(query)) { assertTrue(rs.next());return rs.getInt(1); }
    }

    @Test void permissionMigrationReplayDoesNotGrantExistingRolesAndMatchesBaseline() throws Exception {
        assumeHistoricalMigration("0057");
        seedReviewMenu(); migrateReviewMenu(); migrateReviewMenu();
        assertEquals(1,scalar("SELECT count(*) FROM system_menu WHERE permission='dataforge:data-push:resolve' AND parent_id=1 AND type=3 AND status=0 AND deleted=0"));
        assertEquals(1,scalar("SELECT count(*) FROM system_role_menu")); // 未自动增加角色授权
        String baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        var block=Pattern.compile("DO \\$push_resolution_permission\\$.*?\\$push_resolution_permission\\$;",Pattern.DOTALL).matcher(baseline);
        assertTrue(block.find()); sql(block.group()); assertEquals(3,scalar("SELECT count(*) FROM system_menu"));
    }

    @Test void permissionMigrationRejectsMissingParentAndDisabledExistingPermission() throws Exception {
        assumeHistoricalMigration("0057");
        seedReviewMenu(); sql("UPDATE system_menu SET deleted=1 WHERE id=1");
        assertThrows(SQLException.class,this::migrateReviewMenu); sql("ROLLBACK");
        assertEquals(0,scalar("SELECT count(*) FROM system_menu WHERE permission='dataforge:data-push:resolve'"));
        sql("UPDATE system_menu SET deleted=0 WHERE id=1"); migrateReviewMenu();
        sql("UPDATE system_menu SET status=1 WHERE permission='dataforge:data-push:resolve'");
        assertThrows(SQLException.class,this::migrateReviewMenu); sql("ROLLBACK");
        assertEquals(1,scalar("SELECT status FROM system_menu WHERE permission='dataforge:data-push:resolve'"));
    }

    @Test void oldSchemaUpgradeAddsAllAuditColumnsWithoutRewritingRuns() throws Exception {
        assumeHistoricalMigration("0056");
        var run=unknown(); String signature=resolutionSchemaSignature();
        for(String name:List.of("resolution_request_id","resolution_outcome","resolution_reason","stopped_evidence_ref","output_evidence_ref","resolved_by","resolved_at"))
            sql("ALTER TABLE dh_dataforge_push_task_log DROP COLUMN "+name+" CASCADE");
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        assertEquals(signature,resolutionSchemaSignature()); assertEquals(5,runs.selectById(run.getId()).getRunStatus());
        assertNull(runs.selectById(run.getId()).getResolutionRequestId());
    }
    private String resolutionSchemaSignature() throws Exception {
        try(var st=control.createStatement();var rs=st.executeQuery("SELECT column_name,data_type,is_nullable,character_maximum_length FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='dh_dataforge_push_task_log' AND (column_name LIKE 'resolution_%' OR column_name LIKE 'resolved_%' OR column_name IN ('stopped_evidence_ref','output_evidence_ref')) ORDER BY column_name")) {
            var out=new StringBuilder();while(rs.next()) for(int i=1;i<=4;i++) out.append(rs.getString(i)).append('|');return out.toString();
        }
    }
    private void expireLease(DataPushTaskLogDO run) throws Exception {
        sql("UPDATE dh_dataforge_push_task_log SET dispatch_lease_until=clock_timestamp()-interval '1 second' WHERE id=" + run.getId());
    }
    private DataPushIncreaseLogDO watermark(long task) {
        return DataPushIncreaseLogDO.builder().pushTaskId(task).increaseColumn("id").startVal("0").endVal("2").build();
    }

    @Test void twoNodesOnlyClaimOnce() throws Exception {
        var second = node(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<DataPushTaskLogDO> a = () -> { tenant(1); try { assertTrue(gate.await(5, TimeUnit.SECONDS)); return claim(); }
                finally { TenantContextHolder.clear(); } };
            Callable<DataPushTaskLogDO> b = () -> { tenant(1); try { assertTrue(gate.await(5, TimeUnit.SECONDS)); return second.claim(10L, PushRunTypeEnum.AUTO); }
                finally { TenantContextHolder.clear(); } };
            var first = pool.submit(a); var other = pool.submit(b); gate.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) == null ? 0 : 1) + (other.get(5, TimeUnit.SECONDS) == null ? 0 : 1));
        }
        assertEquals(1, runs.countUnresolved(10L));
    }

    @Test void claimIsCommittedEvenIfOuterTransactionRollsBack() {
        new TransactionTemplate(manager).executeWithoutResult(status -> { assertNotNull(claim()); status.setRollbackOnly(); });
        assertTrue(node().isBlocked(10L)); assertNull(claim());
    }

    @Test void unknownSurvivesNewNodeAndBlocksBothTriggersAndIdleMutation() {
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(5);
        assertTrue(coordinator.finish(run, 2, null));
        assertNull(node().claim(10L, PushRunTypeEnum.AUTO)); assertNull(node().claim(10L, PushRunTypeEnum.HAND));
        assertThrows(RuntimeException.class, () -> node().withIdleTask(10L, task -> { fail("未知时不得进入修改"); return null; }));
        assertNull(watermarks.selectNewest(10L));
    }

    @Test void cancelledBeforeSendCannotStartAndCanBeClaimedAgain() {
        var run = claim(); assertTrue(node().cancel(10L));
        assertEquals(4, runs.selectById(run.getId()).getRunStatus());
        assertFalse(coordinator.startSending(run)); assertNotNull(claim());
    }

    @Test void cancelledAfterSendCannotBeOverwrittenByLateSuccess() {
        var run = claim(); assertTrue(coordinator.startSending(run)); assertTrue(node().cancel(10L));
        run.setRunStatus(3);
        assertFalse(coordinator.finish(run, 2, watermark(10))); assertNull(watermarks.selectNewest(10L));
        assertEquals(5, runs.selectById(run.getId()).getRunStatus()); assertNull(claim());
    }

    @Test void stalePreparationIsReclaimedButStaleSendingHoldsUnknown() throws Exception {
        var first = claim(); expireLease(first);
        assertEquals(0, runs.recoverStaleById(first, 10));
        assertFalse(coordinator.startSending(first));
        var second = node().acquireDispatch(first);
        assertEquals(first.getId(), second.getId()); assertNotEquals(first.getDispatchToken(), second.getDispatchToken());
        assertTrue(coordinator.startSending(second)); second.setRunStatus(2); expireLease(second);
        assertEquals(1, runs.recoverStaleById(second, 10)); assertEquals(5, runs.selectById(second.getId()).getRunStatus());
        assertNull(claim()); assertEquals(0, runs.touchHeartbeat(second.getId(), second.getDispatchToken(), 600));
    }

    @Test void refreshedHeartbeatInvalidatesStaleCandidate() throws Exception {
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(2); expireLease(run);
        assertEquals(1, runs.selectStaleRunning(10).size());
        // 模拟扫描后租约在数据库中已更新，恢复必须读取当前期限而非候选快照。
        sql("UPDATE dh_dataforge_push_task_log SET dispatch_lease_until=clock_timestamp()+interval '10 minutes' WHERE id=" + run.getId());
        assertEquals(1, runs.touchHeartbeat(run.getId(), run.getDispatchToken(), 600));
        assertEquals(0, runs.recoverStaleById(run, 10)); assertEquals(2, runs.selectById(run.getId()).getRunStatus());
    }

    @Test void changedPhaseInvalidatesStaleCandidateEvenWhenHeartbeatOld() throws Exception {
        var run = claim(); assertTrue(coordinator.startSending(run)); expireLease(run);
        assertEquals(0, runs.recoverStaleById(run, 10)); assertEquals(2, runs.selectById(run.getId()).getRunStatus());
    }

    @Test void independentTaskLocksAndWatermarksRetainLeaseFencing() {
        var run = claim();
        var own = coordinator.acquireDispatch(coordinator.claim(20L, PushRunTypeEnum.HAND));
        assertTrue(coordinator.isBlocked(10L));
        assertEquals(0, runs.touchHeartbeat(run.getId(), "stale-token", 600));
        assertTrue(coordinator.startSending(own));
        own.setRunStatus(3); assertTrue(coordinator.finish(own, 2, watermark(20)));
        assertNotNull(watermarks.selectNewest(20L));
        assertNull(watermarks.selectNewest(10L)); assertTrue(coordinator.isBlocked(10L));
    }

    @Test void admissionDoesNotRequireTenantContext() {
        TenantContextHolder.clear(); assertNotNull(claim());
        assertNull(claim());
    }

    @Test void successAndWatermarkCommitTogetherAndRelease() {
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(3); run.setDataCount(2L);
        assertTrue(coordinator.finish(run, 2, watermark(10)));
        assertEquals("2", watermarks.selectNewest(10L).getEndVal()); assertEquals(3, runs.selectById(run.getId()).getRunStatus());
        assertNotNull(claim());
    }

    @Test void watermarkFailureRollsBackSuccessAndKeepsOccupied() throws Exception {
        sql("CREATE FUNCTION reject_watermark() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_watermark BEFORE INSERT ON dh_dataforge_push_increase_log FOR EACH ROW EXECUTE FUNCTION reject_watermark()");
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(3);
        assertThrows(RuntimeException.class, () -> coordinator.finish(run, 2, watermark(10)));
        assertEquals(2, runs.selectById(run.getId()).getRunStatus()); assertNull(watermarks.selectNewest(10L)); assertNull(claim());
    }

    @Test void postSendOrdinaryFailureAndUnknownWatermarkAreRejected() {
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(4);
        assertThrows(IllegalArgumentException.class, () -> coordinator.finish(run, 2, null));
        run.setRunStatus(5); assertThrows(IllegalArgumentException.class, () -> coordinator.finish(run, 2, watermark(10)));
    }

    @Test void cleanupNeverDeletesUnknownEvenIfNotLatest() throws Exception {
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(5); assertTrue(coordinator.finish(run, 2, null));
        sql("INSERT INTO dh_dataforge_push_task_log(id,push_task_id,run_status,run_type) VALUES (" + (run.getId()+1) + ",10,3,1)");
        runs.deleteOldByTaskId(10L, 1); assertNotNull(runs.selectById(run.getId()));
        runs.deleteByTaskId(10L); assertNotNull(runs.selectById(run.getId())); assertTrue(coordinator.isBlocked(10L));
        assertEquals(5, coordinator.blockingState(10L));
    }

    @Test void waitingAndUnknownAreExcludedFromScheduledCandidates() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET task_type=2,next_run_time=clock_timestamp()-interval '1 minute' WHERE id=10");
        assertEquals(1, tasks.selectDueList(runs.databaseNow()).size());
        var run = claim(); assertTrue(tasks.selectDueList(runs.databaseNow()).isEmpty());
        assertTrue(coordinator.startSending(run)); run.setRunStatus(5); assertTrue(coordinator.finish(run, 2, null));
        assertTrue(tasks.selectDueList(runs.databaseNow()).isEmpty());
    }

    @Test void schedulerCrashAtQueueBoundaryNeverAdvancesWithoutDurableRun() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET task_type=2,cron='0 * * * * *', "
                + "next_run_time=clock_timestamp()-interval '2 minutes' WHERE id=10");
        var due = tasks.selectById(10L).getNextRunTime();
        var job = new cn.iocoder.yudao.module.dataforge.job.push.DataPushSyncJob();
        var engine = mock(DataPushExecutor.class, invocation -> {
            if (Set.of("submit", "dispatch").contains(invocation.getMethod().getName())) {
                throw new AssertionError("fixture: process lost before queue acceptance");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        ReflectionTestUtils.setField(job, "taskMapper", tasks);
        ReflectionTestUtils.setField(job, "pushExecutor", engine);
        // 同一反例兼容修复前入口；修复后通过真实协调器受理。
        if (org.springframework.util.ReflectionUtils.findField(job.getClass(), "runCoordinator") != null) {
            ReflectionTestUtils.setField(job, "runCoordinator", coordinator);
            ReflectionTestUtils.setField(job, "logMapper", runs);
        }
        assertThrows(AssertionError.class, job::execute);
        assertTrue(Objects.equals(due, tasks.selectById(10L).getNextRunTime()) || runs.countUnresolved(10L) == 1,
                "排期已推进却没有持久执行记录：提交队列前崩溃会漏执行");
    }

    @Test void actualTwoExecutorsDoNotResendUnknownAcrossWorkerThreads() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET push_mode=1 WHERE id=10");
        var channel = mock(DbPushChannel.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        when(channel.syncFromSource(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); throw new UnconfirmedWriteException();
        });
        var first = executor(channel, finished); var second = executor(channel, new CountDownLatch(1));
        try {
            assertTrue(first.submit(10L, PushRunTypeEnum.HAND)); assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(second.submit(10L, PushRunTypeEnum.AUTO)); release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS)); assertEquals(5, coordinator.blockingState(10L));
            assertFalse(second.submit(10L, PushRunTypeEnum.HAND));
            verify(channel, times(1)).syncFromSource(any(), any(), any(), any(), any(), any(), any());
        } finally { release.countDown(); first.destroy(); second.destroy(); }
    }

    @Test void queueRejectionKeepsSameDurableRunAndBlocksDuplicateSubmission() {
        var engine = executor(mock(DbPushChannel.class), new CountDownLatch(1)); engine.destroy();
        assertTrue(engine.submit(10L, PushRunTypeEnum.HAND));
        assertTrue(coordinator.isBlocked(10L));
        var pending = runs.selectLatestByTaskIds(List.of(10L)).getFirst();
        assertEquals(1, pending.getRunStatus()); assertNull(pending.getDispatchToken());
        assertTrue(pending.getNextDispatchAt().isAfter(runs.databaseNow()));
        assertFalse(engine.submit(10L, PushRunTypeEnum.HAND));
        assertEquals(1, runs.selectCount(null));
    }

    @Test void serviceDeleteAndResetCannotBypassUnknownAndResetWritesAreAtomic() throws Exception {
        var service = new DataPushTaskServiceImpl();
        ReflectionTestUtils.setField(service, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "taskMapper", tasks); ReflectionTestUtils.setField(service, "logMapper", runs);
        ReflectionTestUtils.setField(service, "increaseLogMapper", watermarks);
        var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(5); assertTrue(coordinator.finish(run, 2, null));
        assertThrows(RuntimeException.class, () -> service.deleteTask(10L));
        assertThrows(RuntimeException.class, () -> service.resetWatermark(10L)); assertNotNull(tasks.selectById(10L));
        tenant(2); watermarks.upsert(watermark(20));
        sql("CREATE FUNCTION reject_task_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_task_update BEFORE UPDATE ON dh_dataforge_push_task FOR EACH ROW EXECUTE FUNCTION reject_task_update()");
        assertThrows(RuntimeException.class, () -> service.resetWatermark(20L));
        assertEquals("2", watermarks.selectNewest(20L).getEndVal());
    }

    private DataPushExecutor executor(DbPushChannel channel, CountDownLatch finished) {
        var result = new DataPushExecutor();
        var configs = mock(DataPushConfigMapper.class); var config = new DataPushConfigDO();
        config.setChannelType(PushChannelTypeEnum.DB.getType()); when(configs.selectById(1L)).thenReturn(config);
        var registry = mock(DataPushChannelRegistry.class); when(registry.get(any())).thenReturn(channel);
        var resolver = mock(AccessDatabaseResolver.class);
        var source = new AccessDatabaseResolver.ConnectionInfo();
        source.setDatabaseId(1L); source.setSchema(schema);
        source.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(any(), any())).thenReturn(source);
        var ops = mock(DataPushOpsSupport.class); doAnswer(call -> { finished.countDown(); return null; }).when(ops).afterFinished(any(), any());
        ReflectionTestUtils.setField(result, "taskMapper", tasks); ReflectionTestUtils.setField(result, "logMapper", runs);
        ReflectionTestUtils.setField(result, "increaseLogMapper", watermarks); ReflectionTestUtils.setField(result, "runCoordinator", node());
        ReflectionTestUtils.setField(result, "configMapper", configs); ReflectionTestUtils.setField(result, "channelRegistry", registry);
        ReflectionTestUtils.setField(result, "dbPushChannel", channel); ReflectionTestUtils.setField(result, "databaseResolver", resolver);
        var access = mock(ExecutionAccessService.class);
        doAnswer(call -> {
            assertEquals(99L, call.getArgument(0, Long.class));
            assertEquals("dataforge:data-push:run", call.getArgument(1, String.class));
            return null;
        }).when(access).requireUserPermission(any(), any());
        ReflectionTestUtils.setField(result, "executionAccessService", access);
        var queries = mock(DatabaseQuerySupport.class);
        when(queries.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> {
            assertEquals(1L, call.getArgument(0, Long.class));
            assertEquals("SELECT * FROM \"" + schema + "\".\"orders\"", call.getArgument(1, String.class));
            return call.getArgument(1);
        });
        ReflectionTestUtils.setField(result, "databaseQuerySupport", queries);
        ReflectionTestUtils.setField(result, "opsSupport", ops);
        result.init(); return result;
    }

    @Test void cancelPublicationAndExecutionStateCommitTogether() {
        var run = claim(); assertTrue(coordinator.startSending(run)); coordinator.cancelPublication(10L);
        assertEquals(0, tasks.selectById(10L).getStatus()); assertEquals(5, runs.selectById(run.getId()).getRunStatus());
        assertNull(coordinator.claim(10L, PushRunTypeEnum.AUTO)); assertNull(claim());
    }

    @Test void migrationConservativelyConvertsOnlySelectedHistoryAndReplays() throws Exception {
        assumeHistoricalMigration("0054");
        sql("INSERT INTO dh_dataforge_push_task_log(id,push_task_id,run_status,run_type,error_info,deleted) VALUES (1,10,1,1,NULL,0),(2,10,2,1,NULL,0),(3,10,3,1,NULL,0),(4,10,4,1,'plain',0),(5,10,4,1,'UnconfirmedWriteException',0),(6,20,2,1,NULL,0),(7,10,2,1,NULL,1)");
        migrate(); migrate();
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT run_status FROM dh_dataforge_push_task_log ORDER BY id")) {
            for (int expected : new int[]{5,5,3,4,5,5,2}) { assertTrue(result.next()); assertEquals(expected, result.getInt(1)); }
            assertFalse(result.next());
        }
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='" + schema + "' AND indexname='idx_dataforge_push_unresolved'")) {
            assertTrue(result.next()); assertEquals(1, result.getInt(1));
        }
    }

    private DataPushTaskDO dueTask() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET task_type=2,cron='0 * * * * *', "
                + "next_run_time=date_trunc('minute',clock_timestamp())-interval '5 minutes' WHERE id=10");
        return tasks.selectById(10L);
    }
    private DataPushTaskLogDO acceptDue(DataPushTaskDO due) {
        return coordinator.enqueueScheduled(due.getId(), due.getScheduleGeneration(), due.getNextRunTime());
    }

    @Test void scheduledAcceptanceAndCursorRollbackTogetherWhenCursorWriteFails() throws Exception {
        var due = dueTask();
        sql("CREATE FUNCTION reject_schedule() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_schedule BEFORE UPDATE ON dh_dataforge_push_task FOR EACH ROW EXECUTE FUNCTION reject_schedule()");
        assertThrows(RuntimeException.class, () -> acceptDue(due));
        assertEquals(due.getNextRunTime(), tasks.selectById(10L).getNextRunTime()); assertEquals(0, runs.selectCount(null));
    }

    @Test void twoSchedulersAcceptSameSlotOnlyOnceAndDoNotSkipBacklog() throws Exception {
        var due = dueTask(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<DataPushTaskLogDO> call = () -> { tenant(1); try {
                assertTrue(gate.await(5, TimeUnit.SECONDS));
                return node().enqueueScheduled(due.getId(), due.getScheduleGeneration(), due.getNextRunTime());
            } finally { TenantContextHolder.clear(); } };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            assertEquals(1, (a.get(5, TimeUnit.SECONDS) == null ? 0 : 1) + (b.get(5, TimeUnit.SECONDS) == null ? 0 : 1));
        }
        assertEquals(due.getNextRunTime().plusMinutes(1), tasks.selectById(10L).getNextRunTime());
        var accepted = runs.selectLatestByTaskIds(List.of(10L)).getFirst();
        assertEquals(due.getNextRunTime(), accepted.getScheduledTime()); assertNull(accepted.getDispatchToken());
        assertNull(acceptDue(due)); // 模拟受理提交回执丢失后的旧槽重试。
        var lease = coordinator.acquireDispatch(accepted); assertTrue(coordinator.startSending(lease)); lease.setRunStatus(3);
        assertTrue(coordinator.finish(lease, 2, null));
        assertNotNull(acceptDue(tasks.selectById(10L)));
        assertEquals(due.getNextRunTime().plusMinutes(2), tasks.selectById(10L).getNextRunTime());
    }

    @Test void futureDueOrUnknownDoesNotConsumeSlot() throws Exception {
        var due = dueTask(); var run = claim(); assertTrue(coordinator.startSending(run)); run.setRunStatus(5);
        assertTrue(coordinator.finish(run, 2, null)); assertNull(acceptDue(due));
        assertEquals(due.getNextRunTime(), tasks.selectById(10L).getNextRunTime());
        tenant(2); sql("UPDATE dh_dataforge_push_task SET task_type=2,cron='0 * * * * *',next_run_time=clock_timestamp()+interval '1 day' WHERE id=20");
        assertNull(acceptDue(tasks.selectById(20L))); assertEquals(1, runs.selectCount(null));
    }

    @Test void invalidCronKeepsSlotAndNoIntentInsteadOfSilentlyClearingIt() throws Exception {
        var due = dueTask(); sql("UPDATE dh_dataforge_push_task SET cron='invalid' WHERE id=10");
        assertThrows(RuntimeException.class, () -> acceptDue(due));
        assertEquals(due.getNextRunTime(), tasks.selectById(10L).getNextRunTime()); assertEquals(0, runs.selectCount(null));
    }

    @Test void oldGenerationCannotEnqueueAfterCancelAndRepublishEvenIfDueMatches() throws Exception {
        var due = dueTask(); coordinator.cancelPublication(10L);
        assertNull(coordinator.publish(tasks.selectById(10L), 99L));
        sql("UPDATE dh_dataforge_push_task SET next_run_time='" + due.getNextRunTime() + "' WHERE id=10");
        assertNull(acceptDue(due)); assertEquals(0, runs.selectCount(null));
        assertEquals(2L, tasks.selectById(10L).getScheduleGeneration());
        assertNotNull(acceptDue(tasks.selectById(10L)));
    }

    @Test void oneOffPublicationAndIntentCommitTogetherWithoutAnExecutor() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET status=0 WHERE id=10");
        var run = coordinator.publish(tasks.selectById(10L), 99L);
        assertNotNull(run); assertEquals(1, tasks.selectById(10L).getStatus()); assertEquals(1L, tasks.selectById(10L).getScheduleGeneration());
        assertEquals(1, runs.selectDispatchCandidates().size()); assertNotNull(node().acquireDispatch(run));
        assertThrows(RuntimeException.class, () -> coordinator.publish(tasks.selectById(10L), 99L));
        assertEquals(1, runs.selectCount(null));
    }

    @Test void oneOffPublicationRollsBackWhenIntentInsertFails() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET status=0 WHERE id=10");
        sql("CREATE FUNCTION reject_run() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_run BEFORE INSERT ON dh_dataforge_push_task_log FOR EACH ROW EXECUTE FUNCTION reject_run()");
        assertThrows(RuntimeException.class, () -> coordinator.publish(tasks.selectById(10L), 99L));
        assertEquals(0, tasks.selectById(10L).getStatus()); assertEquals(0L, tasks.selectById(10L).getScheduleGeneration());
        assertNull(tasks.selectById(10L).getReleaseTime());
    }

    @Test void expiredOldTokenCannotStartHeartbeatProgressOrFinishNewLease() throws Exception {
        var old = claim(); expireLease(old); var fresh = node().acquireDispatch(old); assertNotNull(fresh);
        assertFalse(coordinator.startSending(old));
        assertFalse(coordinator.heartbeat(old.getId(), old.getDispatchToken()));
        old.setRunStatus(4); assertFalse(coordinator.finish(old, 1, null));
        coordinator.deferDispatch(old); assertEquals(fresh.getDispatchToken(), runs.selectById(old.getId()).getDispatchToken());
        assertTrue(coordinator.startSending(fresh));
        assertFalse(coordinator.progress(old, 1, "late")); old.setRunStatus(3);
        assertFalse(coordinator.finish(old, 2, watermark(10)));
        assertEquals(2, runs.selectById(old.getId()).getRunStatus()); assertNull(watermarks.selectNewest(10L));
    }

    @Test void twoDispatchersAcquireOneIntentOnlyOnce() throws Exception {
        var accepted = coordinator.claim(10L, PushRunTypeEnum.HAND); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<DataPushTaskLogDO> call = () -> { tenant(1); try {
                assertTrue(gate.await(5, TimeUnit.SECONDS)); return node().acquireDispatch(accepted);
            } finally { TenantContextHolder.clear(); } };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            assertEquals(1, (a.get(5, TimeUnit.SECONDS) == null ? 0 : 1) + (b.get(5, TimeUnit.SECONDS) == null ? 0 : 1));
        }
        assertEquals(1, runs.selectById(accepted.getId()).getDispatchAttempts());
    }

    @Test void attemptsExhaustionLeavesExplicitPreSendFailureAndNeverAcquiresSending() throws Exception {
        var accepted = coordinator.claim(10L, PushRunTypeEnum.HAND);
        for (int i=1; i<=5; i++) {
            var lease = node().acquireDispatch(accepted); assertNotNull(lease); assertEquals(i, lease.getDispatchAttempts());
            expireLease(lease);
        }
        assertNull(coordinator.acquireDispatch(accepted)); assertEquals(4, runs.selectById(accepted.getId()).getRunStatus());
        var active = claim(); assertTrue(coordinator.startSending(active)); expireLease(active);
        assertNull(node().acquireDispatch(active)); assertEquals(2, runs.selectById(active.getId()).getRunStatus());
        active.setRunStatus(2); assertEquals(1, runs.recoverStaleById(active, 0)); // 不受旧回收开关影响。
        assertEquals(5, runs.selectById(active.getId()).getRunStatus());
    }

    @Test void deferredQueueIsRecoveredByActualSecondExecutorWithoutCreatingAnotherRun() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET push_mode=1 WHERE id=10");
        var channel = mock(DbPushChannel.class); when(channel.syncFromSource(any(),any(),any(),any(),any(),any(),any())).thenReturn(2L);
        var stopped = executor(channel, new CountDownLatch(1)); stopped.destroy();
        assertTrue(stopped.submit(10L, PushRunTypeEnum.HAND));
        var accepted = runs.selectLatestByTaskIds(List.of(10L)).getFirst();
        sql("UPDATE dh_dataforge_push_task_log SET next_dispatch_at=clock_timestamp()-interval '1 second' WHERE id=" + accepted.getId());
        var finished = new CountDownLatch(1); var restarted = executor(channel, finished);
        try {
            assertTrue(restarted.dispatch(runs.selectDispatchCandidates().getFirst()));
            assertTrue(finished.await(10,TimeUnit.SECONDS)); assertEquals(1, runs.selectCount(null));
            assertEquals(3,runs.selectById(accepted.getId()).getRunStatus());
            assertNull(runs.selectById(accepted.getId()).getErrorInfo(),"恢复成功不能继续显示先前队列拒绝原因");
            verify(channel,times(1)).syncFromSource(any(),any(),any(),any(),any(),any(),any());
        } finally { restarted.destroy(); }
    }

    @Test void migrationDispatchQuarantinesLegacyOnlyAndReplaysWithoutTouchingNewPending() throws Exception {
        assumeHistoricalMigration("0055");
        sql("INSERT INTO dh_dataforge_push_task_log(id,push_task_id,run_status,run_type) VALUES (1,20,1,2),(2,20,2,2),(3,20,4,2)");
        var run = coordinator.claim(10L, PushRunTypeEnum.HAND);
        migrateDispatch(); migrateDispatch();
        assertEquals(1,runs.selectById(run.getId()).getRunStatus()); assertNotNull(coordinator.acquireDispatch(run));
        tenant(2); assertEquals(5,runs.selectById(1L).getRunStatus()); assertEquals(5,runs.selectById(2L).getRunStatus());
        assertEquals(4,runs.selectById(3L).getRunStatus()); assertTrue(runs.selectDispatchCandidates().isEmpty());
    }

    @Test void commitAcknowledgementLossRetainsOneRecoverableScheduledIntent() throws Exception {
        var due = dueTask(); var uncertain = node();
        var lostAck = new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) {
                manager.commit(status);
                throw new org.springframework.transaction.TransactionSystemException("fixture: commit response lost");
            }
            public void rollback(org.springframework.transaction.TransactionStatus status) { manager.rollback(status); }
        };
        ReflectionTestUtils.setField(uncertain,"transactionManager",lostAck);
        assertThrows(RuntimeException.class, () -> uncertain.enqueueScheduled(due.getId(),due.getScheduleGeneration(),due.getNextRunTime()));
        assertEquals(due.getNextRunTime().plusMinutes(1),tasks.selectById(10L).getNextRunTime());
        assertNull(acceptDue(due)); assertEquals(1,runs.selectCount(null));
        assertNotNull(node().acquireDispatch(runs.selectDispatchCandidates().getFirst()));
    }

    @Test void expiredLeaseCannotBeRevivedOrFinalizedBeforeTakeover() throws Exception {
        var lease = claim(); expireLease(lease);
        assertFalse(coordinator.heartbeat(lease.getId(),lease.getDispatchToken()));
        assertFalse(coordinator.startSending(lease));
        lease.setRunStatus(3); assertFalse(coordinator.finish(lease,1,null));
        assertEquals(1,runs.selectById(lease.getId()).getRunStatus());
    }

    @Test void actualJobReportsPartialFailureWithoutSkippingOtherTasks() throws Exception {
        dueTask(); sql("UPDATE dh_dataforge_push_task SET cron='invalid' WHERE id=10");
        sql("UPDATE dh_dataforge_push_task SET task_type=2,cron='0 * * * * *',next_run_time=clock_timestamp()-interval '1 minute' WHERE id=20");
        var engine=mock(DataPushExecutor.class);
        when(engine.dispatch(any())).thenAnswer(call -> {
            DataPushTaskLogDO run=call.getArgument(0);
            assertEquals(20L,run.getPushTaskId()); return false;
        });
        var job=new cn.iocoder.yudao.module.dataforge.job.push.DataPushSyncJob();
        ReflectionTestUtils.setField(job,"taskMapper",tasks); ReflectionTestUtils.setField(job,"logMapper",runs);
        ReflectionTestUtils.setField(job,"runCoordinator",coordinator); ReflectionTestUtils.setField(job,"pushExecutor",engine);
        TenantContextHolder.setIgnore(true);
        try (var helper=mockStatic(com.xxl.job.core.context.XxlJobHelper.class)) {
            job.execute(); helper.verify(() -> com.xxl.job.core.context.XxlJobHelper.handleFail(anyString()));
            helper.verify(() -> com.xxl.job.core.context.XxlJobHelper.handleSuccess(anyString()),never());
        }
        assertEquals(1,runs.selectCount(null)); verify(engine,times(1)).dispatch(any());
        assertNotNull(tasks.selectById(10L).getNextRunTime());
    }

    @Test void actualJobRecoversManualPendingForUnreleasedTaskAfterNodeLoss() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET status=0,push_mode=1 WHERE id=10");
        var accepted=coordinator.claim(10L,PushRunTypeEnum.HAND);
        var channel=mock(DbPushChannel.class); when(channel.syncFromSource(any(),any(),any(),any(),any(),any(),any())).thenReturn(1L);
        var finished=new CountDownLatch(1); var engine=executor(channel,finished);
        var job=new cn.iocoder.yudao.module.dataforge.job.push.DataPushSyncJob();
        ReflectionTestUtils.setField(job,"taskMapper",tasks); ReflectionTestUtils.setField(job,"logMapper",runs);
        ReflectionTestUtils.setField(job,"runCoordinator",coordinator); ReflectionTestUtils.setField(job,"pushExecutor",engine);
        try { job.execute(); assertTrue(finished.await(10,TimeUnit.SECONDS));
            assertEquals(3,runs.selectById(accepted.getId()).getRunStatus()); assertEquals(1,runs.selectCount(null));
        } finally { engine.destroy(); }
    }

    @Test void serviceOneOffReleaseDoesNotLoseIntentWhenImmediateDispatchFails() throws Exception {
        sql("UPDATE dh_dataforge_push_task SET status=0 WHERE id=10");
        var service=new DataPushTaskServiceImpl(); var configs=mock(DataPushConfigMapper.class);
        when(configs.selectById(1L)).thenReturn(new DataPushConfigDO()); var engine=mock(DataPushExecutor.class);
        when(engine.dispatch(any())).thenThrow(new IllegalStateException("fixture"));
        ReflectionTestUtils.setField(service,"taskMapper",tasks); ReflectionTestUtils.setField(service,"configMapper",configs);
        ReflectionTestUtils.setField(service,"runCoordinator",coordinator); ReflectionTestUtils.setField(service,"pushExecutor",engine);
        assertDoesNotThrow(() -> service.releaseTask(10L));
        assertEquals(1,tasks.selectById(10L).getStatus()); assertEquals(1,runs.selectDispatchCandidates().size());
        verify(engine,never()).submit(any(),any());
    }

    @Test void legacyUpgradeAndFreshSliceReachSameColumnAndIndexDefinitions() throws Exception {
        assumeHistoricalMigration("0055");
        List<String> columns=List.of("dispatch_protocol","dispatch_token","dispatch_lease_until","dispatch_attempts","next_dispatch_at","scheduled_time");
        String before=dispatchSchemaSignature();
        for (String column:columns) sql("ALTER TABLE dh_dataforge_push_task_log DROP COLUMN " + column + " CASCADE");
        sql("ALTER TABLE dh_dataforge_push_task DROP COLUMN schedule_generation");
        migrateDispatch(); migrateDispatch();
        assertEquals(before,dispatchSchemaSignature());
        String baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        for (String index:List.of("idx_dataforge_push_dispatch_pending","idx_dataforge_push_due")) {
            String migrated=indexDefinition(index);
            sql("DROP INDEX " + index);
            var ddl=Pattern.compile("CREATE INDEX IF NOT EXISTS " + index + ".*?;",Pattern.DOTALL).matcher(baseline);
            assertTrue(ddl.find()); sql(ddl.group().replace("\"datahub-center\".","").replace("tenant_id, ",""));
            assertEquals(migrated,indexDefinition(index));
        }
    }

    private String dispatchSchemaSignature() throws Exception {
        try(var st=control.createStatement();var rs=st.executeQuery("SELECT table_name,column_name,data_type,is_nullable,column_default "
                + "FROM information_schema.columns WHERE table_schema='"+schema+"' AND (column_name LIKE 'dispatch_%' "
                + "OR column_name IN ('next_dispatch_at','scheduled_time','schedule_generation')) ORDER BY table_name,column_name")) {
            var out=new StringBuilder(); while(rs.next()) for(int i=1;i<=5;i++) out.append(rs.getString(i)).append('|'); return out.toString();
        }
    }
    private String indexDefinition(String index) throws Exception {
        try(var st=control.createStatement();var rs=st.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname='"+schema+"' AND indexname='"+index+"'")) {
            assertTrue(rs.next()); return rs.getString(1);
        }
    }
}
