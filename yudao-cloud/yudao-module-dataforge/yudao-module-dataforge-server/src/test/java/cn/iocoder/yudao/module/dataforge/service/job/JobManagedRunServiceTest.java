package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.*;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.dev.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实父子协议、平台门禁和未执行分支的行为回归，不用名称或最近实例认领。 */
class JobManagedRunServiceTest {
    @BeforeAll static void mappings(){var c=new com.baomidou.mybatisplus.core.MybatisConfiguration();for(Class<?> type:List.of(JobRecordDO.class,JobNodeRunDO.class,IntegrationRecordDO.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(c,type.getSimpleName()),type);}
    static class Fixture {
        final JobManagedRunService service=new JobManagedRunService();final JobRecordMapper records=mock(JobRecordMapper.class);final JobNodeRunMapper nodes=mock(JobNodeRunMapper.class);final IntegrationRecordMapper integrations=mock(IntegrationRecordMapper.class);final DevTaskService development=mock(DevTaskService.class);final DevRecordService devRecords=mock(DevRecordService.class);final DolphinSchedulerClient ds=mock(DolphinSchedulerClient.class);final WorkflowInstanceApi instances=mock(WorkflowInstanceApi.class);final ExecutorApi executor=mock(ExecutorApi.class);final SecurityFrameworkService security=mock(SecurityFrameworkService.class);
        final JobRecordDO parent=new JobRecordDO();final JobNodeRunDO input=new JobNodeRunDO(),output=new JobNodeRunDO();final IntegrationRecordDO integration=new IntegrationRecordDO();final DevRecordDO dev=new DevRecordDO();
        Fixture(){
            ReflectionTestUtils.setField(service,"records",records);ReflectionTestUtils.setField(service,"nodes",nodes);ReflectionTestUtils.setField(service,"integrationRecords",integrations);ReflectionTestUtils.setField(service,"development",development);ReflectionTestUtils.setField(service,"developmentRecords",devRecords);ReflectionTestUtils.setField(service,"ds",ds);ReflectionTestUtils.setField(service,"security",security);
            when(ds.workflowInstance()).thenReturn(instances);when(ds.executor()).thenReturn(executor);when(security.hasPermission(anyString())).thenReturn(true);
            parent.setId(1L);parent.setJobId(2L);parent.setOrigin("PLATFORM");parent.setExecutionUserId(7L);parent.setDsProjectCode(100L);parent.setDsDefinitionCode(1000L);parent.setDsInstanceId(50);parent.setSubmissionStage("BOUND");parent.setStatus("RUNNING");parent.setStartTime(LocalDateTime.of(2026,11,1,2,0));when(records.selectById(1L)).thenReturn(parent);when(records.update(any(),any())).thenReturn(1);
            input.setId(10L);input.setJobRecordId(1L);input.setNodeId("collect");input.setKind("INTEGRATION");input.setTaskId(111L);input.setTaskName("真实采集任务");input.setDsProjectCode(100L);input.setDsDefinitionCode(1100L);input.setParentTaskCode(101L);input.setPlatformRecordId(201L);input.setStatus("RUNNING");
            output.setId(11L);output.setJobRecordId(1L);output.setNodeId("aggregate");output.setKind("DEVELOPMENT");output.setTaskId(222L);output.setDsProjectCode(100L);output.setDsDefinitionCode(1200L);output.setParentTaskCode(102L);output.setPlatformRecordId(202L);output.setStatus("WAITING");when(nodes.selectList(any(Wrapper.class))).thenReturn(List.of(input,output));when(nodes.update(any(),any())).thenReturn(1);
            integration.setId(201L);integration.setTaskId(111L);integration.setDsProjectCode(100L);integration.setDsInstanceId(51);integration.setStatus("RUNNING");when(integrations.selectById(201L)).thenReturn(integration);when(integrations.update(any(),any())).thenReturn(1);
            dev.setId(202L);dev.setTaskId(222L);dev.setTaskName("真实聚合任务");dev.setStatus("SUCCESS");dev.setQualityStatus("PASSED");when(development.bindJobNode(202L,100L,1200L,52)).thenReturn(dev);when(development.observeJobNode(202L)).thenReturn(dev);when(devRecords.getRecord(202L)).thenReturn(dev);
            parentState("SUCCESS");when(instances.queryWorkflowInstanceById(100L,51)).thenReturn(workflow(51,1100,"SUCCESS"));when(instances.queryWorkflowInstanceById(100L,52)).thenReturn(workflow(52,1200,"SUCCESS"));
            when(instances.queryTaskListByWorkflowInstanceId(100L,50)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"taskList\":[{\"id\":31,\"taskCode\":101,\"taskType\":\"SUB_WORKFLOW\",\"projectCode\":100,\"workflowInstanceId\":50},{\"id\":32,\"taskCode\":102,\"taskType\":\"SUB_WORKFLOW\",\"projectCode\":100,\"workflowInstanceId\":50}]}}"));
            when(instances.querySubWorkflowInstanceByTaskId(100L,31,null)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"subWorkflowInstanceId\":51}}"));when(instances.querySubWorkflowInstanceByTaskId(100L,32,null)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"subWorkflowInstanceId\":52}}"));
            when(instances.queryParentInstanceBySubId(100L,51)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"parentWorkflowInstance\":50}}"));when(instances.queryParentInstanceBySubId(100L,52)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"parentWorkflowInstance\":50}}"));
        }
        void parentState(String state){when(instances.queryWorkflowInstanceById(100L,50)).thenReturn(workflow(50,1000,state));}
        static JSONObject workflow(int id,long code,String state){return new JSONObject().set("code",0).set("data",new JSONObject().set("id",id).set("projectCode",100L).set("workflowDefinitionCode",code).set("state",state).set("name","实际实例"+id).set("startTime","2026-11-01 02:00:00").set("endTime","2026-11-01 02:00:42"));}
    }
    @Test void matchesIntegerReverseParentAndUsesRealModuleIdentities(){var f=new Fixture();f.service.reconcile(f.parent);assertEquals("SUCCESS",f.parent.getStatus());var children=JSONUtil.parseArray(f.parent.getChildrenJson());assertEquals(2,children.size());assertEquals("MANAGED",children.getJSONObject(0).getStr("origin"));assertEquals(201L,children.getJSONObject(0).getLong("id"));assertEquals("真实采集任务",children.getJSONObject(0).getStr("taskName"));assertEquals("PLATFORM",children.getJSONObject(1).getStr("origin"));assertEquals(202L,children.getJSONObject(1).getLong("id"));}
    @Test void mismatchedReverseParentCannotClaimTheChild(){var f=new Fixture();when(f.instances.queryParentInstanceBySubId(100L,51)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"parentWorkflowInstance\":999}}"));assertThrows(ServiceException.class,()->f.service.reconcile(f.parent));assertEquals("RUNNING",f.parent.getStatus());verify(f.development,never()).bindJobNode(any(),any(),any(),any());}
    private Fixture recoveredParentFixture() {
        var f=new Fixture();f.input.setDsInstanceId(51);f.input.setParentTaskInstanceId(21);
        var tasks=JSONUtil.parseObj("{\"code\":0,\"data\":{\"taskList\":[{\"id\":31,\"taskCode\":101,\"taskType\":\"SUB_WORKFLOW\",\"projectCode\":100,\"workflowInstanceId\":50,\"flag\":\"YES\"},{\"id\":32,\"taskCode\":102,\"taskType\":\"SUB_WORKFLOW\",\"projectCode\":100,\"workflowInstanceId\":50}]}}");
        when(f.instances.queryTaskListByWorkflowInstanceId(100L,50)).thenReturn(tasks);
        when(f.instances.querySubWorkflowInstanceByTaskId(100L,21,null)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"subWorkflowInstanceId\":51}}"));
        when(f.instances.queryParentInstanceBySubId(100L,51)).thenThrow(new cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException("org.apache.ibatis.exceptions.TooManyResultsException: selectOne found: 2",10119,null));
        return f;
    }
    @Test void recoveredParentRetainsPreviouslyVerifiedChildAndOldIdentityAnchor() {
        var f=recoveredParentFixture();f.service.reconcile(f.parent);
        assertEquals("SUCCESS",f.parent.getStatus());assertEquals(21,f.input.getParentTaskInstanceId());
        assertTrue(f.parent.getLogContent().contains("恢复前后父任务"));
        verify(f.instances).querySubWorkflowInstanceByTaskId(100L,21,null);
        // 再次对账仍用原来的已核验锚点，不依赖第一次恢复的内存状态。
        f.service.reconcile(f.parent);assertEquals("SUCCESS",f.parent.getStatus());
    }
    @Test void recoveredParentWithoutPreviouslyVerifiedChildCannotBeAdopted() {
        var f=recoveredParentFixture();f.input.setDsInstanceId(null);
        assertThrows(RuntimeException.class,()->f.service.reconcile(f.parent));
        verify(f.development,never()).bindJobNode(any(),any(),any(),any());
    }
    @Test void recoveredParentWhoseOldTaskPointsElsewhereCannotBeAdopted() {
        var f=recoveredParentFixture();when(f.instances.querySubWorkflowInstanceByTaskId(100L,21,null)).thenReturn(JSONUtil.parseObj("{\"code\":0,\"data\":{\"subWorkflowInstanceId\":999}}"));
        assertThrows(RuntimeException.class,()->f.service.reconcile(f.parent));
        verify(f.development,never()).bindJobNode(any(),any(),any(),any());
    }
    @Test void unrelatedReverseLookupFailureCannotUseRecoveryFallback() {
        var f=recoveredParentFixture();doThrow(new cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException("transport unavailable",10119,null)).when(f.instances).queryParentInstanceBySubId(100L,51);
        assertThrows(RuntimeException.class,()->f.service.reconcile(f.parent));
        verify(f.instances,never()).querySubWorkflowInstanceByTaskId(100L,21,null);
    }
    @Test void dsSuccessDoesNotBypassNativeOrQualityPending(){var f=new Fixture();f.dev.setStatus("UNKNOWN");f.service.reconcile(f.parent);assertEquals("UNKNOWN",f.parent.getStatus());assertNull(f.parent.getEndTime());}
    @Test void upstreamFailureSkipsUnstartedIntegrationWithoutInventingAnInstance(){var f=new Fixture();f.parentState("FAILURE");f.input.setKind("DEVELOPMENT");f.input.setTaskId(222L);f.input.setPlatformRecordId(202L);f.input.setDsDefinitionCode(1200L);f.output.setKind("INTEGRATION");f.output.setTaskId(333L);f.output.setPlatformRecordId(null);f.output.setDsDefinitionCode(1300L);when(f.instances.queryWorkflowInstanceById(100L,51)).thenReturn(Fixture.workflow(51,1200,"FAILURE"));f.dev.setStatus("FAILED");when(f.development.bindJobNode(202L,100L,1200L,51)).thenReturn(f.dev);when(f.instances.querySubWorkflowInstanceByTaskId(100L,32,null)).thenReturn(JSONUtil.parseObj("{\"code\":50007,\"msg\":\"sub workflow absent\"}"));f.service.reconcile(f.parent);assertEquals("FAILURE",f.parent.getStatus());assertEquals("SKIPPED",f.output.getStatus());assertNull(f.output.getPlatformRecordId());assertEquals(1,JSONUtil.parseArray(f.parent.getChildrenJson()).size());verify(f.integrations,never()).insert(any(IntegrationRecordDO.class));}
    @Test void transportFailureCannotBeTreatedAsUnstartedNode(){var f=new Fixture();f.parentState("FAILURE");when(f.instances.querySubWorkflowInstanceByTaskId(100L,32,null)).thenThrow(new IllegalStateException("transport unavailable"));assertThrows(IllegalStateException.class,()->f.service.reconcile(f.parent));verify(f.development,never()).cancelPreparedJobNode(anyLong());}
    @Test void completedDevelopmentDoesNotBlockStoppingTheRealParent(){var f=new Fixture();when(f.executor.controlWorkflowInstance(100L,50,"STOP")).thenReturn(JSONUtil.parseObj("{\"code\":0}"));f.service.stop(f.parent);verify(f.devRecords,never()).stopRecord(202L);verify(f.executor).controlWorkflowInstance(100L,50,"STOP");}
    @Test void oldRunningObservationCannotOverwritePersistedIntegrationSuccess(){var f=new Fixture();when(f.instances.queryWorkflowInstanceById(100L,51)).thenReturn(Fixture.workflow(51,1100,"RUNNING_EXECUTION"));var terminal=new IntegrationRecordDO();terminal.setId(201L);terminal.setTaskId(111L);terminal.setStatus("SUCCESS");when(f.integrations.selectById(201L)).thenReturn(f.integration,terminal);when(f.integrations.update(any(),any())).thenReturn(0);f.service.reconcile(f.parent);assertEquals("SUCCESS",JSONUtil.parseArray(f.parent.getChildrenJson()).getJSONObject(0).getStr("status"));}
    @Test void lateTriggerAcknowledgementPreservesAlreadyReconciledSuccess(){
        var f=new Fixture();var stored=new JobRecordDO();stored.setId(1L);stored.setDsProjectCode(100L);stored.setDsDefinitionCode(1000L);stored.setDsInstanceId(50);stored.setSubmissionStage("BOUND");stored.setStatus("SUCCESS");
        f.parent.setDsInstanceId(null);f.parent.setSubmissionStage("TRIGGER_SENT");f.parent.setStatus("UNKNOWN");when(f.records.selectById(1L)).thenReturn(stored);when(f.records.update(isNull(),any())).thenReturn(0);
        ReflectionTestUtils.invokeMethod(f.service,"acceptParentIdentity",f.parent,50);
        assertEquals("SUCCESS",f.parent.getStatus());assertEquals(50,f.parent.getDsInstanceId());verify(f.records,never()).updateById(any(JobRecordDO.class));
    }
    @Test void unknownRecoveryRereadsConcurrentTerminalWithoutAStaleObjectWrite(){
        var f=new Fixture();f.parent.setDsInstanceId(null);f.parent.setSubmissionStage("TRIGGER_SENT");f.parent.setStatus("UNKNOWN");
        var stored=new JobRecordDO();stored.setId(1L);stored.setDsProjectCode(100L);stored.setDsDefinitionCode(1000L);stored.setDsInstanceId(50);stored.setSubmissionStage("BOUND");stored.setStatus("SUCCESS");when(f.records.selectById(1L)).thenReturn(stored);when(f.records.update(isNull(),any())).thenReturn(0);
        when(f.instances.queryWorkflowInstanceList(eq(100L),any())).thenReturn(new JSONObject().set("code",0).set("data",new JSONObject().set("totalList",new JSONArray().put(Fixture.workflow(50,1000,"SUCCESS").getJSONObject("data")))));
        f.service.reconcile(f.parent);assertEquals("SUCCESS",f.parent.getStatus());verify(f.records,never()).updateById(any(JobRecordDO.class));verify(f.instances,never()).queryTaskListByWorkflowInstanceId(anyLong(),anyInt());
    }
    @Test void finishingDevelopmentDuringStopStillSendsParentStop(){
        var f=new Fixture();f.dev.setStatus("RUNNING");doThrow(new ServiceException(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_RECORD_NOT_RUNNING.getCode(),"already finished")).when(f.devRecords).stopRecord(202L);when(f.executor.controlWorkflowInstance(100L,50,"STOP")).thenReturn(JSONUtil.parseObj("{\"code\":0}"));
        f.service.stop(f.parent);verify(f.executor).controlWorkflowInstance(100L,50,"STOP");
    }
    @Test void stopDoesNotSwallowPermissionDenialAsATerminalRace(){
        var f=new Fixture();f.dev.setStatus("RUNNING");doThrow(new ServiceException(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.OPERATION_PERMISSION_DENIED.getCode(),"denied")).when(f.devRecords).stopRecord(202L);
        assertThrows(ServiceException.class,()->f.service.stop(f.parent));verify(f.executor,never()).controlWorkflowInstance(anyLong(),anyInt(),anyString());
    }
    @Test void previouslyScheduledImportIsClaimedAsTheVerifiedJobChild(){
        var f=new Fixture();f.integration.setTriggerType("SCHEDULE");f.service.reconcile(f.parent);
        verify(f.integrations).update(isNull(),argThat((Wrapper<IntegrationRecordDO> w)->w.getSqlSegment().contains("ds_instance_id")&&((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<IntegrationRecordDO>)w).getSqlSet().contains("trigger_type")));
    }

    @Test void runtimeObservationCannotOverwriteQueueLeaseOrStopIntent() {
        var source=new JobRecordDO();source.setId(1L);source.setStatus("SUCCESS");source.setQueueState("ACTIVE");source.setLeaseOwner("stale");source.setSerialSlot(true);source.setStopRequested(false);
        JobRecordDO update=ReflectionTestUtils.invokeMethod(JobManagedRunService.class,"runtimeFields",source);
        assertEquals("SUCCESS",update.getStatus());assertNull(update.getQueueState());assertNull(update.getLeaseOwner());assertNull(update.getSerialSlot());assertNull(update.getStopRequested());
    }


    @Test void queuedBindingDriftCannotSwitchToANewChildDefinition() {
        var f=new Fixture();var integrationService=mock(cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService.class);
        var integrationRuntime=mock(cn.iocoder.yudao.module.dataforge.service.integration.IntegrationDsRuntime.class);
        var runtimeProperties=new cn.iocoder.yudao.module.dataforge.service.integration.IntegrationRuntimeProperties();runtimeProperties.setTenantCode("datahub");
        var access=mock(cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.class);
        ReflectionTestUtils.setField(f.service,"integrations",integrationService);ReflectionTestUtils.setField(f.service,"integrationRuntime",integrationRuntime);ReflectionTestUtils.setField(f.service,"runtime",runtimeProperties);ReflectionTestUtils.setField(f.service,"executionAccess",access);
        var task=new cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO();task.setId(111L);task.setRevision(1);task.setDsProjectCode(100L);task.setDsDefinitionCode(1100L);task.setWorkerGroup("controlled");when(integrationService.validateJobReference(111L)).thenReturn(task);when(integrationRuntime.environmentCode(task)).thenReturn(80L);
        var job=new JobTaskDO();job.setId(2L);job.setName("冻结版本回归");job.setRevision(1);job.setStrategy("SERIAL_WAIT");job.setFlowJson("{\"nodes\":[{\"id\":\"a\",\"kind\":\"INTEGRATION\",\"taskId\":111}],\"edges\":[]}");
        f.parent.setRunSnapshotJson(f.service.freeze(job,7L,false));f.service.validateFrozen(f.parent);
        task.setDsDefinitionCode(1101L);assertThrows(ServiceException.class,()->f.service.validateFrozen(f.parent));verifyNoInteractions(f.executor);
    }

}
