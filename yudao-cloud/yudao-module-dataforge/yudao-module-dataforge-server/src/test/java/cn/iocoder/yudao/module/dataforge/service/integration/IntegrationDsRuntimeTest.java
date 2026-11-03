package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException;
import cn.iocoder.yudao.framework.dolphinscheduler.api.ExecutorApi;
import cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowInstanceApi;
import cn.iocoder.yudao.framework.dolphinscheduler.request.TriggerWorkflowDefinitionRequest;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationDsRuntimeTest {
    private final IntegrationDsRuntime runtime = new IntegrationDsRuntime();
    private final DolphinSchedulerClient client = mock(DolphinSchedulerClient.class);
    private final ExecutorApi executor = mock(ExecutorApi.class);
    private final cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi definitions = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi.class);
    private final WorkflowInstanceApi instances = mock(WorkflowInstanceApi.class);
    private final cn.iocoder.yudao.framework.dolphinscheduler.api.ScheduleApi schedules = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.ScheduleApi.class);
    private final IntegrationTaskMapper tasks = mock(IntegrationTaskMapper.class);
    private final IntegrationRecordMapper records = mock(IntegrationRecordMapper.class);
    private final IntegrationRuntimeProperties properties = new IntegrationRuntimeProperties();

    @BeforeEach void init() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "integration-runtime"), IntegrationRecordDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "integration-runtime-task"), IntegrationTaskDO.class);
        properties.setDsProjectCode(91L); properties.setTenantCode("managed");
        ReflectionTestUtils.setField(runtime, "client", client); ReflectionTestUtils.setField(runtime, "properties", properties);
        ReflectionTestUtils.setField(runtime, "tasks", tasks); ReflectionTestUtils.setField(runtime, "records", records);
        when(client.executor()).thenReturn(executor); when(client.workflowInstance()).thenReturn(instances);
        when(client.schedule()).thenReturn(schedules); when(client.workflowDefinition()).thenReturn(definitions);
        when(definitions.queryWorkflowDefinitionByCode(anyLong(), anyLong())).thenReturn(new JSONObject().set("code", 0)
                .set("data", new JSONObject().set("workflowDefinition", new JSONObject().set("releaseState", "OFFLINE"))));
        when(schedules.queryScheduleListPaging(anyLong(), anyLong(), isNull(), anyInt(), anyInt()))
                .thenReturn(new JSONObject().set("code", 0).set("data", new JSONObject().set("totalList", new JSONArray())));
        when(records.selectCount(any())).thenReturn(0L);
        doAnswer(call -> { ((IntegrationRecordDO) call.getArgument(0)).setId(101L); return 1; }).when(records).insert(any(IntegrationRecordDO.class));
    }

    @Test void acceptedTriggerIsRunningUntilDsActuallyReportsSuccess() {
        when(executor.triggerWorkflowDefinition(eq(91L), any())).thenReturn(new JSONObject().set("code", 0).set("data", new JSONArray().put(42)));
        assertEquals(101L, runtime.run(task()));
        var capture = ArgumentCaptor.forClass(IntegrationRecordDO.class); verify(records).updateById(capture.capture());
        assertEquals("RUNNING", capture.getValue().getStatus()); assertEquals(42, capture.getValue().getDsInstanceId());
        var request = ArgumentCaptor.forClass(TriggerWorkflowDefinitionRequest.class);
        verify(executor).triggerWorkflowDefinition(eq(91L), request.capture());
        assertEquals("managed", request.getValue().getTenantCode()); assertTrue(request.getValue().getStartParams().contains("101"));
    }

    @Test void unknownTriggerOutcomeRemainsObservableAndDoesNotBecomeSuccess() {
        when(executor.triggerWorkflowDefinition(eq(91L), any())).thenThrow(new IllegalStateException("transport unavailable"));
        assertThrows(IllegalStateException.class, () -> runtime.run(task()));
        var capture = ArgumentCaptor.forClass(IntegrationRecordDO.class); verify(records).updateById(capture.capture());
        assertEquals("RUNNING", capture.getValue().getStatus()); assertNull(capture.getValue().getDsInstanceId());
        assertTrue(capture.getValue().getMessage().contains("待确认"));
    }

    @Test void pendingUnknownSubmissionPreventsAnAutomaticDuplicate() {
        when(records.selectCount(any())).thenReturn(1L);
        assertThrows(IllegalArgumentException.class, () -> runtime.run(task())); verifyNoInteractions(executor);
    }

    @Test void fingerprintChangesWithRealParametersAndManagedEnvironment() {
        var a = List.of(new IntegrationFlowCompiler.Step("a", "a", "FLINK", Map.of("rawScript", "select 1"), List.of()));
        var b = List.of(new IntegrationFlowCompiler.Step("a", "a", "FLINK", Map.of("rawScript", "select 2"), List.of()));
        assertNotEquals(runtime.fingerprint(task(), a), runtime.fingerprint(task(), b));
        String before = runtime.fingerprint(task(), a); properties.setTenantCode("another");
        assertNotEquals(before, runtime.fingerprint(task(), a));
    }

    @Test void usesOnlyRealDsTerminalStates() {
        assertEquals("RUNNING", IntegrationDsRuntime.status("SUBMITTED_SUCCESS"));
        assertEquals("SUCCESS", IntegrationDsRuntime.status("SUCCESS")); assertEquals("FAILURE", IntegrationDsRuntime.status("FAILURE"));
        assertEquals("IDLE", IntegrationDsRuntime.status("STOP"));
    }

    @Test void requestedStopWaitsForRealTerminalStateAndKeepsDsFailureVisible() {
        var record = new IntegrationRecordDO(); record.setId(101L); record.setTaskId(7L); record.setStatus("RUNNING");
        record.setDsProjectCode(91L); record.setDsInstanceId(42); record.setCommandType("STOP_REQUESTED");
        when(records.selectOne(any())).thenReturn(record);
        when(instances.queryWorkflowInstanceList(eq(91L), any())).thenReturn(new JSONObject().set("code", 0)
                .set("data", new JSONObject().set("totalList", new JSONArray().put(new JSONObject().set("id", 42).set("state", "FAILURE")))));
        runtime.refresh(task());
        assertEquals("IDLE", record.getStatus()); assertEquals("STOP_REQUESTED", record.getCommandType());
        assertTrue(record.getMessage().contains("FAILURE")); assertTrue(record.getMessage().contains("已请求停止"));
    }

    @Test void runningInstanceWithExplicitJsonNullEndTimeRemainsObservable() {
        var record = new IntegrationRecordDO(); record.setId(101L); record.setTaskId(7L); record.setDsProjectCode(91L); record.setDsInstanceId(42);
        when(records.selectOne(any())).thenReturn(record);
        when(instances.queryWorkflowInstanceList(eq(91L), any())).thenReturn(new JSONObject().set("code", 0)
                .set("data", new JSONObject().set("totalList", new JSONArray().put(new JSONObject().set("id", 42)
                        .set("state", "RUNNING_EXECUTION").set("startTime", "2026-11-01 16:18:17").set("endTime", cn.hutool.json.JSONNull.NULL)))));
        assertDoesNotThrow(() -> runtime.refresh(task())); assertEquals("RUNNING", record.getStatus()); assertNull(record.getEndTime());
        assertEquals(java.time.LocalDateTime.of(2026, 11, 1, 16, 18, 17), record.getStartTime());
    }

    @Test void runningInstanceOutsideFirstPageIsReconciledById() {
        var record = new IntegrationRecordDO(); record.setId(101L); record.setTaskId(7L); record.setDsProjectCode(91L); record.setDsInstanceId(42); record.setStatus("RUNNING");
        when(records.selectList(any())).thenReturn(List.of(record)); when(records.selectOne(any())).thenReturn(record);
        when(instances.queryWorkflowInstanceList(eq(91L), any())).thenReturn(new JSONObject().set("code", 0).set("data", new JSONObject().set("totalList", new JSONArray())));
        when(instances.queryWorkflowInstanceById(91L, 42)).thenReturn(new JSONObject().set("code", 0).set("data", new JSONObject().set("id", 42).set("state", "SUCCESS")));
        runtime.refresh(task()); assertEquals("SUCCESS", record.getStatus()); verify(instances).queryWorkflowInstanceById(91L, 42);
    }

    @Test void realPublicationStateRecoversLostLocalAcknowledgement() {
        var task = task(); when(tasks.update(isNull(), any())).thenReturn(1);
        when(definitions.queryWorkflowDefinitionByCode(91L, 93L)).thenReturn(new JSONObject().set("code", 0)
                .set("data", new JSONObject().set("workflowDefinition", new JSONObject().set("releaseState", "ONLINE"))));
        runtime.refreshRelease(task); assertEquals(1, task.getStatus()); assertEquals(1, task.getRevision());
    }

    @Test void missingProjectOrDefinitionIsReportedBeforeAnyPersistentStateChanges() {
        for (int code : List.of(10018, 10190, 50003)) {
            var task = task(); task.setStatus(1); task.setFlowJson("original-flow");
            doReturn(new JSONObject().set("code", code)).when(definitions).queryWorkflowDefinitionByCode(91L, 93L);
            var failure = assertThrows(IntegrationDsRuntime.MissingWorkflowDefinitionException.class, () -> runtime.refresh(task));
            assertTrue(failure.getMessage().contains("不存在"));
            assertEquals(91L, task.getDsProjectCode()); assertEquals(93L, task.getDsDefinitionCode());
            assertEquals(1, task.getStatus()); assertEquals(0, task.getRevision()); assertEquals("original-flow", task.getFlowJson());
        }
        verifyNoInteractions(tasks, records, schedules, instances);
    }

    @Test void strictClientMissingDefinitionFailureUsesTheSameReadOnlyDiagnostic() {
        doThrow(new DolphinSchedulerException("missing definition", 50003, new JSONObject().set("code", 50003)))
                .when(definitions).queryWorkflowDefinitionByCode(91L, 93L);
        assertThrows(IntegrationDsRuntime.MissingWorkflowDefinitionException.class, () -> runtime.refresh(task()));
        verifyNoInteractions(tasks, records, schedules, instances);
    }

    @Test void authenticationTransportAndUnexpectedPayloadFailuresAreNotMissingDefinitions() {
        var unauthorized = new DolphinSchedulerException("HTTP 401");
        doThrow(unauthorized).when(definitions).queryWorkflowDefinitionByCode(91L, 93L);
        assertSame(unauthorized, assertThrows(DolphinSchedulerException.class, () -> runtime.refresh(task())));
        var forbidden = new DolphinSchedulerException("HTTP 403", 403, new JSONObject().set("code", 403));
        doThrow(forbidden).when(definitions).queryWorkflowDefinitionByCode(91L, 93L);
        assertSame(forbidden, assertThrows(DolphinSchedulerException.class, () -> runtime.refresh(task())));
        var network = new IllegalStateException("transport unavailable");
        doThrow(network).when(definitions).queryWorkflowDefinitionByCode(91L, 93L);
        assertSame(network, assertThrows(IllegalStateException.class, () -> runtime.refresh(task())));
        doReturn(new JSONObject().set("code", 0).set("data", new JSONObject())).when(definitions).queryWorkflowDefinitionByCode(91L, 93L);
        assertFalse(assertThrows(IllegalStateException.class, () -> runtime.refresh(task()))
                instanceof IntegrationDsRuntime.MissingWorkflowDefinitionException);
        verifyNoInteractions(tasks, records, schedules, instances);
    }

    @Test void bindingFingerprintIgnoresNestedMapInsertionOrderButKeepsArrayOrder() {
        var forward = new java.util.LinkedHashMap<String, Object>(); forward.put("a", 1); forward.put("b", 2);
        var reverse = new java.util.LinkedHashMap<String, Object>(); reverse.put("b", 2); reverse.put("a", 1);
        var a = List.of(new IntegrationFlowCompiler.Step("a", "a", "FLINK", Map.of("nested", forward, "array", List.of(1, 2)), List.of()));
        var b = List.of(new IntegrationFlowCompiler.Step("a", "a", "FLINK", Map.of("array", List.of(1, 2), "nested", reverse), List.of()));
        assertEquals(runtime.fingerprint(task(), a), runtime.fingerprint(task(), b));
        var different = List.of(new IntegrationFlowCompiler.Step("a", "a", "FLINK", Map.of("nested", reverse, "array", List.of(2, 1)), List.of()));
        assertNotEquals(runtime.fingerprint(task(), a), runtime.fingerprint(task(), different));
    }

    @Test void scheduledImportStillUsesTerminalCasBeforeJobClaimsItsTriggerType() {
        var record=new IntegrationRecordDO();record.setId(101L);record.setTaskId(7L);record.setDsProjectCode(91L);record.setDsInstanceId(42);record.setTriggerType("SCHEDULE");record.setStatus("RUNNING");
        when(records.selectOne(any())).thenReturn(record);
        when(instances.queryWorkflowInstanceList(eq(91L),any())).thenReturn(new JSONObject().set("code",0).set("data",new JSONObject().set("totalList",new JSONArray().put(new JSONObject().set("id",42).set("state","RUNNING_EXECUTION")))));
        runtime.refresh(task());
        verify(records,never()).updateById(any(IntegrationRecordDO.class));
        verify(records).update(eq(record),argThat((com.baomidou.mybatisplus.core.conditions.Wrapper<IntegrationRecordDO> w)->w.getSqlSegment().contains("NOT IN")));
    }

    private static IntegrationTaskDO task() {
        var task = new IntegrationTaskDO(); task.setId(7L); task.setRevision(0); task.setDsProjectCode(91L); task.setDsDefinitionCode(93L);
        task.setEngine("SPARK"); task.setScheduleStatus(0); task.setStatus(0); task.setWorkerGroup("default"); task.setPriority("HIGH"); task.setExecutionStrategy("SERIAL_WAIT");
        task.setRetryCount(0); task.setRetryInterval(0); task.setDelayMinutes(0); return task;
    }
}
