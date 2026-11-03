package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 PostgreSQL 行锁、部分唯一索引与独立事务验证跨协调器排队和周期槽幂等。 */
class JobQueuePostgreSqlTest {
    PooledDataSource source;SqlSessionTemplate session;JobTaskMapper jobs;JobRecordMapper records;TransactionTemplate transaction;
    JobQueueCoordinator first,second;JobManagedRunService runtime;JobTaskDO job;String snapshot;
    final List<Long> extraJobs=new ArrayList<>();final AtomicInteger dispatched=new AtomicInteger();final Map<Long,String> observed=new ConcurrentHashMap<>();
    @BeforeEach void setup() {
        String url=System.getenv("OPS_TEST_JDBC_URL");assumeTrue(url!=null&&!url.isBlank(),"需要显式隔离 PostgreSQL 测试库");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z0-9_]+_test(?:\\?.*)?"));
        source=new PooledDataSource("org.postgresql.Driver",url,System.getenv().getOrDefault("OPS_TEST_JDBC_USER","postgres"),"");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setEnvironment(new Environment("durable-job-test",new SpringManagedTransactionFactory(),source));
        config.addMapper(JobTaskMapper.class);config.addMapper(JobRecordMapper.class);
        session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));jobs=session.getMapper(JobTaskMapper.class);records=session.getMapper(JobRecordMapper.class);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));runtime=mock(JobManagedRunService.class);
        first=coordinator();second=coordinator();job=new JobTaskDO();job.setCategoryId(1L);job.setRevision(0);job.setName("持久队列回归_"+UUID.randomUUID());job.setStatus(1);job.setScheduleStatus(0);job.setScheduleGeneration(0L);job.setStrategy("SERIAL_WAIT");job.setOperationState("IDLE");job.setFlowJson("{\"nodes\":[],\"edges\":[]}");job.setCreator("7");job.setUpdater("7");job.setCreateTime(LocalDateTime.now());job.setUpdateTime(LocalDateTime.now());jobs.insert(job);freeze();
        when(runtime.owned(any())).thenAnswer(call->((JobRecordDO)call.getArgument(0)).getDsInstanceId()!=null);
        doAnswer(call->{JobRecordDO record=call.getArgument(0);String state=observed.get(record.getId());if(state!=null)records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getStatus,state));return null;}).when(runtime).reconcile(any());
        when(runtime.executeAccepted(any(),anyString())).thenAnswer(call->{JobRecordDO record=call.getArgument(0);dispatched.incrementAndGet();records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getStatus,"RUNNING").set(JobRecordDO::getDsInstanceId,1000+dispatched.get()).set(JobRecordDO::getDsDefinitionCode,100000+record.getId()).set(JobRecordDO::getSubmissionStage,"BOUND"));return record.getId();});
    }
    JobQueueCoordinator coordinator(){var coordinator=new JobQueueCoordinator();ReflectionTestUtils.setField(coordinator,"jobs",jobs);ReflectionTestUtils.setField(coordinator,"records",records);ReflectionTestUtils.setField(coordinator,"runtime",runtime);ReflectionTestUtils.setField(coordinator,"transaction",transaction);return coordinator;}
    void freeze(){snapshot=new JSONObject().set("jobId",job.getId()).set("name",job.getName()).set("revision",job.getRevision()).set("strategy",job.getStrategy()).set("actor",7L).set("flowJson",job.getFlowJson()).set("bindings",List.of(Map.of("target",Map.of("project",100L)))).toString();}
    void strategy(String value){job.setStrategy(value);jobs.updateById(job);freeze();}
    RunReq request(String suffix){var request=new RunReq();request.setId(job.getId());request.setRevision(job.getRevision());request.setRequestId("queue_"+job.getId()+"_"+suffix);return request;}
    Long enqueue(JobQueueCoordinator coordinator,String suffix){return coordinator.enqueueManual(job,request(suffix),7L,snapshot);}
    List<JobRecordDO> runs(){return records.selectList(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,job.getId()).orderByAsc(JobRecordDO::getId));}
    @AfterEach void cleanup(){if(source==null)return;try{if(job!=null&&job.getId()!=null){try(var connection=source.getConnection();var sql=connection.createStatement()){extraJobs.add(job.getId());for(Long id:extraJobs){sql.executeUpdate("DELETE FROM \"datahub-center\".dh_dataforge_job_record WHERE job_id="+id);sql.executeUpdate("DELETE FROM \"datahub-center\".dh_dataforge_job WHERE id="+id);}}}}catch(Exception error){throw new RuntimeException(error);}finally{source.forceCloseAll();}}

    @Test void waitIsFifoAndOnlyConfirmedTerminalReleasesTheSlot(){Long a=enqueue(first,"a"),b=enqueue(second,"b");assertEquals(1,dispatched.get());assertEquals("QUEUED",records.selectById(b).getQueueState());first.maintain();assertEquals(1,dispatched.get());observed.put(a,"SUCCESS");second.maintain();assertEquals(2,dispatched.get());assertEquals("DONE",records.selectById(a).getQueueState());assertEquals("ACTIVE",records.selectById(b).getQueueState());}
    @Test void duplicateRequestAcrossCoordinatorsHasOneRecordAndOneTrigger() throws Exception {var pool=Executors.newFixedThreadPool(2);try{var barrier=new CyclicBarrier(2);var a=pool.submit(()->{barrier.await();return enqueue(first,"same");});var b=pool.submit(()->{barrier.await();return enqueue(second,"same");});assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,runs().size());assertEquals(1,dispatched.get());assertEquals(1L,records.selectCount(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,job.getId()).eq(JobRecordDO::getSerialSlot,true)));}finally{pool.shutdownNow();}}
    @Test void simultaneousDifferentWaitRequestsHaveOneActiveSlot() throws Exception {var pool=Executors.newFixedThreadPool(2);try{var barrier=new CyclicBarrier(2);var a=pool.submit(()->{barrier.await();return enqueue(first,"a");});var b=pool.submit(()->{barrier.await();return enqueue(second,"b");});assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(2,runs().size());assertEquals(1,dispatched.get());assertEquals(1,runs().stream().filter(r->"QUEUED".equals(r.getQueueState())).count());}finally{pool.shutdownNow();}}
    @Test void discardAuditsBusyWithoutAllocatingOrTriggeringAChild(){strategy("SERIAL_DISCARD");enqueue(first,"a");Long b=enqueue(second,"b");assertEquals("DISCARDED",records.selectById(b).getQueueState());assertEquals("STOPPED",records.selectById(b).getStatus());assertNull(records.selectById(b).getDsInstanceId());assertEquals(1,dispatched.get());}
    @Test void priorityKeepsNewestAndWaitsForActualExitRatherThanStopReceipt(){strategy("SERIAL_PRIORITY");Long a=enqueue(first,"a"),b=enqueue(second,"b"),c=enqueue(first,"c");assertEquals("SUPERSEDED",records.selectById(b).getQueueState());assertTrue(records.selectById(a).getStopRequested());verify(runtime,atLeastOnce()).stopForActor(argThat(record->record.getId().equals(a)&&record.getExecutionUserId().equals(7L)));assertEquals(1,dispatched.get());second.maintain();assertEquals(1,dispatched.get());observed.put(a,"STOPPED");first.maintain();assertEquals(2,dispatched.get());assertEquals("ACTIVE",records.selectById(c).getQueueState());}
    @Test void expiredUnconfirmedPreparationKeepsSlotAndCannotBlindlyTriggerAgain(){Long a=enqueue(first,"a");records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,a).set(JobRecordDO::getDsInstanceId,null).set(JobRecordDO::getDsDefinitionCode,null).set(JobRecordDO::getSubmissionStage,"PREPARING").set(JobRecordDO::getQueueState,"DISPATCHING").set(JobRecordDO::getLeaseUntil,LocalDateTime.now().minusMinutes(1)));Long b=enqueue(second,"b");first.maintain();assertEquals("UNKNOWN",records.selectById(a).getQueueState());assertTrue(records.selectById(a).getSerialSlot());assertEquals("QUEUED",records.selectById(b).getQueueState());assertEquals(1,dispatched.get());}
    @Test void cronSlotIsUniqueAcrossRestartedCoordinatorsAndCursorCommitsTogether() throws Exception {enable();LocalDateTime due=LocalDateTime.now().withNano(0).minusSeconds(1);jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).set(JobTaskDO::getNextRunTime,due));var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(first::maintain);var b=pool.submit(second::maintain);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}var slots=runs().stream().filter(r->due.equals(r.getScheduledTime())).toList();assertEquals(1,slots.size());assertTrue(jobs.selectById(job.getId()).getNextRunTime().isAfter(due));assertEquals(1,dispatched.get());}
    @Test void revokedCronActorCannotPreemptAnExistingValidRun(){strategy("SERIAL_PRIORITY");Long a=enqueue(first,"a");enable();LocalDateTime due=LocalDateTime.now().withNano(0).minusSeconds(1);jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).set(JobTaskDO::getNextRunTime,due));doThrow(new ServiceException(1000,"revoked")).when(runtime).validateFrozen(any());first.maintain();assertFalse(records.selectById(a).getStopRequested());var rejected=runs().stream().filter(r->r.getScheduledTime()!=null).toList();assertFalse(rejected.isEmpty());assertTrue(rejected.stream().allMatch(r->"REJECTED".equals(r.getQueueState())));verify(runtime,never()).stopForActor(any());}
    @Test void closingGenerationCancelsOnlyUnstartedScheduledSlots(){Long active=enqueue(first,"a");enable();jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).set(JobTaskDO::getNextRunTime,LocalDateTime.now().withNano(0).minusSeconds(1)));first.maintain();assertTrue(runs().stream().anyMatch(r->"QUEUED".equals(r.getQueueState())&&r.getScheduledTime()!=null));var actual=jobs.selectById(job.getId());var off=new ScheduleReq();off.setId(job.getId());off.setRevision(actual.getRevision());off.setCronExpression(actual.getCronExpression());off.setScheduleStatus(0);first.configureSchedule(off,7L,null);assertTrue(runs().stream().filter(r->r.getScheduledTime()!=null).allMatch(r->"CANCELLED".equals(r.getQueueState())));assertEquals("ACTIVE",records.selectById(active).getQueueState());assertNull(jobs.selectById(job.getId()).getNextRunTime());}
    @Test void unauthorizedPublicStopCannotBorrowTheFrozenExecutorsPermission() {
        Long a=enqueue(first,"a");
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(new cn.iocoder.yudao.framework.security.core.LoginUser().setId(99L).setUserType(2),new org.springframework.mock.web.MockHttpServletRequest());
        try {
            doThrow(new ServiceException(1000,"caller lacks native permission")).when(runtime).authorizeStopRequest(any(),eq(99L));
            assertThrows(ServiceException.class,()->first.stop(job.getId()));assertFalse(records.selectById(a).getStopRequested());assertEquals("ACTIVE",records.selectById(a).getQueueState());verify(runtime,never()).stopForActor(any());
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void priorityCallerWithoutOldNativePermissionCannotSupersedeOrStop() {
        strategy("SERIAL_PRIORITY");Long a=enqueue(first,"a");doThrow(new ServiceException(1000,"caller lacks old native permission")).when(runtime).authorizeStopRequest(any(),eq(7L));
        assertThrows(ServiceException.class,()->enqueue(second,"b"));assertEquals(1,runs().size());assertFalse(records.selectById(a).getStopRequested());verify(runtime,never()).stopForActor(any());
    }
    @Test void cronInfrastructureFailureKeepsDueSlotAndReportsCoordinatorFailure() {
        enable();LocalDateTime due=LocalDateTime.now().withNano(0).minusSeconds(1);jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).set(JobTaskDO::getNextRunTime,due));
        doThrow(new IllegalStateException("permission RPC unavailable")).when(runtime).validateFrozen(any());
        assertTrue(first.maintain()>0);assertEquals(due,jobs.selectById(job.getId()).getNextRunTime());assertTrue(runs().isEmpty());assertEquals(0,dispatched.get());
    }
    @Test void largeQueueOfOneJobDoesNotStarveAnotherJobsDispatch() {
        enqueue(first,"a");
        for(int i=0;i<50;i++)insertWaiting(job,snapshot,"old_"+i,LocalDateTime.now().minusDays(1));
        var another=new JobTaskDO();another.setCategoryId(1L);another.setRevision(0);another.setName("公平扫描回归_"+UUID.randomUUID());another.setStatus(1);another.setScheduleStatus(0);another.setScheduleGeneration(0L);another.setStrategy("SERIAL_WAIT");another.setOperationState("IDLE");another.setFlowJson(job.getFlowJson());another.setCreator("7");another.setUpdater("7");another.setCreateTime(LocalDateTime.now());another.setUpdateTime(LocalDateTime.now());jobs.insert(another);extraJobs.add(another.getId());
        String secondSnapshot=cn.hutool.json.JSONUtil.parseObj(snapshot).set("jobId",another.getId()).set("name",another.getName()).toString();
        insertWaiting(another,secondSnapshot,"second",LocalDateTime.now());first.maintain();
        assertEquals(2,dispatched.get());assertEquals("ACTIVE",records.selectList(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,another.getId())).getFirst().getQueueState());
    }
    void insertWaiting(JobTaskDO target,String frozen,String suffix,LocalDateTime time) {
        var record=new JobRecordDO();record.setJobId(target.getId());record.setOrigin("PLATFORM");record.setRequestId("fair_"+target.getId()+"_"+suffix);record.setInstanceName(target.getName()+suffix);record.setExecutionUserId(7L);record.setExecutionType("START");record.setStatus("WAITING");record.setQueueState("QUEUED");record.setSubmissionStage("QUEUED");record.setQueuedTime(time);record.setRunSnapshotJson(frozen);record.setDsProjectCode(100L);record.setFlowJson(target.getFlowJson());record.setCreator("7");record.setUpdater("7");record.setCreateTime(time);record.setUpdateTime(time);records.insert(record);
    }
    @Test void latestRecordUsesAcceptanceOrderSoOldUnstartedDiscardCannotHideNewSuccess() {
        insertWaiting(job,snapshot,"old_discard",LocalDateTime.now().minusDays(1));var old=runs().getFirst();
        records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,old.getId()).set(JobRecordDO::getQueueState,"DISCARDED").set(JobRecordDO::getStatus,"STOPPED"));
        insertWaiting(job,snapshot,"new_success",LocalDateTime.now());var success=runs().getLast();
        records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,success.getId()).set(JobRecordDO::getQueueState,"DONE").set(JobRecordDO::getStatus,"SUCCESS").set(JobRecordDO::getStartTime,LocalDateTime.now()));
        var service=spy(new JobService());ReflectionTestUtils.setField(service,"jobs",jobs);ReflectionTestUtils.setField(service,"records",records);ReflectionTestUtils.setField(service,"queue",mock(JobQueueCoordinator.class));ReflectionTestUtils.setField(service,"categories",mock(JobCategoryMapper.class));
        var check=new CheckResp();check.setValid(true);check.setExecutable(false);check.setErrors(List.of());doReturn(check).when(service).checkFlow(anyString());
        var response=service.get(job.getId());assertEquals(success.getId(),response.getLatestRecord().getId());assertEquals("SUCCESS",response.getLastRunStatus());assertEquals("DONE",response.getLatestRecord().getQueueState());
    }
    void enable(){var actual=jobs.selectById(job.getId());var on=new ScheduleReq();on.setId(job.getId());on.setRevision(actual.getRevision());on.setScheduleStatus(1);on.setCronExpression("0/30 * * * * ?");first.configureSchedule(on,7L,snapshot);}
}
