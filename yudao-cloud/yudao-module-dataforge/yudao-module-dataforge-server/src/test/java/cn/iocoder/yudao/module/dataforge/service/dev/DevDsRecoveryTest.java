package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowInstanceApi;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DevDsRecoveryTest {
    private final DevDsSubmitter submitter = new DevDsSubmitter();
    private final DevRecordMapper records = mock(DevRecordMapper.class);
    private final DolphinSchedulerClient ds = mock(DolphinSchedulerClient.class);
    private final WorkflowInstanceApi instances = mock(WorkflowInstanceApi.class);
    private final QualityGateService quality = mock(QualityGateService.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final List<Map<String, Object>> writes = new ArrayList<>();
    private final List<String> assignments = new ArrayList<>();
    private final List<String> statusWrites = new ArrayList<>();
    private DevRecordDO record;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "dev-recovery"), DevRecordDO.class);
        ReflectionTestUtils.setField(submitter, "recordMapper", records);
        ReflectionTestUtils.setField(submitter, "dsClient", ds);
        ReflectionTestUtils.setField(submitter, "qualityGateService", quality);
        ReflectionTestUtils.setField(submitter, "executionAccessService", access);
        var support = new DevNativeRuntimeSupport();
        ReflectionTestUtils.setField(support, "properties", new cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties());
        DevNativeRuntimeTest.wireSslBundles(support);
        ReflectionTestUtils.setField(submitter, "nativeRuntimeSupport", support);
        DevTaskSnapshot snapshot = new DevTaskSnapshot();
        snapshot.setWorkerGroup("isolated-prod"); snapshot.setTenantCode("datahub_prod");
        snapshot.setTask(DevTaskDO.builder().id(1L).qualityTaskId(20L)
                .executionUserId(77L).taskType("SHELL").timeoutSeconds(300).content("test").build());
        record = DevRecordDO.builder().id(5L).taskId(1L)
                .versionId(2L).executionUserId(77L).businessDate(LocalDate.of(2026, 9, 15))
                .status("UNKNOWN").submissionStage("TRIGGER_SENT").dsProjectCode(9L).dsWorkflowCode(100L)
                .dsTriggerCode(999L).snapshotJson(JsonUtils.toJsonString(snapshot)).log("").build();
        when(records.selectById(5L)).thenReturn(record);
        when(ds.workflowInstance()).thenReturn(instances);
        when(records.update(isNull(), any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> wrapper = inv.getArgument(1);
            wrapper.getSqlSegment();
            if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> update) {
                assignments.add(update.getSqlSet());
                var status = java.util.regex.Pattern.compile("(?:^|,)status=#\\{ew\\.paramNameValuePairs\\.([^}]+)}").matcher(update.getSqlSet());
                if (status.find()) statusWrites.add((String) wrapper.getParamNameValuePairs().get(status.group(1)));
            }
            writes.add(new HashMap<>(wrapper.getParamNameValuePairs()));
            return 1;
        });
    }

    @Test void triggerAcceptedButInstanceDelayedDoesNotResubmitAfterRestart() {
        when(instances.queryWorkflowInstancesByTriggerCode(9L, 999L)).thenReturn(new JSONObject("{data:[]}"));
        submitter.process(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("UNKNOWN")));
        verify(ds, never()).executor();
        verify(ds, never()).workflowDefinition();
    }

    @Test void busyTaskIsRejectedWithBusinessReasonBeforeInsertOrEnginePreparation() {
        when(records.selectCount(any())).thenReturn(0L,1L);
        var failure=assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                ()->submitter.enqueue(DevTaskDO.builder().id(1L).build(),
                        DevTaskVersionDO.builder().id(2L).snapshotJson(record.getSnapshotJson()).build(),
                        "JOB","JOB:2:node",record.getBusinessDate()));
        assertEquals(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_TASK_RUNNING.getCode(),failure.getCode());
        verify(records,never()).insert(any(DevRecordDO.class));verifyNoInteractions(ds);
    }

    @Test void sameAcceptedRunKeyReturnsOriginalRecordBeforeBusyTaskCheck() {
        when(records.selectCount(any())).thenReturn(0L,1L);
        when(records.selectOne(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class),eq("JOB:2:node"))).thenReturn(record);
        assertEquals(5L,submitter.enqueue(DevTaskDO.builder().id(1L).build(),
                DevTaskVersionDO.builder().id(2L).snapshotJson(record.getSnapshotJson()).build(),
                "JOB","JOB:2:node",record.getBusinessDate()));
        verify(records,times(1)).selectCount(any());verify(records,never()).insert(any(DevRecordDO.class));
    }

    @Test void temporaryEngineOutageDoesNotMarkFailure() {
        when(instances.queryWorkflowInstancesByTriggerCode(9L, 999L)).thenThrow(new IllegalStateException("network failure"));
        submitter.process(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("UNKNOWN")));
        assertFalse(writes.stream().anyMatch(v -> v.containsValue("FAILED") || v.containsValue("STOPPED")));
    }

    @Test void cancellationPersistsWithoutAnInMemoryWorker() {
        submitter.stop(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue(Boolean.TRUE)));
        verifyNoInteractions(ds);
    }

    @Test void schedulerQueueKeepsTheExecutionSlotWithoutPretendingTheEngineIsRunning() {
        record.setDsInstanceId(8);
        for (String state : List.of("SERIAL_WAIT", "SUBMITTED_SUCCESS", "DELAY_EXECUTION", "DISPATCH")) {
            statusWrites.clear();
            when(instances.queryWorkflowInstanceById(9L, 8))
                    .thenReturn(new JSONObject().set("data", new JSONObject().set("state", state)));
            submitter.process(5L);
            assertTrue(statusWrites.contains("SUBMITTING"), state);
            assertFalse(statusWrites.contains("RUNNING"), state);
            assertFalse(statusWrites.contains("WAITING"), state);
        }
        verify(ds, never()).executor();
        verify(ds, never()).workflowDefinition();
    }

    @Test void missingOrUnrecognizedSchedulerStateRemainsUnconfirmed() {
        record.setDsInstanceId(8);
        when(instances.queryWorkflowInstanceById(9L, 8)).thenReturn(new JSONObject("{data:{state:'UNRECOGNIZED'}}"));
        submitter.process(5L);
        assertTrue(statusWrites.contains("UNKNOWN"));
        assertFalse(statusWrites.contains("RUNNING"));
        verify(ds, never()).executor();
    }

    @Test void leaseAlreadyOwnedByAnotherNodeDoesNoExternalWork() {
        doReturn(0).when(records).update(isNull(), any());
        submitter.process(5L);
        verifyNoInteractions(ds);
    }

    @Test void disabledSchedulerTimeoutIsZeroAndPositiveTimeoutRoundsUpWithoutOverflow() {
        for (Integer seconds : Arrays.asList(null, 0, -1, 1, 59, 60, 61, Integer.MAX_VALUE)) {
            DevTaskDO task = DevTaskDO.builder().name("超时边界验证").taskType("SHELL")
                    .timeoutSeconds(seconds).build();
            String json = ReflectionTestUtils.invokeMethod(submitter, "buildTaskDefinitionJson",
                    task, 100L, new JSONObject(), "default", null);
            JSONObject definition = cn.hutool.json.JSONUtil.parseArray(json).getJSONObject(0);
            boolean enabled = seconds != null && seconds > 0;
            assertEquals(enabled ? "OPEN" : "CLOSE", definition.getStr("timeoutFlag"));
            assertEquals(enabled ? (seconds.longValue() + 59) / 60 : 0, definition.getLong("timeout"));
        }
    }

    @Test void streamingWithoutTimeoutKeepsRunningAcrossLongIntervals() {
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setTaskType("FLINK_SQL"); snapshot.getTask().setProgramConfigJson("{\"workloadMode\":\"STREAMING\"}");
        snapshot.getTask().setTimeoutSeconds(0); record.setSnapshotJson(snapshot.write());
        record.setStartTime(LocalDateTime.now().minusDays(10)); record.setDsInstanceId(8);
        when(instances.queryWorkflowInstanceById(9L, 8)).thenReturn(new JSONObject("{data:{state:'RUNNING_EXECUTION'}}"));
        submitter.process(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("RUNNING")));
        assertFalse(assignments.stream().anyMatch(sql -> sql.contains("cancel_requested=")));
        verify(ds, never()).executor(); verify(ds, never()).workflowDefinition();
    }

    @Test void nativeSchedulerStopIsNotEngineTerminationEvidence() {
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setTaskType("FLINK_SQL"); snapshot.getTask().setProgramConfigJson("{}");
        record.setSnapshotJson(snapshot.write()); record.setDsInstanceId(8); record.setCancelRequested(true);
        when(instances.queryWorkflowInstanceById(9L, 8)).thenReturn(new JSONObject("{data:{state:'STOP'}}"));
        submitter.process(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("UNKNOWN")));
        assertFalse(writes.stream().anyMatch(v -> v.containsValue("STOPPED")));
        assertTrue(writes.stream().anyMatch(v -> v.values().stream().anyMatch(value -> value instanceof String text && text.contains("引擎终态尚未确认"))));
        verify(ds, never()).executor(); verifyNoInteractions(quality);
    }

    @Test void nativeCancellationReleasesSlotOnlyAfterAllMatchingYarnApplicationsAreTerminal() {
        var scenario = nativeObservationScenario();
        var killed = new cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.Application("application_1_1", "sandbox-user", "restricted", "dh_native_5",
                cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.KILLED,
                cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.KILLED);
        when(scenario.api().find("dh_native_5", "sandbox-user")).thenReturn(java.util.Collections.nCopies(64, killed));
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("STOPPED"));
        verify(scenario.api()).find("dh_native_5", "sandbox-user"); verifyNoInteractions(quality);
    }

    @Test void emptyRunningWrongIdentityOrFullLimitObservationsCannotConfirmCancellation() {
        for (String variant : List.of("empty", "running", "wrong-user", "wrong-queue", "wrong-name", "limit")) {
            var scenario = nativeObservationScenario(); writes.clear(); statusWrites.clear();
            var app = new cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.Application("application_1_1",
                    "wrong-user".equals(variant) ? "other" : "sandbox-user", "wrong-queue".equals(variant) ? "other" : "restricted",
                    "wrong-name".equals(variant) ? "other" : "dh_native_5",
                    "running".equals(variant) ? cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.RUNNING
                            : cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.KILLED,
                    "running".equals(variant) ? cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.UNDEFINED
                            : cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.KILLED);
            when(scenario.api().find(anyString(), anyString())).thenReturn("empty".equals(variant) ? List.of()
                    : "limit".equals(variant) ? java.util.Collections.nCopies(65, app) : List.of(app));
            scenario.submitter().process(5L);
            assertTrue(statusWrites.contains("UNKNOWN"), variant);
            assertFalse(statusWrites.contains("STOPPED"), variant);
        }
    }

    @Test void profileRotationPreventsObservationFromBeingRedirectedToAnotherCluster() {
        var scenario = nativeObservationScenario(); scenario.profile().setRevision("rotated");
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("UNKNOWN"));
        verifyNoInteractions(scenario.api());
    }

    @Test void localCancellationAfterSchedulerFailureRequiresAttestedExitOfTheEntireProcessTree() {
        var scenario = localObservationScenario();
        when(scenario.api().find("dh_native_5","sandbox-user")).thenReturn(List.of(
                new cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.Execution(
                        "dh_native_5","sandbox-user","FLINK","CANCELLED",143,false,true,true)));
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("STOPPED"));
        verify(scenario.submitter(),never()).yarnApi(any());
        verifyNoInteractions(quality);
    }

    @Test void localSchedulerSuccessCannotReleaseRunningDescendantsOrAnUnobservedExecution() {
        var scenario = localObservationScenario();
        record.setCancelRequested(false);
        when(instances.queryWorkflowInstanceById(19L,8)).thenReturn(new JSONObject("{data:{state:'SUCCESS'}}"));
        for (var observation : List.of(
                new cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.Execution(
                        "dh_native_5","sandbox-user","FLINK","RUNNING",null,true,true,false),
                new cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.Execution(
                        "dh_native_5","sandbox-user","FLINK","RUNNING",null,false,false,false))) {
            statusWrites.clear(); when(scenario.api().find(anyString(),anyString())).thenReturn(List.of(observation));
            scenario.submitter().process(5L);
            assertTrue(statusWrites.contains("UNKNOWN")); assertFalse(statusWrites.contains("SUCCESS"));
        }
        statusWrites.clear();
        when(scenario.api().find(anyString(),anyString())).thenReturn(List.of(
                new cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.Execution(
                        "dh_native_5","sandbox-user","FLINK","SUCCEEDED",0,false,true,true)));
        when(quality.evaluate(anyString(),any(),any())).thenReturn(new QualityGateService.Result("NOT_CONFIGURED",null,null));
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("SUCCESS"));
    }

    @Test void localObservationFailureAndProfileRotationKeepTheRunUnknownWithoutRedirectingObservation() {
        var scenario = localObservationScenario();
        when(scenario.api().find(anyString(),anyString())).thenThrow(new IllegalStateException("unavailable"));
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("UNKNOWN")); assertFalse(statusWrites.contains("STOPPED"));
        clearInvocations(scenario.api()); statusWrites.clear();
        scenario.profile().setLocalObserverEndpoint("https://rotated.invalid");
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("UNKNOWN")); verifyNoInteractions(scenario.api());
    }

    @Test void nativeSuccessRequiresEveryObservedApplicationToHaveSucceeded() {
        for (String variant : List.of("succeeded", "empty", "running", "failed", "killed", "mixed")) {
            var scenario = nativeObservationScenario(); statusWrites.clear(); record.setCancelRequested(false);
            when(instances.queryWorkflowInstanceById(19L, 8)).thenReturn(new JSONObject("{data:{state:'SUCCESS'}}"));
            var succeeded = yarnApplication(cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.FINISHED,
                    cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.SUCCEEDED);
            var bad = yarnApplication("running".equals(variant)
                    ? cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.RUNNING
                    : "killed".equals(variant) ? cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.KILLED
                    : cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.FAILED,
                    "running".equals(variant) ? cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.UNDEFINED
                    : "killed".equals(variant) ? cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.KILLED
                    : cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.FAILED);
            when(scenario.api().find(anyString(), anyString())).thenReturn("succeeded".equals(variant) ? List.of(succeeded)
                    : "empty".equals(variant) ? List.of() : "mixed".equals(variant) ? List.of(succeeded, bad) : List.of(bad));
            when(quality.evaluate(anyString(), any(), any())).thenReturn(new QualityGateService.Result("NOT_CONFIGURED", null, null));
            scenario.submitter().process(5L);
            assertTrue(statusWrites.contains("succeeded".equals(variant) ? "SUCCESS" : "UNKNOWN"), variant);
            assertEquals("succeeded".equals(variant), statusWrites.contains("SUCCESS"), variant);
        }
    }

    @Test void nativeFailureAlsoRetainsTheSlotUntilEngineTerminationIsVerified() {
        var scenario = nativeObservationScenario();
        when(instances.queryWorkflowInstanceById(19L, 8)).thenReturn(new JSONObject("{data:{state:'FAILURE'}}"));
        when(scenario.api().find(anyString(), anyString())).thenReturn(List.of());
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("UNKNOWN")); assertFalse(statusWrites.contains("FAILED"));
        statusWrites.clear();
        when(scenario.api().find(anyString(), anyString())).thenReturn(List.of(yarnApplication(
                cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State.FAILED,
                cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus.FAILED)));
        scenario.submitter().process(5L);
        assertTrue(statusWrites.contains("FAILED"));
    }

    @Test void revokedRunOrNativePermissionBeforeTriggerIsAConfirmedUnsentFailure() {
        for (String permission : List.of(ExecutionAccessService.PERMISSION_DEV_TASK_RUN, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE)) {
            reset(access); statusWrites.clear();
            var scenario = nativeObservationScenario(); record.setSubmissionStage("PREPARE"); record.setCancelRequested(false); record.setExecutionUserId(42L);
            var snapshot = DevTaskSnapshot.read(record.getSnapshotJson()); snapshot.setWorkerGroup(scenario.profile().getWorkerGroup()); snapshot.setTenantCode(scenario.profile().getTenantCode());
            record.setSnapshotJson(snapshot.write());
            var definitions = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi.class);
            when(ds.workflowDefinition()).thenReturn(definitions);
            when(definitions.releaseWorkflowDefinition(19L, 100L, "ONLINE", "dh_dev_run_5")).thenAnswer(inv -> {
                doThrow(new cn.iocoder.yudao.framework.common.exception.ServiceException(1000, "revoked"))
                        .when(access).requireUserPermission(42L, permission);
                return new JSONObject();
            });
            scenario.submitter().process(5L);
            assertTrue(statusWrites.contains("FAILED"), permission); assertFalse(statusWrites.contains("UNKNOWN"), permission);
            assertEquals("PREPARE", record.getSubmissionStage());
            verify(ds, never()).executor();
        }
    }

    private static cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.Application yarnApplication(
            cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.State state,
            cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.FinalStatus finalStatus) {
        return new cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.Application(
                "application_1_1", "sandbox-user", "restricted", "dh_native_5", state, finalStatus);
    }

    private NativeObservation nativeObservationScenario() {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(7L);
        var profile = DevNativeRuntimeTest.profile("FLINK"); profile.setYarnEndpoint("https://yarn.invalid/proxy"); profile.setYarnSslBundle("test-yarn");
        var properties = new cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties(); properties.setProfiles(List.of(profile));
        var support = new DevNativeRuntimeSupport(); ReflectionTestUtils.setField(support, "properties", properties);
        DevNativeRuntimeTest.wireSslBundles(support);
        ReflectionTestUtils.setField(support, "executionAccessService", access);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(DevNativeRuntimeTest.task("FLINK_SQL", profile)); support.freeze(snapshot);
        record.setSnapshotJson(snapshot.write()); record.setDsProjectCode(19L); record.setDsInstanceId(8); record.setCancelRequested(true); record.setStatus("UNKNOWN");
        when(instances.queryWorkflowInstanceById(19L, 8)).thenReturn(new JSONObject("{data:{state:'STOP'}}"));
        var current = spy(submitter); ReflectionTestUtils.setField(current, "nativeRuntimeSupport", support);
        var api = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi.class); doReturn(api).when(current).yarnApi(profile);
        return new NativeObservation(current, api, profile);
    }
    private record NativeObservation(DevDsSubmitter submitter, cn.iocoder.yudao.framework.dolphinscheduler.api.YarnApplicationApi api,
                                     cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties.Profile profile) { }

    private LocalObservation localObservationScenario() {
        var profile = DevLocalNativeRuntimeTest.profile("FLINK");
        var properties = new cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties(); properties.setProfiles(List.of(profile));
        var support = new DevNativeRuntimeSupport(); ReflectionTestUtils.setField(support,"properties",properties);
        DevNativeRuntimeTest.wireSslBundles(support); ReflectionTestUtils.setField(support,"executionAccessService",access);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(DevNativeRuntimeTest.task("FLINK_SQL",profile)); support.freeze(snapshot);
        record.setSnapshotJson(snapshot.write()); record.setDsProjectCode(19L); record.setDsInstanceId(8);
        record.setCancelRequested(true); record.setStatus("UNKNOWN");
        when(instances.queryWorkflowInstanceById(19L,8)).thenReturn(new JSONObject("{data:{state:'FAILURE'}}"));
        var current = spy(submitter); ReflectionTestUtils.setField(current,"nativeRuntimeSupport",support);
        var api = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.class);
        doReturn(api).when(current).localApi(profile);
        return new LocalObservation(current,api,profile);
    }

    private record LocalObservation(DevDsSubmitter submitter, cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi api,
                                    cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties.Profile profile) { }

    @AfterEach void clearTenant() { cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear(); }

    @Test void qualityFailurePreservesSuccessfulExecution() {
        record.setDsInstanceId(8);
        when(instances.queryWorkflowInstanceById(9L, 8)).thenReturn(new JSONObject("{data:{state:'SUCCESS'}}"));
        when(quality.evaluate("DEV:5", 20L, 77L)).thenReturn(new QualityGateService.Result("FAILED", 30L, "rule failed"));
        submitter.process(5L);
        assertEquals("SUCCESS", record.getStatus());
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("SUCCESS")));
        assertTrue(writes.stream().anyMatch(v -> v.containsValue(30L)));
        verify(ds, never()).executor();
    }

    @Test void aRecoveredCreatedWorkflowIsReleasedBeforeTriggering() {
        record.setSubmissionStage("PREPARE"); record.setDsTriggerCode(null); record.setCancelRequested(false);
        cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi definitions =
                mock(cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi.class);
        cn.iocoder.yudao.framework.dolphinscheduler.api.ExecutorApi executor =
                mock(cn.iocoder.yudao.framework.dolphinscheduler.api.ExecutorApi.class);
        when(ds.workflowDefinition()).thenReturn(definitions);
        when(ds.executor()).thenReturn(executor);
        when(executor.triggerWorkflowDefinition(eq(9L), any())).thenReturn(new JSONObject("{data:[999]}"));
        when(instances.queryWorkflowInstanceById(9L, 999))
                .thenReturn(new JSONObject("{data:{state:'RUNNING_EXECUTION'}}"));
        submitter.process(5L);
        org.mockito.InOrder order = inOrder(definitions, executor);
        order.verify(definitions).releaseWorkflowDefinition(9L, 100L, "ONLINE", "dh_dev_run_5");
        order.verify(executor).triggerWorkflowDefinition(eq(9L), any());
        var request = org.mockito.ArgumentCaptor.forClass(
                cn.iocoder.yudao.framework.dolphinscheduler.request.TriggerWorkflowDefinitionRequest.class);
        verify(executor).triggerWorkflowDefinition(eq(9L), request.capture());
        assertEquals("", request.getValue().getScheduleTime());
        assertEquals(999, record.getDsInstanceId());
        assertNull(record.getDsTriggerCode());
        verify(instances).queryWorkflowInstanceById(9L, 999);
        verify(instances, never()).queryWorkflowInstancesByTriggerCode(anyLong(), anyLong());
        verify(definitions, never()).createWorkflowDefinition(anyLong(), any());
    }

    @Test void ambiguousOrInvalidInstanceResponseKeepsUnknownWithoutResubmitting() {
        var definitions = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi.class);
        var executor = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.ExecutorApi.class);
        when(ds.workflowDefinition()).thenReturn(definitions);
        when(ds.executor()).thenReturn(executor);
        when(instances.queryWorkflowInstanceList(eq(9L), any()))
                .thenReturn(new JSONObject("{data:{totalList:[]}}"));
        for (String data : List.of("[]", "[999,1000]", "[null]", "[-1]", "[1.5]", "[2147483648]", "999")) {
            statusWrites.clear();
            clearInvocations(executor, instances);
            record.setSubmissionStage("PREPARE"); record.setDsTriggerCode(null);
            record.setDsInstanceId(null); record.setCancelRequested(false);
            when(executor.triggerWorkflowDefinition(eq(9L), any()))
                    .thenReturn(new JSONObject("{data:" + data + "}"));
            submitter.process(5L);
            assertTrue(statusWrites.contains("UNKNOWN"), data);
            assertNull(record.getDsTriggerCode(), data);
            assertNull(record.getDsInstanceId(), data);
            assertEquals("TRIGGER_SENT", record.getSubmissionStage(), data);
            submitter.process(5L);
            verify(executor, times(1)).triggerWorkflowDefinition(eq(9L), any());
            verify(instances).queryWorkflowInstanceList(eq(9L), any());
            verify(instances, never()).queryWorkflowInstanceById(anyLong(), anyInt());
        }
    }

    @Test void cancellationBeforeDispatchNeverTriggersTheEngine() {
        record.setSubmissionStage("PREPARE"); record.setCancelRequested(true);
        submitter.process(5L);
        assertEquals("STOPPED", record.getStatus());
        verifyNoInteractions(ds);
    }

    @Test void confirmedSuccessOnlyReconcilesQualityWithoutReopeningExecution() {
        record.setStatus("SUCCESS"); record.setQualityStatus("PENDING");
        when(quality.evaluate("DEV:5", 20L, 77L)).thenReturn(new QualityGateService.Result("PENDING", 30L, "running"));
        submitter.process(5L);
        verifyNoInteractions(ds);
        assertEquals("SUCCESS", record.getStatus());
        assertFalse(assignments.stream().anyMatch(sql -> java.util.regex.Pattern.compile("(^|,)status=").matcher(sql).find()));
    }

    @Test void aLegacyUnknownRunRequiresHumanReconciliation() {
        record.setSnapshotJson(null); record.setExecutionUserId(null);
        submitter.process(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("UNKNOWN")));
        assertFalse(writes.stream().anyMatch(v -> v.containsValue("FAILED")));
        verifyNoInteractions(ds);
    }

    @Test void recoveringANativeDraftCannotDispatchItAsSql() {
        record.setSubmissionStage("PREPARE"); record.setDsTriggerCode(null);
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setTaskType("PYSPARK");
        record.setSnapshotJson(snapshot.write());
        submitter.process(5L);
        assertTrue(writes.stream().anyMatch(v -> v.containsValue("FAILED")));
        verifyNoInteractions(ds);
    }
    @Test void dedicatedJobChildrenUseParallelWhileDirectDevelopmentKeepsItsPolicy() {
        var definitions=mock(cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi.class);
        var tasks=mock(cn.iocoder.yudao.framework.dolphinscheduler.api.TaskDefinitionApi.class);
        when(ds.workflowDefinition()).thenReturn(definitions);when(ds.taskDefinition()).thenReturn(tasks);
        when(definitions.queryWorkflowDefinitionListPaging(9L,"dh_dev_run_5",null,null,1,100)).thenReturn(new JSONObject("{code:0,data:{totalList:[]}}"));
        when(tasks.genTaskCodeList(9L,1)).thenReturn(new JSONObject("{code:0,data:[101]}"));
        when(definitions.createWorkflowDefinition(eq(9L),any())).thenReturn(new JSONObject("{code:0,data:{code:201}}"));
        var task=DevTaskSnapshot.read(record.getSnapshotJson()).getTask();task.setName("专属子定义策略回归");
        for(String trigger:List.of("JOB","MANUAL")) {
            clearInvocations(definitions);record.setDsWorkflowCode(null);record.setTriggerType(trigger);
            Long definition=ReflectionTestUtils.invokeMethod(submitter,"ensureRunWorkflow",record,task,new JSONObject(),"owner","isolated-prod");assertEquals(201L,definition);
            var request=org.mockito.ArgumentCaptor.forClass(cn.iocoder.yudao.framework.dolphinscheduler.request.CreateWorkflowDefinitionRequest.class);
            verify(definitions).createWorkflowDefinition(eq(9L),request.capture());
            assertEquals("JOB".equals(trigger)?"PARALLEL":"SERIAL_WAIT",request.getValue().getExecutionType());
            verify(ds,never()).executor();
        }
    }

}
