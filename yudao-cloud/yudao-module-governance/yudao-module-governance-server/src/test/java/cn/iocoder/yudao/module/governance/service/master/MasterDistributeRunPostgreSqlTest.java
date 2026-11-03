package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.governance.enums.master.MasterRunTypeEnum;
import cn.iocoder.yudao.module.governance.job.master.MasterDistributeJob;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实生产 Mapper、单平台数据范围和独立事务，随机命名空间不触及业务库。 */
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class MasterDistributeRunPostgreSqlTest {
    private Connection control;
    private String schema;
    private Path root;
    private MasterDistributeMapper tasks;
    private MasterDistributeLogMapper runs;
    private MasterModelMapper models;
    private MasterDistributeAlertMapper alerts;
    private MasterDistributeCheckpointMapper checkpoints;
    private MasterDistributeAlertService alertService;
    private cn.iocoder.yudao.module.governance.framework.rpc.MasterDistributeAlertNotifyClient notifyClient;
    private DataSourceTransactionManager manager;
    private MasterDistributeRunCoordinator coordinator;
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @BeforeEach void setup() throws Exception {
        dbSwitchRuntime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone();
        String url=System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control=DriverManager.getConnection(url,System.getProperty("datahub.test.postgres.user", "dataforge_test"),"");
        schema="masterguard_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA "+schema); sql("SET search_path TO "+schema);
        root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root=root.getParent();
        assertNotNull(root);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.copyCurrentTables(control, schema,
                List.of("dh_governance_master_distribute", "dh_governance_master_distribute_log", "dh_governance_master_model",
                        "dh_governance_master_distribute_alert", "dh_governance_master_distribute_alert_disposition", "dh_governance_master_distribute_checkpoint"));
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,System.getProperty("datahub.test.postgres.user", "dataforge_test"),"");
        manager=new DataSourceTransactionManager(source);
        var config=new MybatisConfiguration();
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("master-test",new SpringManagedTransactionFactory(),source));
        var interceptor=new MybatisPlusInterceptor();
        config.addInterceptor(interceptor);
        for(Class<?> mapper:List.of(MasterDistributeMapper.class,MasterDistributeLogMapper.class,MasterModelMapper.class,MasterDistributeAlertMapper.class,MasterDistributeCheckpointMapper.class)) config.addMapper(mapper);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        tasks=session.getMapper(MasterDistributeMapper.class); runs=session.getMapper(MasterDistributeLogMapper.class); models=session.getMapper(MasterModelMapper.class);
        alerts=session.getMapper(MasterDistributeAlertMapper.class);
        checkpoints=session.getMapper(MasterDistributeCheckpointMapper.class);
        notifyClient=mock(cn.iocoder.yudao.module.governance.framework.rpc.MasterDistributeAlertNotifyClient.class);
        alertService=alertNode();
        coordinator=node(); clearTenantContext();
        sql("INSERT INTO dh_governance_master_model(id,domain_id,catalog_id,database_id,table_name,table_cn,status) VALUES(100,1,1,1,'rows','测试模型',1),(200,2,2,2,'other_rows','其他模型',1)");
        sql("INSERT INTO dh_governance_master_distribute(id,master_model_id,name,distribute_type,distribute_json,status) VALUES(10,100,'test',2,'{\"api\":{\"url\":\"http://invalid.test\"}}',0),(20,200,'test',2,'{}',0)");
    }

    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear();
        if (dbSwitchRuntime != null) dbSwitchRuntime.close();
        if(control!=null) try { if(schema!=null && schema.matches("masterguard_[a-f0-9]{32}")) sql("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); } finally {control.close();}
    }
    private void sql(String value) throws Exception {try(var s=control.createStatement()){s.execute(value);}}
    private long number(String query) throws Exception {try(var s=control.createStatement();var rs=s.executeQuery(query)){assertTrue(rs.next());return rs.getLong(1);}}
    // 当前基线已吸收历史迁移；测试隔离结构以现行基线为准，不依赖已移除的编号文件。
    private void upgrade() throws Exception { cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(control, schema); }
    private static void clearTenantContext(){TenantContextHolder.clear();TenantContextHolder.setIgnore(false);}
    private String baselineDoBlock(String marker) throws Exception {
        String baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int start=baseline.indexOf("DO "+marker),end=baseline.indexOf(marker+";",start+4);
        assertTrue(start>=0&&end>start);return baseline.substring(start,end+marker.length()+1).replace("\"datahub-center\"",schema);
    }
    private cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeStopReqVO stopRequest(Long record) {
        var request=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeStopReqVO();
        request.setRecordId(record);request.setReason("维护停止");return request;
    }

    @Test void independentStopWorksForUnpublishedManualRunAndDoesNotStopNextRun() {
        var first=claimed();var request=stopRequest(first.getId());
        var receipt=node().stop(request,99L);
        assertEquals(4,receipt.getRunStatus());assertEquals(99L,receipt.getStopRequestedBy());assertNotNull(receipt.getStopRequestedAt());
        assertEquals("维护停止",receipt.getStopReason());assertEquals("99",receipt.getUpdater());
        assertEquals(0,tasks.selectById(10L).getStatus());assertEquals(0,tasks.selectById(10L).getScheduleGeneration());
        assertFalse(coordinator.begin(first));assertFalse(coordinator.heartbeat(first));assertNull(coordinator.claim(first));
        var next=claimed();assertNotEquals(first.getId(),next.getId());
        assertEquals(receipt.getStopRequestedAt(),node().stop(request,99L).getStopRequestedAt());
        assertEquals(1,runs.selectById(next.getId()).getRunStatus());assertTrue(coordinator.begin(next));
    }
    @Test void independentStopPreservesPeriodicPublicationAndOriginalSchedule() throws Exception {
        var original=due();var run=claimed();node().stop(stopRequest(run.getId()),99L);
        var after=tasks.selectById(10L);
        assertEquals(original.getStatus(),after.getStatus());assertEquals(original.getNextRunTime(),after.getNextRunTime());
        assertEquals(original.getScheduleGeneration(),after.getScheduleGeneration());
        assertNotNull(coordinator.scheduled(original));
    }
    @Test void independentSendingStopKeepsLeaseAndUnknownBlockWithSingleDurableAlert() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET creator='8' WHERE id=10");
        var run=claimed();assertTrue(coordinator.begin(run));var request=stopRequest(run.getId());
        var receipt=node().stop(request,99L);assertEquals(5,receipt.getRunStatus());
        assertEquals(run.getLeaseToken(),receipt.getLeaseToken());assertEquals(run.getLeaseUntil(),receipt.getLeaseUntil());
        assertFalse(coordinator.heartbeat(run));assertFalse(coordinator.finish(run,true,3,null));assertNull(accepted());
        assertEquals(5,node().stop(request,99L).getRunStatus());
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
        assertEquals("UNKNOWN",alertFor(run.getId()).getEventType());assertEquals(8L,alertFor(run.getId()).getRecipientId());
    }
    @Test void stopCannotOverwriteActorOrReasonAndRejectsMissingTrustedContext() {
        var run=claimed();var request=stopRequest(run.getId());var receipt=coordinator.stop(request,99L);
        request.setReason(" 维护停止 ");assertEquals(receipt.getStopRequestedAt(),node().stop(request,99L).getStopRequestedAt());
        assertThrows(RuntimeException.class,()->node().stop(request,100L));
        request.setReason("另一原因");assertThrows(RuntimeException.class,()->node().stop(request,99L));
        assertEquals("维护停止",runs.selectById(run.getId()).getStopReason());
        assertThrows(RuntimeException.class,()->node().stop(stopRequest(9999L),99L));
        clearTenantContext();assertThrows(RuntimeException.class,()->node().stop(request,null));
    }
    @Test void completedRunCannotBeStoppedAndLateCommandCannotTargetNewRun() {
        var first=claimed();assertTrue(coordinator.begin(first));assertTrue(coordinator.finish(first,true,3,null));
        var next=claimed();assertThrows(RuntimeException.class,()->node().stop(stopRequest(first.getId()),99L));
        assertEquals(3,runs.selectById(first.getId()).getRunStatus());assertNull(runs.selectById(first.getId()).getStopRequestedAt());
        assertEquals(1,runs.selectById(next.getId()).getRunStatus());
    }
    @Test void legacyRunCannotBeMisclassifiedAsSafePreSendStop() throws Exception {
        var run=claimed();sql("UPDATE dh_governance_master_distribute_log SET run_protocol=0 WHERE id="+run.getId());
        assertThrows(RuntimeException.class,()->node().stop(stopRequest(run.getId()),99L));
        assertEquals(1,runs.selectById(run.getId()).getRunStatus());assertNull(runs.selectById(run.getId()).getStopRequestedAt());
    }
    @Test void stopAuditAndStatusRollBackWhenUnknownAlertCannotBePersisted() {
        var run=claimed();assertTrue(coordinator.begin(run));
        var failing=node();var service=mock(MasterDistributeAlertService.class);
        doThrow(new IllegalStateException("fixture")).when(service).enqueue(any(),eq(run.getId()),eq("UNKNOWN"));
        ReflectionTestUtils.setField(failing,"alertService",service);
        assertThrows(IllegalStateException.class,()->failing.stop(stopRequest(run.getId()),99L));
        var stored=runs.selectById(run.getId());assertEquals(2,stored.getRunStatus());assertNull(stored.getStopRequestedAt());
        assertTrue(coordinator.heartbeat(run));
    }
    @Test void simultaneousBeginAndStopClassifyUsingActualPersistedSendBoundary() throws Exception {
        var run=claimed();try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var begin=pool.submit(()->{start.await();return node().begin(run);});
            var stop=pool.submit(()->{start.await();return node().stop(stopRequest(run.getId()),99L);});
            start.countDown();boolean entered=begin.get(10,TimeUnit.SECONDS);
            assertEquals(entered?5:4,stop.get(10,TimeUnit.SECONDS).getRunStatus());
            assertFalse(coordinator.begin(run));assertFalse(coordinator.finish(run,entered,entered?3:4,null));
        }
    }
    @Test void stopIsNotTerminationEvidenceAndReplayPreservesLaterManualResolution() throws Exception {
        var run=claimed();coordinator.begin(run);var request=stopRequest(run.getId());
        var stopped=node().stop(request,99L);
        assertThrows(RuntimeException.class,()->coordinator.resolve(review(run.getId()),99L));
        expire(run);coordinator.resolve(review(run.getId()),99L);
        var receipt=node().stop(request,99L);assertEquals(6,receipt.getRunStatus());
        assertEquals(stopped.getStopRequestedAt(),receipt.getStopRequestedAt());
        assertEquals(stopped.getStopReason(),receipt.getStopReason());assertNotNull(receipt.getResolvedAt());
    }
    @Test void simultaneousFinishAndStopHaveExactlyOneDurableWinner() throws Exception {
        var run=claimed();coordinator.begin(run);
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var finish=pool.submit(()->{start.await();return node().finish(run,true,3,null);});
            var stop=pool.submit(()->{start.await();
                try{node().stop(stopRequest(run.getId()),99L);return true;}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException conflict){
                    assertEquals(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_DISTRIBUTE_STOP_CONFLICT.getCode(),conflict.getCode());
                    return false;
                }
            });
            start.countDown();boolean completed=finish.get(10,TimeUnit.SECONDS);
            assertNotEquals(completed,stop.get(10,TimeUnit.SECONDS));
            var stored=runs.selectById(run.getId());assertEquals(completed?3:5,stored.getRunStatus());
            assertEquals(!completed,stored.getStopRequestedAt()!=null);
            assertEquals(completed?0:1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
        }
    }
    @Test void concurrentIdenticalStopRequestsReturnOneUnchangedReceipt() throws Exception {
        var run=claimed();assertTrue(coordinator.begin(run));try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            Callable<MasterDistributeLogDO> stop=()->{start.await();return node().stop(stopRequest(run.getId()),99L);};
            var a=pool.submit(stop);var b=pool.submit(stop);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS).getStopRequestedAt(),b.get(10,TimeUnit.SECONDS).getStopRequestedAt());
            assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
        }
    }
    @Test void stopMigrationReplaysWithoutChangingAuditAndBaselineContainsSameSlice() throws Exception {
        var run=claimed();var receipt=coordinator.stop(stopRequest(run.getId()),99L);upgrade();upgrade();
        assertEquals(receipt.getStopRequestedAt(),runs.selectById(run.getId()).getStopRequestedAt());
        assertThrows(SQLException.class,()->sql("UPDATE dh_governance_master_distribute_log SET stop_reason=NULL WHERE id="+run.getId()));
        assertThrows(SQLException.class,()->sql("UPDATE dh_governance_master_distribute_log SET run_status=3 WHERE id="+run.getId()));
        assertEquals(receipt.getStopRequestedAt(),runs.selectById(run.getId()).getStopRequestedAt());
    }
    private MasterDistributeRunCoordinator node(){
        var result=new MasterDistributeRunCoordinator();
        ReflectionTestUtils.setField(result,"tasks",tasks); ReflectionTestUtils.setField(result,"runs",runs);
        ReflectionTestUtils.setField(result,"alertService",alertService);
        ReflectionTestUtils.setField(result,"checkpoints",checkpoints);
        ReflectionTestUtils.setField(result,"models",models);ReflectionTestUtils.setField(result,"transactionManager",manager);return result;
    }
    private MasterDistributeAlertService alertNode(){
        var service=new MasterDistributeAlertService();ReflectionTestUtils.setField(service,"alerts",alerts);
        ReflectionTestUtils.setField(service,"transactionManager",manager);ReflectionTestUtils.setField(service,"notifyClient",notifyClient);return service;
    }
    private MasterDistributeLogDO accepted(){return coordinator.accept(10L,MasterRunTypeEnum.HAND);}
    private MasterDistributeLogDO claimed(){return coordinator.claim(accepted());}
    private void expire(MasterDistributeLogDO r) throws Exception {sql("UPDATE dh_governance_master_distribute_log SET lease_until=clock_timestamp()-interval '1 second',available_at=clock_timestamp()-interval '1 second' WHERE id="+r.getId());}

    @Test void cancelPreservesLatestPublicationAndRepublishReplacesIt() {
        var firstRun=coordinator.release(10L,9L);var first=tasks.selectById(10L);
        assertEquals(1,first.getStatus());assertEquals(9L,first.getReleaseUserId());assertNotNull(first.getReleaseTime());
        assertNull(first.getNextRunTime());
        coordinator.cancel(10L);var cancelled=tasks.selectById(10L);
        assertEquals(0,cancelled.getStatus());assertNull(cancelled.getNextRunTime());
        assertEquals(first.getReleaseUserId(),cancelled.getReleaseUserId());assertEquals(first.getReleaseTime(),cancelled.getReleaseTime());
        assertEquals(4,runs.selectById(firstRun.getId()).getRunStatus());
        var secondRun=node().release(10L,99L);var second=tasks.selectById(10L);
        assertEquals(1,second.getStatus());assertEquals(99L,second.getReleaseUserId());
        assertFalse(second.getReleaseTime().isBefore(first.getReleaseTime()));
        assertEquals(cancelled.getScheduleGeneration()+1,second.getScheduleGeneration());
        assertEquals(1,runs.selectById(secondRun.getId()).getRunStatus());
    }

    @Test void cancellationAndPublicationRollBackTogetherOnLogFailure() throws Exception {
        var run=coordinator.claim(coordinator.release(10L,1L));assertTrue(coordinator.begin(run));
        sql("ALTER TABLE dh_governance_master_distribute_log ADD CONSTRAINT deny_unknown CHECK(run_status<>5)");
        assertThrows(RuntimeException.class,()->coordinator.cancel(10L));assertEquals(1,tasks.selectById(10L).getStatus());assertEquals(2,runs.selectById(run.getId()).getRunStatus());
    }

    @Test void acceptedBeforeQueueAndAnotherNodeCannotCreateSecondRun(){
        var first=accepted();assertNotNull(first);assertEquals(1,runs.selectById(first.getId()).getRunStatus());
        assertNull(node().accept(10L,MasterRunTypeEnum.HAND));assertEquals(1,coordinator.blockingState(10L));
    }

    @Test void twoNodesClaimOnlyOneLeaseAndExpiredTokenCannotAct() throws Exception {
        var run=accepted();var first=coordinator.claim(run);assertNotNull(first);assertNull(node().claim(run));
        expire(first);var next=node().claim(run);assertNotNull(next);assertNotEquals(first.getLeaseToken(),next.getLeaseToken());
        assertFalse(coordinator.begin(first));assertFalse(coordinator.heartbeat(first));
        coordinator.rejected(first);assertFalse(coordinator.finish(first,false,4,"旧线程"));
        assertTrue(coordinator.begin(next));assertTrue(coordinator.finish(next,true,3,null));
        assertEquals(3,runs.selectById(next.getId()).getRunStatus());
    }

    @Test void failedInsertNeverAdvancesSchedule() throws Exception {
        var snapshot=due();sql("ALTER TABLE dh_governance_master_distribute_log ADD CONSTRAINT deny_waiting CHECK(run_status<>1)");
        assertThrows(RuntimeException.class,()->coordinator.scheduled(snapshot));assertEquals(snapshot.getNextRunTime(),tasks.selectById(10L).getNextRunTime());
    }

    @Test void runningLeaseRecoveryNeverResends() throws Exception {
        var run=claimed();assertTrue(coordinator.begin(run));expire(run);assertNull(node().claim(run));
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());assertNull(accepted());assertFalse(coordinator.finish(run,true,3,null));
    }

    @Test void concurrentAcceptHasOneDurableWinner() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            Callable<MasterDistributeLogDO> call=()->{start.await();return node().accept(10L,MasterRunTypeEnum.HAND);};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertEquals(1,(a.get(10,TimeUnit.SECONDS)==null?0:1)+(b.get(10,TimeUnit.SECONDS)==null?0:1));
        }
    }

    @Test void crossNodeCancelSeparatesBeforeAndAfterSending() {
        var pending=coordinator.release(10L,1L);var before=coordinator.claim(pending);node().cancel(10L);
        assertFalse(coordinator.begin(before));assertEquals(4,runs.selectById(before.getId()).getRunStatus());
        var after=coordinator.claim(coordinator.release(10L,1L));assertTrue(coordinator.begin(after));node().cancel(10L);
        assertEquals(5,runs.selectById(after.getId()).getRunStatus());assertFalse(coordinator.finish(after,true,3,null));assertNull(accepted());
    }

    @Test void oneOffReleaseAndIntentRollbackTogether() throws Exception {
        sql("ALTER TABLE dh_governance_master_distribute_log ADD CONSTRAINT deny_waiting CHECK(run_status<>1)");
        assertThrows(RuntimeException.class,()->coordinator.release(10L,1L));assertEquals(0,tasks.selectById(10L).getStatus());assertEquals(0,tasks.selectById(10L).getScheduleGeneration());
    }

    @Test void rejectionPersistsSameIntentWithBackoffAndRecovers() throws Exception {
        var first=claimed();coordinator.rejected(first);assertNull(node().claim(first));
        sql("UPDATE dh_governance_master_distribute_log SET available_at=clock_timestamp()-interval '1 second' WHERE id="+first.getId());
        var second=node().claim(first);assertNotNull(second);assertEquals(first.getId(),second.getId());assertEquals(2,second.getAttempts());
    }

    @Test void changedGenerationAndInvalidCronCannotLoseSlot() throws Exception {
        var snapshot=due();sql("UPDATE dh_governance_master_distribute SET schedule_generation=1 WHERE id=10");assertNull(coordinator.scheduled(snapshot));
        sql("UPDATE dh_governance_master_distribute SET cron='invalid' WHERE id=10");
        var current=tasks.selectById(10L);assertThrows(RuntimeException.class,()->coordinator.scheduled(current));assertEquals(snapshot.getNextRunTime(),tasks.selectById(10L).getNextRunTime());
    }

    private MasterDistributeDO due() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET status=1,task_type=2,cron='0 * * * * ?',next_run_time=date_trunc('minute',clock_timestamp())-interval '5 minutes' WHERE id=10");return tasks.selectById(10L);
    }
    @Test void scheduleIntentAndOriginalSlotAdvanceAtomically() throws Exception {
        var snapshot=due();var run=coordinator.scheduled(snapshot);assertNotNull(run);assertEquals(snapshot.getNextRunTime(),run.getScheduledFor());
        assertEquals(snapshot.getNextRunTime().plusMinutes(1),tasks.selectById(10L).getNextRunTime());
        assertNull(node().scheduled(snapshot));assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_log"));
    }

    @Test void configuredPreSendAttemptsExhaustWithoutEnteringSending() throws Exception {
        var run=accepted();
        for(int i=0;i<4;i++){var current=node().claim(run);assertNotNull(current);expire(current);}
        assertNull(node().claim(run));assertEquals(4,runs.selectById(run.getId()).getRunStatus());assertNotNull(accepted());
    }

    @Test void busyTaskDoesNotConsumeScheduledSlot() throws Exception {
        var snapshot=due();accepted();assertNull(coordinator.scheduled(snapshot));assertEquals(snapshot.getNextRunTime(),tasks.selectById(10L).getNextRunTime());
    }

    @Test void publishedUnknownCanBeCancelledWithoutLosingPublicationOrEnablingResend() {
        var run=coordinator.claim(coordinator.release(10L,9L));assertTrue(coordinator.begin(run));
        assertTrue(coordinator.finish(run,true,5,"待核查"));var published=tasks.selectById(10L);
        assertEquals(1,published.getStatus());assertEquals(5,coordinator.blockingState(10L));
        node().cancel(10L);var cancelled=tasks.selectById(10L);
        assertEquals(0,cancelled.getStatus());assertNull(cancelled.getNextRunTime());
        assertEquals(published.getReleaseUserId(),cancelled.getReleaseUserId());assertEquals(published.getReleaseTime(),cancelled.getReleaseTime());
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());assertEquals(5,coordinator.blockingState(10L));
        assertNull(accepted());assertThrows(RuntimeException.class,()->coordinator.release(10L,99L));
        assertThrows(RuntimeException.class,()->coordinator.withIdleTask(10L,t->null));
        assertFalse(coordinator.finish(run,true,3,null));
    }

    @Test void failedScheduleUpdateRollsBackIntent() throws Exception {
        var snapshot=due();sql("CREATE FUNCTION reject_task_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_task BEFORE UPDATE ON dh_governance_master_distribute FOR EACH ROW EXECUTE FUNCTION reject_task_update()");
        assertThrows(RuntimeException.class,()->coordinator.scheduled(snapshot));assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_log"));
    }

    @Test void expiredLeaseCannotBeRevivedEvenBeforeReclaim() throws Exception {
        var run=claimed();expire(run);assertFalse(coordinator.heartbeat(run));assertFalse(coordinator.begin(run));assertFalse(coordinator.finish(run,false,4,null));
    }

    @Test void unknownSurvivesLaterSuccessAndBlocksMutationAndReexecution() throws Exception {
        var run=claimed();assertTrue(coordinator.begin(run));assertTrue(coordinator.finish(run,true,5,"待核查"));
        sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time) VALUES(999,10,100,2,3,clock_timestamp())");
        assertEquals(5,coordinator.blockingState(10L));assertNull(accepted());
        assertThrows(RuntimeException.class,()->coordinator.withIdleTask(10L,t->null));
        assertThrows(RuntimeException.class,()->coordinator.release(10L,1L));
    }

    @Test void executionUsesDurableOwnershipWithoutTenantContext() {
        var run=claimed(); TenantContextHolder.clear();
        assertTrue(coordinator.begin(run));
        assertNull(coordinator.accept(10L,MasterRunTypeEnum.HAND));
        assertThrows(RuntimeException.class,()->coordinator.accept(9999L,MasterRunTypeEnum.HAND));
    }
    @Test void singleTenantMigrationReplaysWithoutTouchingRunStates() throws Exception {
        var active=claimed();
        sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time,error_info) VALUES(901,30,100,2,2,clock_timestamp(),NULL),(902,40,100,2,4,clock_timestamp(),'UnconfirmedWriteException'),(903,50,100,2,4,clock_timestamp(),'validation')");
        upgrade();upgrade();assertEquals(2,runs.selectById(901L).getRunStatus());assertEquals(4,runs.selectById(902L).getRunStatus());assertEquals(4,runs.selectById(903L).getRunStatus());
        assertEquals(active.getLeaseToken(),runs.selectById(active.getId()).getLeaseToken());assertEquals(1,runs.selectById(active.getId()).getRunStatus());
    }
    @Test void orphanedRunningRecordStillBecomesUnknown() throws Exception {
        var run=claimed();coordinator.begin(run);expire(run);sql("UPDATE dh_governance_master_distribute SET deleted=1 WHERE id=10");
        assertNull(node().claim(run));assertEquals(5,runs.selectById(run.getId()).getRunStatus());
    }

    @Test void twoActualExecutorsShareDurableAdmissionWithoutTenantContext() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var sends=new AtomicInteger();
        MasterDistributeExecutor a=worker(entered,release,finished,sends),b=worker(entered,release,finished,sends);a.init();b.init();
        try {
            assertTrue(a.submit(10L,MasterRunTypeEnum.HAND));assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertFalse(b.submit(10L,MasterRunTypeEnum.HAND));release.countDown();assertTrue(finished.await(10,TimeUnit.SECONDS));assertEquals(1,sends.get());
        } finally {release.countDown();a.destroy();b.destroy();}
    }
    private MasterDistributeExecutor worker(CountDownLatch entered,CountDownLatch release,CountDownLatch finished,AtomicInteger sends) {
        var worker=new MasterDistributeExecutor(){@Override void execute(MasterDistributeLogDO run){
            try {
                assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if(!coordinator.begin(run))return;sends.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));coordinator.finish(run,true,3,null);
            } catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);} finally{finished.countDown();}
        }};ReflectionTestUtils.setField(worker,"coordinator",node());return worker;
    }
    @Test void actualJobRecoversUnreleasedManualIntentWithoutTenantContext() {
        var run=accepted();var executor=mock(MasterDistributeExecutor.class);var job=new MasterDistributeJob();
        ReflectionTestUtils.setField(job,"distributeMapper",tasks);ReflectionTestUtils.setField(job,"logMapper",runs);
        ReflectionTestUtils.setField(job,"distributeExecutor",executor);ReflectionTestUtils.setField(job,"coordinator",coordinator);
        doAnswer(call->{assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());return null;}).when(executor).dispatch(any());
        clearTenantContext();TenantContextHolder.clear();job.execute();
        verify(executor).dispatch(argThat(candidate->candidate.getId().equals(run.getId())));
        assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());
    }
    @Test void serviceMutationCannotBypassUnknownGuard() {
        var run=claimed();coordinator.begin(run);coordinator.finish(run,true,5,"待核查");
        var service=new MasterDistributeServiceImpl();ReflectionTestUtils.setField(service,"coordinator",coordinator);
        assertThrows(RuntimeException.class,()->service.deleteDistribute(10L));assertNotNull(tasks.selectById(10L));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CONFIRMED,true,3","CONFIRMED,false,5","UNCONFIRMED,true,5","UNCONFIRMED,false,5"})
    void realExecutorPersistsPartialAndUnknownWritesWithoutRerun(String termination, boolean success, int expected) throws Exception {
        sql("UPDATE dh_governance_master_distribute SET distribute_type=1,distribute_json='{\"db\":{\"databaseId\":1}}' WHERE id=10");
        var executor=new MasterDistributeExecutor();
        ReflectionTestUtils.setField(executor,"coordinator",coordinator);ReflectionTestUtils.setField(executor,"distributeMapper",tasks);ReflectionTestUtils.setField(executor,"modelMapper",models);
        var resolver=mock(MasterDatabaseResolver.class);var connection=new MasterDatabaseResolver.ConnectionInfo();
        connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);connection.setJdbcUrl("jdbc:postgresql://invalid.test/fixture");connection.setSchema("public");
        when(resolver.resolve(anyLong(),any())).thenReturn(connection);ReflectionTestUtils.setField(executor,"databaseResolver",resolver);
        var result=new cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult();result.getIfAllSuccess().set(success);
        result.setTermination(cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination.valueOf(termination));
        var run=claimed();
        try(var engines=mockConstruction(cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService.class,(engine,ctx)->when(engine.run()).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(2,runs.selectById(run.getId()).getRunStatus());return result;
        }))){
            executor.execute(run);verify(engines.constructed().getFirst(),times(1)).run();
        }
        assertEquals(expected,runs.selectById(run.getId()).getRunStatus());
        if(expected==5){assertNull(accepted());assertThrows(RuntimeException.class,()->coordinator.release(10L,1L));}
    }
    private MasterDistributeExecutor httpExecutor(MasterDistributeExecutor executor, String url) throws Exception {
        sql("CREATE TABLE http_rows(id integer PRIMARY KEY)");sql("INSERT INTO http_rows VALUES(1),(2)");
        sql("UPDATE dh_governance_master_model SET table_name='http_rows' WHERE id=100");
        sql("UPDATE dh_governance_master_distribute SET distribute_type=2,distribute_json='{\"api\":{\"url\":\""+url+"\",\"fetchSize\":1}}',creator='8' WHERE id=10");
        ReflectionTestUtils.setField(executor,"coordinator",node());ReflectionTestUtils.setField(executor,"distributeMapper",tasks);ReflectionTestUtils.setField(executor,"modelMapper",models);
        ReflectionTestUtils.setField(executor,"dbSwitchRuntime",dbSwitchRuntime);
        var columns=mock(MasterColumnMapper.class);when(columns.selectListByModelId(100L)).thenReturn(List.of(MasterColumnDO.builder().name("id").build()));
        ReflectionTestUtils.setField(executor,"columnMapper",columns);
        var policy=new MasterDistributeApiPolicy();ReflectionTestUtils.setField(policy,"columns",columns);
        ReflectionTestUtils.setField(executor,"apiPolicy",policy);ReflectionTestUtils.setField(executor,"checkpoints",checkpoints);
        var resolver=spy(new MasterDatabaseResolver());var connection=new MasterDatabaseResolver.ConnectionInfo();
        ReflectionTestUtils.setField(resolver,"configuredJdbcDrivers",dbSwitchRuntime.drivers());
        ReflectionTestUtils.setField(resolver,"dbSwitchOptions",dbSwitchRuntime.options());
        connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);connection.setSchema(schema);
        connection.setJdbcUrl(System.getProperty("datahub.test.postgres.url")+"?ApplicationName=master-http-"+schema);connection.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));connection.setPassword("");
        doReturn(connection).when(resolver).resolve(anyLong(),any());
        doAnswer(call->{var pool=spy((com.zaxxer.hikari.HikariDataSource)call.callRealMethod());
            doAnswer(close->{assertFalse(Thread.currentThread().isInterrupted(),"关闭源池前应暂清中断");return close.callRealMethod();}).when(pool).close();return pool;
        }).when(resolver).createDataSource(any());
        ReflectionTestUtils.setField(executor,"databaseResolver",resolver);return executor;
    }

    @Test void invalidHttpPlanPersistsSafeFailureWithoutUnknownOrAlert() throws Exception {
        var executor=httpExecutor(new MasterDistributeExecutor(),"file:///fixture");var run=claimed();executor.execute(run);
        assertEquals(4,runs.selectById(run.getId()).getRunStatus());assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));assertNotNull(accepted());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void realDatabaseToHttpPreservesConfirmedPrefixAndNeverReplaysUnknown(boolean failSecond) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/accept",exchange->{int index=calls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="fixture-private-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failSecond&&index==2?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            var executor=httpExecutor(new MasterDistributeExecutor(),"http://127.0.0.1:"+server.getAddress().getPort()+"/accept");var run=claimed();executor.execute(run);
            var stored=runs.selectById(run.getId());assertEquals(failSecond?5:3,stored.getRunStatus());assertEquals(failSecond?1L:2L,stored.getDataCount());assertEquals(2,calls.get());
            if(failSecond){assertFalse(stored.getErrorInfo().contains("fixture-private-response"));assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));assertNull(accepted());}
            else assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
            executor.execute(run);assertEquals(2,calls.get());assertEquals(stored.getRunStatus(),runs.selectById(run.getId()).getRunStatus());
        }finally{server.stop(0);}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void crossNodeCancelInterruptsActiveHttpAndPersistsUnknownAlertWithoutNextBatch(boolean independentStop) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var calls=new AtomicInteger();
        var workerInterrupted=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/accept",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();entered.countDown();
            try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var executor=httpExecutor(new MasterDistributeExecutor(){@Override void execute(MasterDistributeLogDO run){try{super.execute(run);}finally{workerInterrupted.set(Thread.currentThread().isInterrupted());finished.countDown();}}},"http://127.0.0.1:"+server.getAddress().getPort()+"/accept");
        executor.init();
        try {
            var run=independentStop?accepted():coordinator.release(10L,9L);executor.dispatch(run);assertTrue(entered.await(5,TimeUnit.SECONDS));
            if(independentStop)node().stop(stopRequest(run.getId()),99L);else node().cancel(10L);
            assertTrue(finished.await(12,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(5,runs.selectById(run.getId()).getRunStatus());
            assertTrue(workerInterrupted.get());
            assertEquals(0,number("SELECT count(*) FROM pg_stat_activity WHERE application_name='master-http-"+schema+"'"),"取消完成后源连接不能遗留在已废弃池中");
            assertEquals("UNKNOWN",alertFor(run.getId()).getEventType());assertEquals(independentStop?8L:9L,alertFor(run.getId()).getRecipientId());assertNull(accepted());
            if(independentStop){assertEquals(0,tasks.selectById(10L).getStatus());assertEquals(99L,runs.selectById(run.getId()).getStopRequestedBy());}
        }finally{release.countDown();executor.destroy();server.stop(0);}
    }

    private MasterDistributeExecutor mqExecutor(MasterDistributeExecutor executor, String type) throws Exception {
        httpExecutor(executor,"http://localhost/unused-fixture");
        var config=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO();
        config.setMq(type.equals("kafka")?MasterMqFixture.kafka("localhost:19093","fixture"):MasterMqFixture.rabbit(5672,"fixture","key"));
        try(var statement=control.prepareStatement("UPDATE dh_governance_master_distribute SET distribute_type=3,distribute_json=CAST(? AS jsonb) WHERE id=10")){
            statement.setString(1,cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(config));statement.executeUpdate();
        }
        return executor;
    }

    @Test void invalidMqPlanPersistsSafeFailureWithoutAlert() throws Exception {
        var executor=mqExecutor(new MasterDistributeExecutor(),"rabbitmq");
        sql("UPDATE dh_governance_master_distribute SET distribute_json='{\"mq\":{\"mqType\":\"rabbitmq\",\"rabbit\":{\"host\":\"localhost\",\"port\":0}}}' WHERE id=10");
        var run=claimed();executor.execute(run);assertEquals(4,runs.selectById(run.getId()).getRunStatus());
        assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));assertNotNull(accepted());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"kafka,false","kafka,true","rabbitmq,false","rabbitmq,true"})
    void realDatabaseToMqPersistsConfirmedPrefixAndBlocksUnknownReplay(String type,boolean failSecond) throws Exception {
        var executor=mqExecutor(new MasterDistributeExecutor(),type);var calls=new AtomicInteger();var run=claimed();
        var connection=mock(com.rabbitmq.client.Connection.class);var channel=mock(com.rabbitmq.client.Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        doAnswer(call->{int batch=calls.incrementAndGet();assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2,runs.selectById(run.getId()).getRunStatus());
            if(failSecond&&batch==2)throw new java.io.IOException("fixture-private-broker-error");return null;
        }).when(channel).waitForConfirmsOrDie(anyLong());
        try(var producers=mockConstruction(org.apache.kafka.clients.producer.KafkaProducer.class,(producer,ctx)->when(producer.send(any())).thenAnswer(call->{
            int batch=calls.incrementAndGet();assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2,runs.selectById(run.getId()).getRunStatus());
            if(failSecond&&batch==2)return CompletableFuture.failedFuture(new IllegalStateException("fixture-private-broker-error"));
            return CompletableFuture.completedFuture(new org.apache.kafka.clients.producer.RecordMetadata(new org.apache.kafka.common.TopicPartition("fixture",0),batch,0,0,0,1));
        }));var factories=mockConstruction(com.rabbitmq.client.ConnectionFactory.class,(factory,ctx)->when(factory.newConnection()).thenReturn(connection))){
            executor.execute(run);var stored=runs.selectById(run.getId());assertEquals(failSecond?5:3,stored.getRunStatus());
            assertEquals(failSecond?1L:2L,stored.getDataCount());assertEquals(2,calls.get());
            if(failSecond){assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));assertNull(accepted());assertFalse(stored.getErrorInfo().contains("fixture-private"));}
            else assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
            executor.execute(run);assertEquals(2,calls.get());assertEquals(stored.getRunStatus(),runs.selectById(run.getId()).getRunStatus());
        }
        assertEquals(0,number("SELECT count(*) FROM pg_stat_activity WHERE application_name='master-http-"+schema+"'"));
    }

    @Test void crossNodeMqCancelStopsFollowingBatchAndPersistsUnknownAlert() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CompletableFuture<Boolean>();var calls=new AtomicInteger();
        var connection=mock(com.rabbitmq.client.Connection.class);var channel=mock(com.rabbitmq.client.Channel.class);when(connection.createChannel()).thenReturn(channel);
        doAnswer(call->{calls.incrementAndGet();entered.countDown();release.await(15,TimeUnit.SECONDS);return null;}).when(channel).waitForConfirmsOrDie(anyLong());
        var executor=mqExecutor(new MasterDistributeExecutor(){@Override void execute(MasterDistributeLogDO run){
            try(var factories=mockConstruction(com.rabbitmq.client.ConnectionFactory.class,(factory,ctx)->when(factory.newConnection()).thenReturn(connection))){
                super.execute(run);finished.complete(Thread.currentThread().isInterrupted());
            }catch(Throwable failure){finished.completeExceptionally(failure);}
        }},"rabbitmq");executor.init();
        try{
            var run=coordinator.release(10L,9L);executor.dispatch(run);assertTrue(entered.await(5,TimeUnit.SECONDS));node().cancel(10L);
            assertTrue(finished.get(12,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(5,runs.selectById(run.getId()).getRunStatus());
            assertEquals("UNKNOWN",alertFor(run.getId()).getEventType());assertEquals(9L,alertFor(run.getId()).getRecipientId());assertNull(accepted());
            verify(connection).abort(1000);assertEquals(0,number("SELECT count(*) FROM pg_stat_activity WHERE application_name='master-http-"+schema+"'"));
        }finally{release.countDown();executor.destroy();}
    }

    @Test void actualExecutorPrepareFailureIsSafeAndRetryable() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET distribute_json=NULL WHERE id=10");
        var executor=new MasterDistributeExecutor();ReflectionTestUtils.setField(executor,"coordinator",coordinator);
        ReflectionTestUtils.setField(executor,"distributeMapper",tasks);ReflectionTestUtils.setField(executor,"modelMapper",models);
        var run=claimed();executor.execute(run);assertEquals(4,runs.selectById(run.getId()).getRunStatus());assertNotNull(accepted());
    }
    @Test void actualQueueRejectionRetainsCommittedWaitingIntent() throws Exception {
        var executor=new MasterDistributeExecutor();ReflectionTestUtils.setField(executor,"coordinator",coordinator);
        var pool=Executors.newSingleThreadExecutor();pool.shutdown();ReflectionTestUtils.setField(executor,"executor",pool);
        assertTrue(executor.submit(10L,MasterRunTypeEnum.HAND));assertEquals(1,coordinator.blockingState(10L));
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_log WHERE run_status=1 AND lease_token IS NULL"));
    }
    @Test void blockedTasksDoNotFillDueCandidateWindow() throws Exception {
        due();var run=claimed();coordinator.begin(run);coordinator.finish(run,true,5,"待核查");
        assertTrue(tasks.dueCandidates().isEmpty());
    }
    @Test void uniqueActiveAndScheduleSlotConstraintsRejectDuplicates() throws Exception {
        var pending=accepted();
        assertThrows(SQLException.class,()->sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time,run_protocol) VALUES(900,10,100,2,1,clock_timestamp(),1)"));
        var current=coordinator.claim(pending);coordinator.begin(current);coordinator.finish(current,true,3,null);
        var run=coordinator.scheduled(due());var claim=coordinator.claim(run);coordinator.begin(claim);coordinator.finish(claim,true,3,null);
        assertThrows(SQLException.class,()->sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time,run_protocol,schedule_generation,scheduled_for) SELECT 900,distribute_id,master_model_id,run_type,3,start_time,1,schedule_generation,scheduled_for  FROM dh_governance_master_distribute_log WHERE id=" + run.getId()));
    }
    @Test void baselineRuntimeSliceMatchesMigration() throws Exception {
        String baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n","\n");
        int start=baseline.indexOf("-- 主数据派发持久运行结构，已包含增量0060");assertTrue(start>0);
        int end=baseline.indexOf("-- ----------------------------",start);assertTrue(end>start);
        String fresh=schema+"_baseline";
        assertTrue(fresh.matches("masterguard_[a-f0-9]{32}_baseline"));sql("CREATE SCHEMA "+fresh);
        try {
            for(String table:List.of("dh_governance_master_distribute","dh_governance_master_distribute_log")) {
                var ddl=Pattern.compile("CREATE TABLE \"datahub-center\"[.]\""+table+"\" \\(.*?\n\\)\\s*;",Pattern.DOTALL).matcher(baseline);
                assertTrue(ddl.find());sql(ddl.group().replace("\"datahub-center\"",fresh).replaceAll(" DEFAULT nextval\\([^\n]*?::regclass\\)",""));
                sql("CREATE SEQUENCE "+fresh+"."+table+"_seq");
                sql("ALTER TABLE "+fresh+"."+table+" ALTER COLUMN id SET DEFAULT nextval('"+fresh+"."+table+"_seq')");
                sql("ALTER TABLE "+fresh+"."+table+" ADD PRIMARY KEY(id)");
            }
            // 从历史基线片段组装后执行去租户迁移，验证与当前完整基线最终结构一致。
            sql(baseline.substring(start,end).replace("\"datahub-center\"",fresh));
            cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(control, fresh);
            String pushMigration=Files.readString(root.resolve("sql/postgresql/migrations/0188_governance_master-push.sql"));
            // 本用例只组装运行表；菜单更新由完整迁移验证覆盖。
            String pushStructure=pushMigration.substring(0,pushMigration.indexOf("UPDATE system_menu")) + "COMMIT;";
            sql(pushStructure.replace("\"datahub-center\"",fresh));sql(pushStructure.replace("\"datahub-center\"",fresh));
            for(String table:List.of("dh_governance_master_distribute","dh_governance_master_distribute_log","dh_governance_master_distribute_checkpoint"))
                assertEquals(columnDefinitions(schema,table),columnDefinitions(fresh,table));
            assertEquals(runtimeIndexes(schema),runtimeIndexes(fresh));
        } finally {sql("ROLLBACK");sql("DROP SCHEMA "+fresh+" CASCADE");}
    }

    private List<String> columnDefinitions(String namespace,String table) throws Exception {
        List<String> result=new ArrayList<>();
        try(var statement=control.prepareStatement("SELECT column_name,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY column_name")){
            statement.setString(1,namespace);statement.setString(2,table);
            try(var rs=statement.executeQuery()){while(rs.next())result.add(rs.getString(1)+":"+rs.getString(2)+":"+rs.getString(3)+":"+(rs.getString(4)!=null&&rs.getString(4).startsWith("nextval(")?"sequence":rs.getString(4)));}
        }return result;
    }
    private List<String> runtimeIndexes(String namespace) throws Exception {
        List<String> result=new ArrayList<>();
        try(var statement=control.prepareStatement("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname IN ('idx_master_distribute_blocking','idx_master_distribute_recovery','uk_master_distribute_active','uk_master_distribute_slot','idx_master_distribute_due','uk_master_distribute_resolution_request') ORDER BY indexname")){
            statement.setString(1,namespace);try(var rs=statement.executeQuery()){while(rs.next())result.add(rs.getString(1).replace(namespace,"schema"));}
        }assertEquals(6,result.size());return result;
    }

    private cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeResolveReqVO review(Long id) {
        var r=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeResolveReqVO();
        r.setRecordId(id);r.setRequestId(UUID.randomUUID().toString());r.setOutcome("NO_EFFECT");r.setReason("核查全部批次");
        r.setConfirmedStopped(true);r.setOutputReviewed(true);r.setStoppedEvidenceRef("CASE-STOP-1");r.setOutputEvidenceRef("CASE-OUTPUT-1");return r;
    }
    private MasterDistributeLogDO unknown() throws Exception {
        var run=claimed();coordinator.begin(run);run.setDataCount(7L);coordinator.finish(run,true,5,"原始未知错误");expire(run);return run;
    }

    @Test void unknownMustCommitADurableAlert() throws Exception {
        var run=unknown();
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert WHERE run_id="+run.getId()),
                "UNKNOWN 已保存但没有持久告警，进程退出后无人获知需核查");
    }

    private MasterDistributeAlertDO alertFor(Long run) throws Exception {
        return alerts.selectById(number("SELECT id FROM dh_governance_master_distribute_alert WHERE run_id="+run));
    }
    private MasterDistributeAlertDO readyAlert() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET release_user_id=9,creator='8' WHERE id=10");
        return alertFor(unknown().getId());
    }
    @Test void latestPublisherRemainsAlertRecipientAfterUnpublishing() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET creator='8' WHERE id=10");
        coordinator.release(10L,9L);coordinator.cancel(10L);
        var run=unknown();assertEquals(0,tasks.selectById(10L).getStatus());
        assertEquals(9L,alertFor(run.getId()).getRecipientId());
    }
    private void alertDue(Long id) throws Exception {
        sql("UPDATE dh_governance_master_distribute_alert SET available_at=clock_timestamp()-interval '1 second',lease_until=clock_timestamp()-interval '1 second' WHERE id="+id);
    }

    @Test void alertRecipientIsFrozenAndNoRecipientRemainsVisibleAsDeadLetter() throws Exception {
        var row=readyAlert();assertEquals(9L,row.getRecipientId());assertEquals(0,row.getStatus());
        sql("UPDATE dh_governance_master_distribute SET release_user_id=99,name='changed' WHERE id=10");
        coordinator.reconcileAlert(runs.selectById(row.getRunId()));
        var frozen=alerts.selectById(row.getId());assertEquals(9L,frozen.getRecipientId());assertEquals("test",frozen.getTaskName());
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
        clearTenantContext();var run=coordinator.claim(coordinator.accept(20L,MasterRunTypeEnum.HAND));coordinator.begin(run);coordinator.finish(run,true,5,"unknown");
        var dead=alertFor(run.getId());assertNull(dead.getRecipientId());assertEquals(3,dead.getStatus());assertEquals("NO_RECIPIENT",dead.getLastError());
        alertService.deliver(dead.getId());verifyNoInteractions(notifyClient);
    }

    @Test void creatorFallbackAndInvalidCreatorNeverDefaultToAdministrator() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET creator='8' WHERE id=10");
        assertEquals(8L,alertFor(unknown().getId()).getRecipientId());
        clearTenantContext();sql("UPDATE dh_governance_master_distribute SET creator='invalid' WHERE id=20");
        var run=coordinator.claim(coordinator.accept(20L,MasterRunTypeEnum.HAND));coordinator.begin(run);coordinator.finish(run,true,5,"unknown");
        assertNull(alertFor(run.getId()).getRecipientId());assertEquals(3,alertFor(run.getId()).getStatus());
    }

    @Test void alertInsertFailureRollsBackUnknownAndCrossNodeCancellation() throws Exception {
        var run=coordinator.claim(coordinator.release(10L,9L));coordinator.begin(run);
        sql("ALTER TABLE dh_governance_master_distribute_alert ADD CONSTRAINT deny_alert CHECK(false)");
        assertThrows(RuntimeException.class,()->coordinator.finish(run,true,5,"unknown"));assertEquals(2,runs.selectById(run.getId()).getRunStatus());
        assertThrows(RuntimeException.class,()->node().cancel(10L));assertEquals(1,tasks.selectById(10L).getStatus());assertEquals(2,runs.selectById(run.getId()).getRunStatus());
        sql("ALTER TABLE dh_governance_master_distribute_alert DROP CONSTRAINT deny_alert");
        node().cancel(10L);assertEquals(5,runs.selectById(run.getId()).getRunStatus());assertEquals(9L,alertFor(run.getId()).getRecipientId());
        assertFalse(coordinator.finish(run,true,3,null));assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
    }

    @Test void recoveryAndExhaustionCannotCommitWithoutAlert() throws Exception {
        var run=claimed();coordinator.begin(run);expire(run);
        sql("ALTER TABLE dh_governance_master_distribute_alert ADD CONSTRAINT deny_alert CHECK(false)");
        assertThrows(RuntimeException.class,()->node().claim(run));assertEquals(2,runs.selectById(run.getId()).getRunStatus());
        sql("ALTER TABLE dh_governance_master_distribute_alert DROP CONSTRAINT deny_alert");
        assertNull(node().claim(run));assertEquals("UNKNOWN",alertFor(run.getId()).getEventType());
        clearTenantContext();var pending=coordinator.accept(20L,MasterRunTypeEnum.HAND);
        for(int i=0;i<5;i++){var current=coordinator.claim(pending);assertNotNull(current);expire(current);}
        sql("ALTER TABLE dh_governance_master_distribute_alert ADD CONSTRAINT deny_exhausted CHECK(event_type<>'DISPATCH_EXHAUSTED')");
        assertThrows(RuntimeException.class,()->coordinator.claim(pending));assertEquals(1,runs.selectById(pending.getId()).getRunStatus());
        sql("ALTER TABLE dh_governance_master_distribute_alert DROP CONSTRAINT deny_exhausted");
        assertNull(coordinator.claim(pending));assertEquals("DISPATCH_EXHAUSTED",alertFor(pending.getId()).getEventType());
    }

    @Test void successKnownFailureAndBeforeSendCancelDoNotEmitCriticalAlerts() throws Exception {
        var success=claimed();coordinator.begin(success);coordinator.finish(success,true,3,null);
        var failed=claimed();coordinator.finish(failed,false,4,"validation");
        var pending=coordinator.release(10L,9L);coordinator.cancel(10L);
        for(var r:List.of(success,failed,pending))coordinator.reconcileAlert(r);
        assertTrue(alerts.missingTerminal().isEmpty());assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
    }

    @Test void enqueueAndDeliveryRequireCorrectTransactionBoundaryWithoutTenantContext() throws Exception {
        var row=readyAlert();var task=tasks.selectById(10L);
        assertThrows(IllegalStateException.class,()->alertService.enqueue(task,row.getRunId(),"UNKNOWN"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThrows(IllegalStateException.class,()->tx.execute(status->alertService.deliver(row.getId())));
        when(notifyClient.sendSingleMessageToAdmin(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(7L));
        TenantContextHolder.clear();
        assertTrue(alertService.deliver(row.getId()));
        verify(notifyClient).sendSingleMessageToAdmin(any());
    }

    @Test void historicalBackfillIsSelectiveAndOrphanUnknownBecomesDeadLetter() throws Exception {
        sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time,run_protocol,attempts,error_info) VALUES(901,10,100,2,5,clock_timestamp(),0,0,'legacy'),(902,10,100,2,4,clock_timestamp(),1,5,'发送前领取次数耗尽，未执行发送'),(903,10,100,2,4,clock_timestamp(),1,5,'validation'),(904,999,100,2,5,clock_timestamp(),0,0,'orphan'),(905,10,100,2,5,clock_timestamp(),0,0,'deleted')");
        sql("UPDATE dh_governance_master_distribute_log SET deleted=1 WHERE id=905");
        assertEquals(3,alerts.missingTerminal().size());for(var r:alerts.missingTerminal())coordinator.reconcileAlert(r);
        assertEquals(3,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));assertTrue(alerts.missingTerminal().isEmpty());
        assertEquals("NO_RECIPIENT",alertFor(904L).getLastError());
        sql("UPDATE dh_governance_master_distribute_alert SET deleted=1 WHERE run_id=901");
        coordinator.reconcileAlert(runs.selectById(901L));assertTrue(alerts.missingTerminal().isEmpty());
    }

    @Test void manualResolutionCannotOvertakeHistoricalAlertAndRollbackKeepsBothConsistent() throws Exception {
        var run=unknown();sql("DELETE FROM dh_governance_master_distribute_alert");
        sql("ALTER TABLE dh_governance_master_distribute_alert ADD CONSTRAINT deny_alert CHECK(false)");
        var req=review(run.getId());assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));assertEquals(5,runs.selectById(run.getId()).getRunStatus());
        sql("ALTER TABLE dh_governance_master_distribute_alert DROP CONSTRAINT deny_alert");
        coordinator.resolve(req,99L);assertEquals(6,runs.selectById(run.getId()).getRunStatus());assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
    }

    @Test void notificationRequiresPositiveReceiptAndRunsOutsideBusinessTransaction() throws Exception {
        var row=readyAlert();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());
            cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO request=call.getArgument(0);
            assertEquals(9L,request.getUserId());assertEquals(MasterDistributeAlertService.TEMPLATE,request.getTemplateCode());
            assertEquals(String.valueOf(row.getRunId()),request.getTemplateParams().get("runId"));
            assertEquals(String.valueOf(row.getId()),request.getTemplateParams().get("alertId"));assertEquals(4,request.getTemplateParams().size());
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(9007199254740993L);
        });
        assertTrue(alertService.deliver(row.getId()));assertTrue(alertNode().deliver(row.getId()));
        var sent=alerts.selectById(row.getId());assertEquals(2,sent.getStatus());assertEquals(9007199254740993L,sent.getNotifyMessageId());assertNull(sent.getLeaseToken());
        assertEquals(5,runs.selectById(row.getRunId()).getRunStatus());verify(notifyClient,times(1)).sendSingleMessageToAdmin(any());
    }

    @Test void emptyBusinessErrorAndLostReceiptsBackOffThenBecomeDeadWithoutBusinessRetry() throws Exception {
        var row=readyAlert();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenReturn(null,
                cn.iocoder.yudao.framework.common.pojo.CommonResult.success(null),
                cn.iocoder.yudao.framework.common.pojo.CommonResult.success(0L),
                cn.iocoder.yudao.framework.common.pojo.CommonResult.error(500,"fixture-secret-not-for-log"))
                .thenThrow(new IllegalStateException("fixture-secret-not-for-log"));
        for(int i=0;i<5;i++){
            assertFalse(alertService.deliver(row.getId()));var failed=alerts.selectById(row.getId());assertEquals(i+1,failed.getAttempts());
            assertEquals(i==4?3:0,failed.getStatus());assertEquals(i<3?"MISSING_RECEIPT":i==3?"REMOTE_REJECTED":"DELIVERY_UNCONFIRMED",failed.getLastError());
            assertTrue(alerts.candidates().isEmpty());alertDue(row.getId());
        }
        alertService.deliver(row.getId());verify(notifyClient,times(5)).sendSingleMessageToAdmin(any());
        assertEquals(5,runs.selectById(row.getRunId()).getRunStatus());assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_log"));
    }

    @Test void concurrentNotificationWorkersShareOneLease() throws Exception {
        var row=readyAlert();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(77L);});
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->alertNode().deliver(row.getId()));
            try {assertTrue(entered.await(10,TimeUnit.SECONDS));assertTrue(alertNode().deliver(row.getId()));}
            finally {release.countDown();}
            assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        verify(notifyClient,times(1)).sendSingleMessageToAdmin(any());assertEquals(1,alerts.selectById(row.getId()).getAttempts());
    }

    @Test void expiredAlertTokensCannotFinishOrOverwriteNewLease() throws Exception {
        var row=readyAlert();assertEquals(1,alerts.claim(row.getId(),"old"));alertDue(row.getId());
        assertEquals(0,alerts.delivered(row.getId(),"old",7L));assertEquals(0,alerts.failed(row.getId(),"old","error"));
        assertEquals(1,alerts.claim(row.getId(),"new"));assertEquals(0,alerts.delivered(row.getId(),"old",7L));
        assertEquals(0,alerts.delivered(9999L,"new",7L));assertEquals(1,alerts.delivered(row.getId(),"new",8L));
        assertEquals(8L,alerts.selectById(row.getId()).getNotifyMessageId());
    }

    @Test void fiveAbandonedClaimsBecomeDeadWithoutSixthRpc() throws Exception {
        var row=readyAlert();for(int i=0;i<5;i++){assertEquals(1,alerts.claim(row.getId(),"lease"+i));alertDue(row.getId());}
        assertTrue(alertService.deliver(row.getId()));assertEquals(3,alerts.selectById(row.getId()).getStatus());
        assertEquals("ATTEMPTS_EXHAUSTED",alerts.selectById(row.getId()).getLastError());verifyNoInteractions(notifyClient);
    }

    @Test void notificationAckLossMayDuplicateNotificationButKeepsStableCorrelationAndUnknown() throws Exception {
        var row=readyAlert();var calls=new AtomicInteger();
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call->{
            cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO request=call.getArgument(0);
            assertEquals(String.valueOf(row.getId()),request.getTemplateParams().get("alertId"));
            if(calls.incrementAndGet()==1)throw new IllegalStateException("ack lost after remote commit");
            return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(8L);
        });
        assertFalse(alertService.deliver(row.getId()));alertDue(row.getId());assertTrue(alertNode().deliver(row.getId()));
        assertEquals(2,calls.get());assertEquals(5,runs.selectById(row.getRunId()).getRunStatus());assertNull(accepted());
    }

    @Test void deadLetterDispositionIsAuditedIdempotentAndNeverResolvesBusinessUnknown() throws Exception {
        var row=readyAlert();sql("UPDATE dh_governance_master_distribute_alert SET status=3,attempts=5,last_error='ATTEMPTS_EXHAUSTED' WHERE id="+row.getId());
        alertService.dispose(row.getId(),"RETRY","CASE-1","xxl-job:7:77");
        var current=alerts.selectById(row.getId());assertEquals(0,current.getStatus());assertEquals(0,current.getAttempts());
        assertEquals(1,alerts.claim(row.getId(),"held"));alertService.dispose(row.getId(),"RETRY","CASE-1","xxl-job:7:77");
        assertEquals("held",alerts.selectById(row.getId()).getLeaseToken());
        assertThrows(IllegalStateException.class,()->alertService.dispose(row.getId(),"RETRY","changed","xxl-job:7:77"));
        assertThrows(IllegalStateException.class,()->alertService.dispose(row.getId(),"ACKNOWLEDGE","CASE-1","xxl-job:7:78"));
        assertThrows(IllegalArgumentException.class,()->alertService.dispose(9999L,"RETRY","CASE-1","xxl-job:7:77"));
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert_disposition"));assertEquals(5,runs.selectById(row.getRunId()).getRunStatus());
    }

    @Test void missingRecipientCanOnlyBeAcknowledgedAndAuditFailureRollsBackDisposition() throws Exception {
        var row=alertFor(unknown().getId());
        assertThrows(IllegalStateException.class,()->alertService.dispose(row.getId(),"RETRY","CASE-1","xxl-job:7:77"));
        assertThrows(IllegalArgumentException.class,()->alertService.dispose(row.getId(),"ACKNOWLEDGE"," ","xxl-job:7:77"));
        sql("ALTER TABLE dh_governance_master_distribute_alert_disposition ADD CONSTRAINT deny_audit CHECK(false)");
        assertThrows(RuntimeException.class,()->alertService.dispose(row.getId(),"ACKNOWLEDGE","CASE-1","xxl-job:7:77"));assertEquals(3,alerts.selectById(row.getId()).getStatus());
        sql("ALTER TABLE dh_governance_master_distribute_alert_disposition DROP CONSTRAINT deny_audit");
        alertService.dispose(row.getId(),"ACKNOWLEDGE","CASE-1","xxl-job:7:77");alertService.dispose(row.getId(),"ACKNOWLEDGE","CASE-1","xxl-job:7:77");
        assertEquals(4,alerts.selectById(row.getId()).getStatus());assertEquals(5,runs.selectById(row.getRunId()).getRunStatus());assertNull(accepted());
    }

    @Test void cleanupRetainsLogsDeadPendingAndAcknowledgedAudit() throws Exception {
        var row=readyAlert();sql("UPDATE dh_governance_master_distribute_alert SET status=2,handled_at=clock_timestamp()-interval '91 days' WHERE id="+row.getId());
        assertEquals(0,alerts.cleanDelivered());sql("UPDATE dh_governance_master_distribute_log SET deleted=1 WHERE id="+row.getRunId());
        assertEquals(0,alerts.cleanDelivered());sql("DELETE FROM dh_governance_master_distribute_log WHERE id="+row.getRunId());
        sql("INSERT INTO dh_governance_master_distribute_alert(task_id,run_id,event_type,task_name,status,handled_at) VALUES(10,901,'UNKNOWN','test',0,clock_timestamp()-interval '91 days'),(10,902,'UNKNOWN','test',3,clock_timestamp()-interval '91 days'),(10,903,'UNKNOWN','test',4,clock_timestamp()-interval '91 days'),(10,904,'UNKNOWN','test',2,clock_timestamp()),(20,905,'UNKNOWN','test',2,clock_timestamp()-interval '91 days')");
        alerts.audit("xxl-job:7:77",row.getId(),"RETRY","CASE-1");assertEquals(1,alerts.cleanDelivered());
        sql("DELETE FROM dh_governance_master_distribute_alert_disposition WHERE alert_id="+row.getId());
        assertEquals(1,alerts.cleanDelivered());assertEquals(4,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
    }

    @Test void alertMigrationReplaysWithoutChangingLeaseOrAudit() throws Exception {
        var row=readyAlert();assertEquals(1,alerts.claim(row.getId(),"held"));
        alerts.audit("xxl-job:7:77",row.getId(),"RETRY","fixture-audit");upgrade();upgrade();
        assertEquals("held",alerts.selectById(row.getId()).getLeaseToken());assertEquals(1,alerts.selectById(row.getId()).getAttempts());
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert_disposition"));
        assertThrows(SQLException.class,()->sql("INSERT INTO dh_governance_master_distribute_alert(task_id,run_id,event_type,task_name) VALUES(10," + row.getRunId() + ",'UNKNOWN','duplicate')"));
        assertEquals(0,number("SELECT count(*) FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='dh_governance_master_distribute_alert' AND column_name='tenant_id'"));
    }

    @Test void baselineAlertStructureIndependentlyMatchesMigration() throws Exception {
        var expectedColumns=columnDefinitions(schema,"dh_governance_master_distribute_alert");
        var expectedAudit=columnDefinitions(schema,"dh_governance_master_distribute_alert_disposition");
        var expectedIndexes=alertIndexes();
        sql("DROP TABLE dh_governance_master_distribute_alert_disposition");sql("DROP TABLE dh_governance_master_distribute_alert");
        sql("DROP INDEX idx_master_distribute_alert_missing");
        sql("ALTER TABLE dh_governance_master_distribute_log ADD COLUMN tenant_id bigint DEFAULT 1");
        var baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int start=baseline.indexOf("-- 主数据派发关键终态告警账本，已包含增量0063"),end=baseline.indexOf("-- ----------------------------",start);
        assertTrue(start>0&&end>start);sql(baseline.substring(start,end).replace("\"datahub-center\"",schema));
        upgrade();assertEquals(expectedColumns,columnDefinitions(schema,"dh_governance_master_distribute_alert"));
        assertEquals(expectedAudit,columnDefinitions(schema,"dh_governance_master_distribute_alert_disposition"));assertEquals(expectedIndexes,alertIndexes());
        upgrade();assertEquals(expectedIndexes,alertIndexes());assertEquals(3,alertFor(unknown().getId()).getStatus());
    }
    private List<String> alertIndexes() throws Exception {
        var result=new ArrayList<String>();
        try(var s=control.prepareStatement("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND (tablename IN ('dh_governance_master_distribute_alert','dh_governance_master_distribute_alert_disposition') OR indexname='idx_master_distribute_alert_missing') ORDER BY indexname")){
            s.setString(1,schema);try(var rs=s.executeQuery()){while(rs.next())result.add(rs.getString(1));}
        }assertEquals(8,result.size());return result;
    }

    @Test void historyDeliveryAndCleanupQueriesHaveFiniteBatchLimits() throws Exception {
        sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time) SELECT n,n,100,2,5,clock_timestamp() FROM generate_series(1001,1120) n");
        assertEquals(100,alerts.missingTerminal().size());
        sql("INSERT INTO dh_governance_master_distribute_alert(task_id,run_id,event_type,task_name) SELECT n,n,'UNKNOWN','test'  FROM generate_series(2001,2120) n");
        assertEquals(20,alerts.candidates().size());
        sql("UPDATE dh_governance_master_distribute_alert SET status=2,handled_at=clock_timestamp()-interval '91 days'");
        assertEquals(100,alerts.cleanDelivered());assertEquals(20,alerts.cleanDelivered());
    }

    @Test void actualAlertJobBackfillsAndDeliversEachTaskOnceWithoutChangingUnknown() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET creator='8' WHERE id=10");
        sql("UPDATE dh_governance_master_distribute SET creator='9' WHERE id=20");
        sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time) VALUES(901,10,100,2,5,clock_timestamp()),(902,20,200,2,5,clock_timestamp())");
        var job=new cn.iocoder.yudao.module.governance.job.master.MasterDistributeAlertJob();
        ReflectionTestUtils.setField(job,"alerts",alerts);ReflectionTestUtils.setField(job,"service",alertService);ReflectionTestUtils.setField(job,"coordinator",coordinator);
        var context=new com.xxl.job.core.context.XxlJobContext(7,"","77.log",0,1);
        com.xxl.job.core.context.XxlJobContext.setXxlJobContext(context);
        when(notifyClient.sendSingleMessageToAdmin(any())).thenAnswer(call->{
            cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO request=call.getArgument(0);
            assertFalse(TenantContextHolder.isIgnore());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(List.of(8L,9L).contains(request.getUserId()));return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(7L);
        });
        try {
            clearTenantContext();TenantContextHolder.clear();job.execute();job.execute();
            assertEquals(200,context.getHandleCode());assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());
            assertEquals(2,number("SELECT count(*) FROM dh_governance_master_distribute_alert WHERE status=2 AND attempts=1"));
            assertEquals(2,number("SELECT count(*) FROM dh_governance_master_distribute_log WHERE run_status=5"));
            verify(notifyClient,times(2)).sendSingleMessageToAdmin(any());
        } finally {com.xxl.job.core.context.XxlJobContext.setXxlJobContext(null);}
    }

    private void seedAlertTemplate() throws Exception {
        sql("CREATE SEQUENCE system_notify_template_seq");
        sql("CREATE TABLE system_notify_template(id bigint PRIMARY KEY DEFAULT nextval('system_notify_template_seq'),name text,code text,type int,nickname text,content text,params text,status int,remark text,creator text,updater text,deleted int DEFAULT 0)");
    }
    private void migrateAlertTemplate() throws Exception {
        sql(baselineDoBlock("$master_distribute_alert_template$"));
    }

    @Test void alertTemplateMigrationAndBaselineReplayWithoutOverwritingConfiguration() throws Exception {
        seedAlertTemplate();sql("INSERT INTO system_notify_template(id,code) VALUES(1,'existing')");migrateAlertTemplate();migrateAlertTemplate();
        assertEquals(2,number("SELECT count(*) FROM system_notify_template"));
        assertEquals(2,number("SELECT id FROM system_notify_template WHERE code='governance_master_distribute_outcome'"));
        sql("DELETE FROM system_notify_template WHERE code='governance_master_distribute_outcome'");
        var baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));String marker="$master_distribute_alert_template$";
        int start=baseline.indexOf("DO "+marker),end=baseline.indexOf(marker+";",start+4);assertTrue(start>0&&end>start);
        sql(baseline.substring(start,end+marker.length()+1).replace("\"datahub-center\"",schema));migrateAlertTemplate();
        assertEquals(1,number("SELECT count(*) FROM system_notify_template WHERE code='governance_master_distribute_outcome' AND status=0 AND deleted=0"));
        sql("UPDATE system_notify_template SET status=1 WHERE code='governance_master_distribute_outcome'");
        assertThrows(SQLException.class,this::migrateAlertTemplate);sql("ROLLBACK");
        assertEquals(1,number("SELECT status FROM system_notify_template WHERE code='governance_master_distribute_outcome'"));
    }

    @Test void alertTemplateFailsClosedOnMissingStructureOrConflictingPayload() throws Exception {
        assertThrows(SQLException.class,this::migrateAlertTemplate);sql("ROLLBACK");seedAlertTemplate();
        sql("ALTER TABLE system_notify_template ALTER COLUMN id DROP DEFAULT");sql("DROP SEQUENCE system_notify_template_seq");
        assertThrows(SQLException.class,this::migrateAlertTemplate);sql("ROLLBACK");assertEquals(0,number("SELECT count(*) FROM system_notify_template"));
        sql("CREATE SEQUENCE system_notify_template_seq");migrateAlertTemplate();
        sql("UPDATE system_notify_template SET content='operator-change' WHERE code='governance_master_distribute_outcome'");
        assertThrows(SQLException.class,this::migrateAlertTemplate);sql("ROLLBACK");
        assertEquals(1,number("SELECT count(*) FROM system_notify_template WHERE content='operator-change'"));
    }

    @Test void reviewPreservesEvidenceAndStopsScheduleWithoutResending() throws Exception {
        due();sql("UPDATE dh_governance_master_distribute SET release_user_id=9,release_time=clock_timestamp()-interval '1 hour' WHERE id=10");
        var run=unknown();var snapshot=tasks.selectById(10L);var req=review(run.getId());
        var result=coordinator.resolve(req,99L);
        assertEquals(6,result.getRunStatus());assertEquals("原始未知错误",result.getErrorInfo());assertEquals(7L,result.getDataCount());
        assertEquals(run.getLeaseToken(),result.getLeaseToken());assertEquals(99L,result.getResolvedBy());
        assertNotNull(result.getResolvedAt());assertEquals(req.getRequestId(),result.getResolutionRequestId());
        var task=tasks.selectById(10L);assertEquals(0,task.getStatus());assertNull(task.getNextRunTime());
        assertEquals(snapshot.getReleaseTime(),task.getReleaseTime());assertEquals(snapshot.getReleaseUserId(),task.getReleaseUserId());
        assertEquals(snapshot.getScheduleGeneration()+1,task.getScheduleGeneration());assertNull(coordinator.scheduled(snapshot));
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_log"));assertEquals(0,coordinator.blockingState(10L));
        assertFalse(coordinator.finish(run,true,3,null));assertFalse(coordinator.begin(run));assertFalse(coordinator.heartbeat(run));
        assertNotNull(accepted()); // 单独授权的新动作，不由核查隐式派发。
    }

    @Test void exactReplayDoesNotStopALaterPublishedTaskOrRewriteAudit() throws Exception {
        var req=review(unknown().getId());var first=coordinator.resolve(req,99L);
        due();var current=tasks.selectById(10L);var replay=node().resolve(req,99L);
        assertEquals(first.getResolvedAt(),replay.getResolvedAt());assertEquals(current.getNextRunTime(),tasks.selectById(10L).getNextRunTime());
        assertEquals(current.getScheduleGeneration(),tasks.selectById(10L).getScheduleGeneration());assertEquals(1,tasks.selectById(10L).getStatus());
    }

    @Test void changedActorPayloadOrRequestCannotRewriteReceipt() throws Exception {
        var req=review(unknown().getId());coordinator.resolve(req,99L);
        assertThrows(RuntimeException.class,()->node().resolve(req,100L));
        req.setReason("改变原因");assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));req.setReason("核查全部批次");
        req.setOutputEvidenceRef("OTHER");assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));req.setOutputEvidenceRef("CASE-OUTPUT-1");
        req.setOutcome("COMPENSATED");assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));req.setOutcome("NO_EFFECT");
        req.setRequestId(UUID.randomUUID().toString());assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertEquals("NO_EFFECT",runs.selectById(req.getRecordId()).getResolutionOutcome());
    }

    @Test void liveLeaseAndMissingAttestationsCannotBeResolved() throws Exception {
        var run=claimed();coordinator.begin(run);coordinator.finish(run,true,5,"未知");var req=review(run.getId());
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));expire(run);
        req.setConfirmedStopped(false);assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));req.setConfirmedStopped(true);
        req.setOutputReviewed(null);assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));req.setOutputReviewed(true);
        req.setOutcome("SUCCESS");assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));req.setOutcome("NO_EFFECT");
        req.setStoppedEvidenceRef(" ");assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());assertEquals(0,tasks.selectById(10L).getScheduleGeneration());
    }

    @Test void reviewRejectsMissingTaskWithoutChangingUnknownRun() throws Exception {
        var req=review(unknown().getId()); TenantContextHolder.clear();
        sql("UPDATE dh_governance_master_distribute SET deleted=1 WHERE id=10");
        assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
        assertEquals(5,runs.selectById(req.getRecordId()).getRunStatus());
    }

    @Test void reviewRequiresUnknownAndNoOtherActiveRun() throws Exception {
        var pending=accepted();assertThrows(RuntimeException.class,()->coordinator.resolve(review(pending.getId()),99L));
        var sending=coordinator.claim(pending);coordinator.begin(sending);assertThrows(RuntimeException.class,()->coordinator.resolve(review(sending.getId()),99L));
        coordinator.finish(sending,true,5,"未知");expire(sending);
        sql("INSERT INTO dh_governance_master_distribute_log(id,distribute_id,master_model_id,run_type,run_status,start_time) VALUES(901,10,100,2,2,clock_timestamp())");
        assertThrows(RuntimeException.class,()->coordinator.resolve(review(sending.getId()),99L));
        sql("UPDATE dh_governance_master_distribute_log SET run_status=5 WHERE id=901");
        var req=review(sending.getId());req.setOutcome("COMPENSATED");coordinator.resolve(req,99L);assertEquals(5,coordinator.blockingState(10L));assertNull(accepted());
        coordinator.resolve(review(901L),99L);assertEquals(0,coordinator.blockingState(10L));
    }

    @Test void simultaneousSameCommandHasSingleMutation() throws Exception {
        var req=review(unknown().getId());var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<MasterDistributeLogDO> call=()->{gate.await();return node().resolve(req,99L);};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS).getResolvedAt(),b.get(10,TimeUnit.SECONDS).getResolvedAt());
        }assertEquals(1,tasks.selectById(10L).getScheduleGeneration());
    }

    @Test void receiptAndUnpublishRollBackTogetherOnTaskFailure() throws Exception {
        due();var run=unknown();var snapshot=tasks.selectById(10L);
        sql("CREATE FUNCTION reject_review() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_review BEFORE UPDATE ON dh_governance_master_distribute FOR EACH ROW EXECUTE FUNCTION reject_review()");
        assertThrows(RuntimeException.class,()->coordinator.resolve(review(run.getId()),99L));
        assertNull(runs.selectById(run.getId()).getResolutionRequestId());assertEquals(5,runs.selectById(run.getId()).getRunStatus());
        assertEquals(snapshot.getNextRunTime(),tasks.selectById(10L).getNextRunTime());assertEquals(1,tasks.selectById(10L).getStatus());
    }

    @Test void commitAckLossCanReplaySameReceipt() throws Exception {
        var req=review(unknown().getId());var uncertain=node();
        var lostAck=new org.springframework.transaction.PlatformTransactionManager(){
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(org.springframework.transaction.TransactionStatus s){manager.commit(s);throw new org.springframework.transaction.TransactionSystemException("lost ack");}
            public void rollback(org.springframework.transaction.TransactionStatus s){manager.rollback(s);}
        };
        ReflectionTestUtils.setField(uncertain,"transactionManager",lostAck);
        assertThrows(RuntimeException.class,()->uncertain.resolve(req,99L));assertEquals(6,coordinator.resolve(req,99L).getRunStatus());
        assertEquals(1,tasks.selectById(10L).getScheduleGeneration());
    }

    @Test void requestUniquenessIncludesDeletedAuditAcrossAllTasks() throws Exception {
        var req=review(unknown().getId());coordinator.resolve(req,99L);sql("UPDATE dh_governance_master_distribute_log SET deleted=1 WHERE id="+req.getRecordId());
        var next=unknown();req.setRecordId(next.getId());assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));assertEquals(5,runs.selectById(next.getId()).getRunStatus());
        assertThrows(SQLException.class,()->sql("UPDATE dh_governance_master_distribute_log SET resolution_request_id='"+req.getRequestId()+"' WHERE id="+next.getId()));
        clearTenantContext();var foreign=coordinator.claim(coordinator.accept(20L,MasterRunTypeEnum.HAND));coordinator.begin(foreign);coordinator.finish(foreign,true,5,"未知");expire(foreign);
        req.setRecordId(foreign.getId());assertThrows(RuntimeException.class,()->coordinator.resolve(req,99L));
    }

    @Test void singleTenantMigrationKeepsReviewStructureAndAudit() throws Exception {
        var run=unknown();var signature=columnDefinitions(schema,"dh_governance_master_distribute_log");
        upgrade();upgrade();assertEquals(signature,columnDefinitions(schema,"dh_governance_master_distribute_log"));
        assertEquals(5,runs.selectById(run.getId()).getRunStatus());var result=coordinator.resolve(review(run.getId()),99L);
        upgrade();assertEquals(result.getResolvedAt(),runs.selectById(run.getId()).getResolvedAt());
    }

    private void seedReviewMenu() throws Exception {
        sql("CREATE SEQUENCE system_menu_seq");
        sql("CREATE TABLE system_menu(id bigint PRIMARY KEY DEFAULT nextval('system_menu_seq'),name text,permission text,type int,sort int,parent_id bigint,path text,icon text,component text,component_name text,status int,visible boolean,keep_alive boolean,always_show boolean,creator text,updater text,create_time timestamp,update_time timestamp,deleted int DEFAULT 0)");
        sql("INSERT INTO system_menu(id,type,status,deleted) VALUES(1,2,0,0)");
        sql("INSERT INTO system_menu(id,type,parent_id,status,deleted,permission) VALUES(2,3,1,0,0,'governance:master-distribute:run')");
        sql("CREATE TABLE system_role_menu(role_id bigint,menu_id bigint)");sql("INSERT INTO system_role_menu VALUES(1,2)");
    }
    private void migrateReviewMenu() throws Exception {sql(baselineDoBlock("$master_resolution_permission$"));}

    @Test void permissionMigrationAndIndependentBaselineDoNotGrantRolesAndReplay() throws Exception {
        seedReviewMenu();migrateReviewMenu();migrateReviewMenu();assertEquals(3,number("SELECT count(*) FROM system_menu"));assertEquals(1,number("SELECT count(*) FROM system_role_menu"));
        var baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int start=baseline.indexOf("DO $master_resolution_permission$"),end=baseline.indexOf("$master_resolution_permission$;",start+4);
        assertTrue(start>0 && end>start);
        sql("DELETE FROM system_menu WHERE permission='governance:master-distribute:resolve'");
        sql(baseline.substring(start,end+"$master_resolution_permission$;".length()));
        assertEquals(1,number("SELECT count(*) FROM system_menu WHERE permission='governance:master-distribute:resolve' AND parent_id=1 AND status=0 AND deleted=0 AND type=3"));
        assertEquals(1,number("SELECT count(*) FROM system_role_menu"));
    }

    @Test void permissionMigrationFailsClosedOnMissingTablesParentOrDisabledPermission() throws Exception {
        assertThrows(SQLException.class,this::migrateReviewMenu);sql("ROLLBACK");seedReviewMenu();
        sql("UPDATE system_menu SET deleted=1 WHERE id=1");assertThrows(SQLException.class,this::migrateReviewMenu);sql("ROLLBACK");
        assertEquals(0,number("SELECT count(*) FROM system_menu WHERE permission='governance:master-distribute:resolve'"));
        sql("UPDATE system_menu SET deleted=0 WHERE id=1");migrateReviewMenu();
        sql("UPDATE system_menu SET status=1 WHERE permission='governance:master-distribute:resolve'");
        assertThrows(SQLException.class,this::migrateReviewMenu);sql("ROLLBACK");
        assertEquals(1,number("SELECT status FROM system_menu WHERE permission='governance:master-distribute:resolve'"));
    }

    @Test void incrementalScopeMappingAndConfirmedCheckpointsAvoidResendingUnchangedRows() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var payloads=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/accept",exchange->{payloads.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        try {
            var executor=httpExecutor(new MasterDistributeExecutor(),"http://127.0.0.1:"+server.getAddress().getPort()+"/accept");
            sql("ALTER TABLE http_rows ADD active int DEFAULT 1, ADD amount numeric(38,18) DEFAULT 12345678901234567890.123456789012345678");
            sql("INSERT INTO http_rows(id,active) VALUES(3,0)");
            var columns=(MasterColumnMapper)ReflectionTestUtils.getField(executor,"columnMapper");
            when(columns.selectListByModelId(100L)).thenReturn(List.of(MasterColumnDO.builder().name("id").pk(1).build(),
                    MasterColumnDO.builder().name("active").build(),MasterColumnDO.builder().name("amount").build()));
            var api=cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(tasks.selectById(10L).getDistributeJson(),cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.class);
            api.getApi().setPushMode("INCREMENTAL");api.getApi().setPushScope("ENABLED");api.getApi().setStatusField("active");
            var mapping=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.FieldMapping();mapping.setSourceField("amount");mapping.setTargetField("exact_amount");api.getApi().setMappings(List.of(mapping));
            var task=tasks.selectById(10L);task.setDistributeJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(api));tasks.updateById(task);
            coordinator.updateApiStatus(10L,1,8L);assertEquals(0,payloads.size());assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_log"));
            var first=coordinator.claim(coordinator.acceptApi(10L));executor.execute(first);
            assertEquals(3,runs.selectById(first.getId()).getRunStatus());assertEquals(2L,runs.selectById(first.getId()).getSuccessCount());
            assertEquals(2,payloads.size());assertTrue(payloads.getFirst().contains("\"12345678901234567890.123456789012345678\""));
            assertEquals(2,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
            var second=coordinator.claim(coordinator.acceptApi(10L));executor.execute(second);assertEquals(3,runs.selectById(second.getId()).getRunStatus());
            assertEquals(0L,runs.selectById(second.getId()).getTotalCount());assertEquals(2,payloads.size());
            sql("UPDATE http_rows SET amount=2 WHERE id=1");var third=coordinator.claim(coordinator.acceptApi(10L));executor.execute(third);
            assertEquals(1L,runs.selectById(third.getId()).getSuccessCount());assertEquals(3,payloads.size());
            var summary=runs.summaries(List.of(10L)).getFirst();assertEquals(3L,summary.getTotalCount());assertEquals(3L,summary.getSuccessCount());
        } finally {server.stop(0);}
    }

    @Test void staleWorkerCannotAdvanceCheckpointAndReceiptRollbackIsAtomic() throws Exception {
        var run=claimed();assertTrue(coordinator.begin(run));run.setTotalCount(1L);run.setSuccessCount(1L);
        var receipt=new MasterDistributeCheckpointMapper.Checkpoint("a".repeat(64),"b".repeat(64));
        assertTrue(coordinator.progress(run,List.of(receipt)));assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
        sql("UPDATE dh_governance_master_distribute_log SET lease_token='replacement' WHERE id="+run.getId());
        assertFalse(coordinator.progress(run,List.of(new MasterDistributeCheckpointMapper.Checkpoint("c".repeat(64),"d".repeat(64)))));
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
        sql("UPDATE dh_governance_master_distribute_log SET lease_token='"+run.getLeaseToken()+"' WHERE id="+run.getId());
        run.setSuccessCount(2L);assertThrows(RuntimeException.class,()->coordinator.progress(run,List.of(new MasterDistributeCheckpointMapper.Checkpoint("invalid","hash"))));
        assertEquals(1L,runs.selectById(run.getId()).getSuccessCount());
    }

    @Test void safeRetryUsesSameIntentAndConfiguredIntervalThenStopsWithoutUnknown() throws Exception {
        sql("UPDATE dh_governance_master_distribute SET status=1,distribute_json='{\"api\":{\"url\":\"http://consumer.invalid\",\"retryCount\":1,\"retryIntervalMinutes\":7,\"exhaustedAction\":\"STOP\"}}' WHERE id=10");
        var run=coordinator.claim(coordinator.acceptApi(10L));assertTrue(coordinator.finish(run,false,4,"safe failure"));
        var pending=runs.selectById(run.getId());assertEquals(1,pending.getRunStatus());assertNull(pending.getLeaseToken());
        assertTrue(pending.getAvailableAt().isAfter(runs.databaseNow().plusMinutes(6)));assertNull(coordinator.claim(pending));
        sql("UPDATE dh_governance_master_distribute_log SET available_at=clock_timestamp()-interval '1 second' WHERE id="+run.getId());
        var retry=coordinator.claim(pending);assertEquals(run.getId(),retry.getId());assertEquals(2,retry.getAttempts());
        assertTrue(coordinator.finish(retry,false,4,"safe failure"));assertEquals(4,runs.selectById(run.getId()).getRunStatus());assertEquals(0,tasks.selectById(10L).getStatus());
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_log"));assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,3,10})
    void manualExhaustionPersistsExactlyOneAlertForEveryConfiguredRetryBudget(int budget) throws Exception {
        sql("UPDATE dh_governance_master_distribute SET status=1,creator='8',distribute_json='{\"api\":{\"url\":\"http://consumer.invalid\",\"retryCount\":"+budget+",\"exhaustedAction\":\"MANUAL\"}}' WHERE id=10");
        var pending=coordinator.acceptApi(10L);
        for(int i=0;i<=budget;i++) {
            var run=coordinator.claim(pending);assertNotNull(run);assertTrue(coordinator.finish(run,false,4,"safe failure"));
            if(i<budget)sql("UPDATE dh_governance_master_distribute_log SET available_at=clock_timestamp()-interval '1 second' WHERE id="+run.getId());
        }
        assertEquals(4,runs.selectById(pending.getId()).getRunStatus());assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
        coordinator.reconcileAlert(runs.selectById(pending.getId()));assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_alert"));
        assertTrue(alerts.missingTerminal().isEmpty());
    }

    @Test void resolvingCompensatedRunInvalidatesOnlyItsConfirmedPrefix() throws Exception {
        var successful=claimed();coordinator.begin(successful);successful.setTotalCount(1L);successful.setSuccessCount(1L);
        coordinator.progress(successful,List.of(new MasterDistributeCheckpointMapper.Checkpoint("a".repeat(64),"b".repeat(64))));coordinator.finish(successful,true,3,null);
        var unknown=claimed();coordinator.begin(unknown);unknown.setTotalCount(2L);unknown.setSuccessCount(1L);unknown.setUnknownCount(1L);
        coordinator.progress(unknown,List.of(new MasterDistributeCheckpointMapper.Checkpoint("c".repeat(64),"d".repeat(64))));coordinator.finish(unknown,true,5,"unknown");
        expire(unknown);var request=review(unknown.getId());request.setOutcome("COMPENSATED");coordinator.resolve(request,99L);
        assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
        assertEquals(successful.getId(),number("SELECT run_id FROM dh_governance_master_distribute_checkpoint"));
        assertEquals(0,tasks.selectById(10L).getStatus());
    }

    @Test void encryptedSecretsRoundTripThroughAllTaskMappersWithoutPublicConfigLeak() throws Exception {
        var handler=cn.iocoder.yudao.framework.mybatis.core.type.EncryptTypeHandler.class;
        var previous=ReflectionTestUtils.getField(handler,"aes");ReflectionTestUtils.setField(handler,"aes",cn.hutool.crypto.SecureUtil.aes());
        try {
            var api=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.ApiConfig();api.setAuthType("TOKEN");api.setToken("fixture-secret");api.setUrl("https://consumer.invalid/receive");
            var task=tasks.selectById(10L);MasterDistributeApiPolicy.separateSecrets(task,api);
            var config=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO();config.setApi(api);task.setDistributeJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(config));tasks.updateById(task);
            try(var statement=control.createStatement();var values=statement.executeQuery("SELECT request_secrets,distribute_json FROM dh_governance_master_distribute WHERE id=10")) {
                assertTrue(values.next());assertFalse(values.getString(1).contains("fixture-secret"));assertFalse(values.getString(2).contains("fixture-secret"));
            }
            assertTrue(tasks.selectById(10L).getRequestSecrets().contains("fixture-secret"));assertTrue(tasks.lockOwned(10L).getRequestSecrets().contains("fixture-secret"));
            assertFalse(tasks.selectById(10L).toString().contains("fixture-secret"));
            MasterDistributeApiPolicy.redact(tasks.selectById(10L),api);assertTrue(api.getTokenConfigured());assertNull(api.getToken());
            var oldIdentity=MasterDistributeApiPolicy.checkpointIdentity(tasks.selectById(10L));
            api.setToken("replacement-fixture");MasterDistributeApiPolicy.separateSecrets(task,api);task.setDescription("only metadata changed");
            assertEquals(oldIdentity,MasterDistributeApiPolicy.checkpointIdentity(task));
            var confirmed=claimed();coordinator.begin(confirmed);coordinator.progress(confirmed,List.of(new MasterDistributeCheckpointMapper.Checkpoint("a".repeat(64),"b".repeat(64))));coordinator.finish(confirmed,true,3,null);
            var service=new MasterDistributeServiceImpl();var policy=new MasterDistributeApiPolicy();var columns=mock(MasterColumnMapper.class);
            when(columns.selectListByModelId(100L)).thenReturn(List.of(MasterColumnDO.builder().name("id").pk(1).build()));ReflectionTestUtils.setField(policy,"columns",columns);
            var security=mock(cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService.class);when(security.hasPermission(anyString())).thenReturn(true);
            ReflectionTestUtils.setField(service,"coordinator",coordinator);ReflectionTestUtils.setField(service,"modelMapper",models);
            ReflectionTestUtils.setField(service,"distributeMapper",tasks);ReflectionTestUtils.setField(service,"checkpoints",checkpoints);
            ReflectionTestUtils.setField(service,"securityFrameworkService",security);ReflectionTestUtils.setField(service,"apiPolicy",policy);
            var request=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeSaveReqVO();request.setId(10L);request.setName("renamed");request.setMasterModelId(100L);request.setDistributeType(2);request.setTaskType(1);request.setConfig(config);
            service.updateDistribute(request);assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
            config.getApi().setToken("another-fixture");service.updateDistribute(request);assertEquals(1,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
            config.getApi().setUrl("https://other-consumer.invalid/receive");service.updateDistribute(request);assertEquals(0,number("SELECT count(*) FROM dh_governance_master_distribute_checkpoint"));
        } finally {ReflectionTestUtils.setField(handler,"aes",previous);}
    }

    @Test void actualJobContinuesOtherTaskAfterInvalidCronAndReportsFailure() throws Exception {
        due();sql("UPDATE dh_governance_master_distribute SET cron='bad' WHERE id=10");
        sql("UPDATE dh_governance_master_distribute SET status=1,task_type=2,cron='0 * * * * ?',next_run_time=clock_timestamp()-interval '1 minute' WHERE id=20");
        var executor=mock(MasterDistributeExecutor.class);var job=new MasterDistributeJob();
        ReflectionTestUtils.setField(job,"distributeMapper",tasks);ReflectionTestUtils.setField(job,"logMapper",runs);
        ReflectionTestUtils.setField(job,"distributeExecutor",executor);ReflectionTestUtils.setField(job,"coordinator",coordinator);
        var context=new com.xxl.job.core.context.XxlJobContext(1,"","11.log",0,1);
        com.xxl.job.core.context.XxlJobContext.setXxlJobContext(context);
        try {
            doAnswer(call->{assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());return null;}).when(executor).dispatch(any());
            clearTenantContext();TenantContextHolder.clear();job.execute();
            verify(executor).dispatch(argThat(run->run!=null && run.getDistributeId().equals(20L)));
            assertEquals(500,context.getHandleCode());assertNull(TenantContextHolder.getTenantId());assertFalse(TenantContextHolder.isIgnore());
        } finally {com.xxl.job.core.context.XxlJobContext.setXxlJobContext(null);}
    }
}
