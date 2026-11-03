package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.*;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.operations.OpsInstanceMapper;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationRuntimeProperties;
import cn.iocoder.yudao.module.dataforge.service.operations.OpsInstanceService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 普通读取对账和原生子工作流绑定的行为边界，防止旧节点版本和历史状态被继续执行或展示。 */
class JobRuntimeBoundaryTest {
    @BeforeAll static void initializeMappings() {
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(config,"job-runtime-test"),JobTaskDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(config,"job-record-runtime-test"),JobRecordDO.class);
    }
    static class Fixture {
        final JobService service=spy(new JobService());
        final JobManagedRunService managed=mock(JobManagedRunService.class);final JobQueueCoordinator queue=mock(JobQueueCoordinator.class);
        final JobTaskMapper jobs=mock(JobTaskMapper.class);final JobRecordMapper records=mock(JobRecordMapper.class);final JobCategoryMapper categories=mock(JobCategoryMapper.class);
        final IntegrationTaskMapper integrations=mock(IntegrationTaskMapper.class);final DolphinSchedulerClient ds=mock(DolphinSchedulerClient.class);
        final WorkflowDefinitionApi definitions=mock(WorkflowDefinitionApi.class);final WorkflowInstanceApi instances=mock(WorkflowInstanceApi.class);final ScheduleApi schedules=mock(ScheduleApi.class);final TaskDefinitionApi tasks=mock(TaskDefinitionApi.class);final ExecutorApi executor=mock(ExecutorApi.class);
        final JobTaskDO job=new JobTaskDO();final JobRecordDO record=new JobRecordDO();
        Fixture() {
            ReflectionTestUtils.setField(service,"managedRuns",managed);ReflectionTestUtils.setField(service,"queue",queue);ReflectionTestUtils.setField(service,"jobs",jobs);ReflectionTestUtils.setField(service,"records",records);ReflectionTestUtils.setField(service,"categories",categories);ReflectionTestUtils.setField(service,"integrations",integrations);ReflectionTestUtils.setField(service,"ds",ds);
            var security=mock(SecurityFrameworkService.class);when(security.hasPermission("dataforge:integration:run")).thenReturn(true);ReflectionTestUtils.setField(service,"security",security);
            var runtime=new IntegrationRuntimeProperties();runtime.setDsProjectCode(321L);runtime.setTenantCode("datahub");ReflectionTestUtils.setField(service,"runtime",runtime);
            job.setId(1L);job.setCategoryId(1L);job.setName("水情作业");job.setRevision(2);job.setStatus(1);job.setScheduleStatus(0);job.setStrategy("PARALLEL");job.setOperationState("IDLE");job.setDsProjectCode(321L);job.setDsDefinitionCode(1000L);job.setFlowJson("{\"nodes\":[{\"id\":\"water\",\"kind\":\"INTEGRATION\",\"taskId\":\"9007199254740993\",\"name\":\"水情任务\"}],\"edges\":[]}");
            record.setId(10L);record.setJobId(1L);record.setOrigin("PLATFORM");record.setDsProjectCode(321L);record.setDsInstanceId(7);record.setStatus("WAITING");record.setInstanceName("水情作业-7");record.setStartTime(LocalDateTime.of(2026,11,1,2,0));
            when(jobs.selectById(1L)).thenReturn(job);when(jobs.selectList(any(Wrapper.class))).thenReturn(List.of(job));when(jobs.selectPage(any(PageReq.class),any(Wrapper.class))).thenReturn(new PageResult<>(List.of(job),1L));when(jobs.update(any(),any())).thenReturn(1);when(jobs.updateById(any(JobTaskDO.class))).thenReturn(1);
            when(records.selectOne(any(Wrapper.class))).thenReturn(record);when(records.selectList(any(Wrapper.class))).thenReturn(List.of(record));when(records.updateById(any(JobRecordDO.class))).thenReturn(1);when(records.insert(any(JobRecordDO.class))).thenAnswer(invocation->{JobRecordDO r=invocation.getArgument(0);r.setId(20L);return 1;});
            when(ds.workflowDefinition()).thenReturn(definitions);when(ds.workflowInstance()).thenReturn(instances);when(ds.schedule()).thenReturn(schedules);when(ds.taskDefinition()).thenReturn(tasks);when(ds.executor()).thenReturn(executor);
            when(schedules.queryScheduleListPaging(321L,1000L,null,1,100)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"totalList\":[]}}"));
            when(instances.queryWorkflowInstanceList(eq(321L),any(QueryWorkflowInstanceListRequest.class))).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"totalList\":[{\"id\":7,\"name\":\"水情作业-7\",\"state\":\"SUCCESS\",\"startTime\":\"2026-11-01 02:00:00\",\"endTime\":\"2026-11-01 02:00:42\"}]}}"));
            var check=new CheckResp();check.setValid(true);check.setExecutable(true);check.setErrors(List.of());doReturn(check).when(service).checkFlow(anyString());
            var child=new IntegrationTaskDO();child.setId(9007199254740993L);child.setName("水情任务");child.setMode("OFFLINE");child.setStatus(1);child.setRevision(8);child.setDsProjectCode(321L);child.setDsDefinitionCode(9007199254740995L);when(integrations.selectById(9007199254740993L)).thenReturn(child);
        }
    }
    @Test void ordinaryJobPageReconcilesTerminalExecutionWithoutOpeningDetails() {
        var f=new Fixture();var page=f.service.page(new PageReq());assertEquals("SUCCESS",page.getList().getFirst().getLastRunStatus());assertEquals(42L,page.getList().getFirst().getLastRunDuration());verify(f.instances).queryWorkflowInstanceList(eq(321L),any());
    }
    @Test void ordinaryOpsReadReconcilesTheRequestedRealJob() {
        var f=new Fixture();var ops=new OpsInstanceService();var mapper=mock(OpsInstanceMapper.class);ReflectionTestUtils.setField(ops,"mapper",mapper);ReflectionTestUtils.setField(ops,"jobService",f.service);
        when(mapper.selectInstance(any())).thenAnswer(ignored->JobService.instance(f.record,f.job));when(mapper.selectJobChildren(10L,"PLATFORM")).thenReturn("[]");
        var identity=new OpsInstanceModels.IdentityReq();identity.setId(10L);identity.setKind("JOB");identity.setOrigin("PLATFORM");assertEquals("SUCCESS",ops.get(identity).getStatus());verify(f.instances).queryWorkflowInstanceList(eq(321L),any());
    }
    @Test void platformCronCannotStartUntilLegacyTimerIsConfirmedOffline() {
        var f=new Fixture();when(f.managed.freeze(any(),any(),eq(true))).thenReturn("snapshot");
        when(f.schedules.queryScheduleListPaging(321L,1000L,null,1,100)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"totalList\":[{\"id\":4,\"releaseState\":\"ONLINE\"}]}}"));
        when(f.schedules.offlineSchedule(321L,4)).thenReturn(JSONUtil.parseObj("{\"code\":0}"));
        var req=new ScheduleReq();req.setId(1L);req.setRevision(2);req.setScheduleStatus(1);req.setCronExpression("0 * * * * ?");
        assertThrows(ServiceException.class,()->f.service.schedule(req));verify(f.queue,never()).configureSchedule(any(),any(),any());verifyNoInteractions(f.executor);
    }
    @Test void manualRunDelegatesAllStrategiesToDurableQueueInsteadOfCachedDefinition() {
        for(String strategy:List.of("PARALLEL","SERIAL_WAIT","SERIAL_DISCARD","SERIAL_PRIORITY")) {
            var f=new Fixture();f.job.setDsDefinitionCode(null);f.job.setStrategy(strategy);when(f.records.selectOne(any(Wrapper.class))).thenReturn(null);
            when(f.managed.freeze(eq(f.job),isNull(),eq(true))).thenReturn("snapshot");when(f.queue.enqueueManual(eq(f.job),any(),isNull(),eq("snapshot"))).thenReturn(20L);
            var req=new RunReq();req.setId(1L);req.setRevision(2);req.setRequestId("fresh_"+strategy);
            assertEquals(20L,f.service.run(req));verify(f.queue).enqueueManual(f.job,req,null,"snapshot");verifyNoInteractions(f.executor);
        }
    }
    @Test void manualRunCannotImplicitlyStopAnOnlineLegacyTimer() {
        var f=new Fixture();when(f.records.selectOne(any(Wrapper.class))).thenReturn(null);when(f.managed.freeze(any(),any(),eq(true))).thenReturn("snapshot");
        when(f.schedules.queryScheduleListPaging(321L,1000L,null,1,100)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"totalList\":[{\"id\":4,\"releaseState\":\"ONLINE\"}]}}"));
        var req=new RunReq();req.setId(1L);req.setRevision(2);req.setRequestId("legacy_timer_request");assertThrows(ServiceException.class,()->f.service.run(req));verify(f.schedules,never()).offlineSchedule(anyLong(),anyInt());verify(f.queue,never()).enqueueManual(any(),any(),any(),any());
    }

}
