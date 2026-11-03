package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.*;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DevTaskSaveTest {
    private final DevTaskServiceImpl service = new DevTaskServiceImpl();
    private final DevTaskMapper tasks = mock(DevTaskMapper.class);
    private final DevTaskVersionMapper versions = mock(DevTaskVersionMapper.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final QualityGateService quality = mock(QualityGateService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final DevDsSubmitter submitter = mock(DevDsSubmitter.class);
    private final DevScheduleCoordinator coordinator = mock(DevScheduleCoordinator.class);
    private DevTaskDO task;
    private final List<DevTaskVersionDO> savedVersions = new ArrayList<>();

    @BeforeEach void setup() {
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(
                new cn.iocoder.yudao.framework.security.core.LoginUser().setId(77L).setTenantId(7L),
                new org.springframework.mock.web.MockHttpServletRequest());
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "dev-save-test"), DevTaskDO.class);
        var nativeRuntime = new DevNativeRuntimeSupport();
        ReflectionTestUtils.setField(nativeRuntime, "properties", new cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties());
        ReflectionTestUtils.setField(service, "nativeRuntimeSupport", nativeRuntime);
        ReflectionTestUtils.setField(service, "taskMapper", tasks); ReflectionTestUtils.setField(service, "versionMapper", versions);
        ReflectionTestUtils.setField(service, "executionAccessService", access); ReflectionTestUtils.setField(service, "qualityGateService", quality);
        ReflectionTestUtils.setField(service, "transactionTemplate", tx); ReflectionTestUtils.setField(service, "dirService", mock(DevDirService.class));
        when(submitter.configuredEnvironmentCode()).thenReturn(-1L);
        when(submitter.executionTarget(any())).thenReturn(new DevDsSubmitter.ExecutionTarget("isolated-test", "datahub_test"));
        ReflectionTestUtils.setField(service, "dsSubmitter", submitter);
        ReflectionTestUtils.setField(service, "scheduleCoordinator", coordinator);
        when(coordinator.zone()).thenReturn(java.time.ZoneId.of("Asia/Shanghai"));
        task = DevTaskDO.builder().id(1L).revision(3).name("task").dirId(0L).taskType("SHELL").scheduler("DOLPHIN_SCHEDULER")
                .content("original script").qualityTaskId(20L).executionUserId(77L).scheduleStatus(0).status(0)
                .timeoutSeconds(300).maxRows(1000).latestVersion(0).dependencyTaskIdsJson("[]").build();
        when(tasks.selectById(1L)).thenReturn(task); when(tasks.update(any(), any())).thenReturn(1);
        when(tasks.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(inv -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) inv.getArgument(0)).accept(new SimpleTransactionStatus()); return null; }).when(tx).executeWithoutResult(any());
        doAnswer(inv -> { DevTaskDO value = inv.getArgument(0); value.setId(1L); return 1; }).when(tasks).insert(any(DevTaskDO.class));
        doAnswer(inv -> { DevTaskVersionDO value = inv.getArgument(0); value.setId(100L + savedVersions.size()); savedVersions.add(value); return 1; }).when(versions).insert(any(DevTaskVersionDO.class));
    }

    private DevTaskSaveReqVO request() {
        var req = new DevTaskSaveReqVO(); req.setId(1L); req.setName("task"); req.setDirId(0L);
        req.setTaskType(task.getTaskType()); req.setContent("saved script"); req.setExpectedRevision(3);
        req.setExecutionUserId(77L); req.setTimeoutSeconds(300); req.setMaxRows(1000); req.setQualityTaskId(20L);
        return req;
    }

    @AfterEach void clearLogin() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void savingCompleteTaskImmediatelySelectsAnImmutableVersion() {
        task.setCurrentVersionId(80L); task.setStatus(1);
        service.updateTask(request());
        var update = captureUpdate(); assertEquals(100L, assignment(update, "current_version_id"));
        assertEquals(1, assignment(update, "latest_version")); assertEquals(1, assignment(update, "status")); assertEquals(4, assignment(update, "revision"));
        task.setContent("later unsaved edit"); task.setTimeoutSeconds(900);
        var snapshot = DevTaskSnapshot.read(savedVersions.getFirst().getSnapshotJson());
        assertEquals("saved script", snapshot.getTask().getContent()); assertEquals(300, snapshot.getTask().getTimeoutSeconds());
        assertEquals(77L, snapshot.getTask().getExecutionUserId());
        verify(access).requireUserPermission(77L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN); verify(quality).validate(20L, 77L);
        verify(tx).executeWithoutResult(any()); verify(submitter, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test void metadataCreationHasNoExecutableVersionAndCannotRun() {
        var req = request(); req.setId(null); req.setContent(null);
        assertEquals(1L, service.createTask(req));
        var captured = org.mockito.ArgumentCaptor.forClass(DevTaskDO.class); verify(tasks).insert(captured.capture());
        assertNull(captured.getValue().getCurrentVersionId()); assertEquals(0, captured.getValue().getLatestVersion()); assertEquals(0, captured.getValue().getStatus());
        verifyNoInteractions(versions, submitter, quality);
        var run = new DevTaskRunReqVO(); run.setId(1L); run.setRequestId("metadata-create"); run.setBusinessDate(java.time.LocalDate.now());
        assertThrows(ServiceException.class, () -> service.runTask(run));
    }

    @Test void creationRechecksTheDirectoryInsideItsLocalWriteTransaction() {
        var req = request(); req.setId(null); req.setContent(null);
        var directories = (DevDirService) ReflectionTestUtils.getField(service, "dirService");
        var writing = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            writing.set(true);
            try { return ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()); }
            finally { writing.set(false); }
        }).when(tx).execute(any());
        doAnswer(invocation -> {
            assertTrue(writing.get());
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_NOT_EXISTS);
        }).when(directories).lockAndValidateDirExists(0L);
        assertThrows(ServiceException.class, () -> service.createTask(req));
        verify(tasks, never()).insert(any(DevTaskDO.class));
    }

    @Test void updateRechecksTheDirectoryInsideItsLocalWriteTransaction() {
        var req = request(); req.setContent(null);
        var directories = (DevDirService) ReflectionTestUtils.getField(service, "dirService");
        var writing = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            writing.set(true);
            try { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0))
                    .accept(new SimpleTransactionStatus()); return null; }
            finally { writing.set(false); }
        }).when(tx).executeWithoutResult(any());
        doAnswer(invocation -> {
            assertTrue(writing.get());
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_NOT_EXISTS);
        }).when(directories).lockAndValidateDirExists(0L);
        assertThrows(ServiceException.class, () -> service.updateTask(req));
        verify(tasks, never()).update(any(), any());
        verifyNoInteractions(versions);
    }

    @Test void completeCreationCreatesCurrentVersionInTheCreationTransaction() {
        var req = request(); req.setId(null);
        assertEquals(1L, service.createTask(req));
        assertEquals(1L, savedVersions.getFirst().getTaskId()); assertEquals(100L, assignment(captureUpdate(), "current_version_id"));
        verify(tx).execute(any()); verify(tx, never()).executeWithoutResult(any());
    }

    @Test void flinkAndSparkMetadataCanBeCreatedWithoutAnyDatabaseBinding() {
        for (String type : List.of("FLINK_SQL", "SPARK_SQL")) {
            var req = request(); req.setId(null); req.setTaskType(type); req.setContent(null);
            req.setProgramConfig(new cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig());
            assertEquals(1L, service.createTask(req));
        }
        var captured = org.mockito.ArgumentCaptor.forClass(DevTaskDO.class);
        verify(tasks, times(2)).insert(captured.capture());
        captured.getAllValues().forEach(value -> { assertNull(value.getDatabaseId()); assertNull(value.getCurrentVersionId()); });
        verifyNoInteractions(versions, access, submitter);
    }

    @Test void incompleteNewTaskCanUpdateMetadataWithoutCreatingAFalseVersion() {
        task.setStatus(1); task.setScheduleStatus(1); task.setNextRunTime(java.time.LocalDateTime.now());
        var req = request(); req.setContent(null); req.setDescription("metadata only");
        service.updateTask(req);
        var update = captureUpdate(); assertNull(assignment(update, "current_version_id"));
        assertEquals(0, assignment(update, "status")); assertEquals(0, assignment(update, "schedule_status"));
        assertNull(assignment(update, "next_run_time"));
        verify(versions, never()).insert(any(DevTaskVersionDO.class)); verifyNoInteractions(submitter);
    }

    @Test void completeDefinitionCannotBeReplacedByAnEmptyScript() {
        task.setCurrentVersionId(80L); task.setStatus(1); var req = request(); req.setContent(" ");
        assertThrows(ServiceException.class, () -> service.updateTask(req));
        verifyNoInteractions(tx, versions); verify(tasks, never()).update(any(), any()); assertEquals("original script", task.getContent());
    }

    @Test void staleSaveCannotCreateAVersion() {
        var req = request(); req.setExpectedRevision(2);
        assertThrows(ServiceException.class, () -> service.updateTask(req)); verifyNoInteractions(tx, versions, submitter);
    }

    @Test void scriptPreflightFailurePreventsAnyDefinitionOrVersionWrite() {
        doThrow(new ServiceException(1000, "script denied")).when(submitter).validateForSave(any());
        assertThrows(ServiceException.class, () -> service.updateTask(request()));
        verifyNoInteractions(tx, versions); verify(tasks, never()).update(any(), any()); assertEquals("original script", task.getContent());
    }

    @Test void missingExecutionPermissionIsRejectedBeforeTheTransaction() {
        doThrow(new ServiceException(1001, "identity denied")).when(access).requireUserPermission(77L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        assertThrows(ServiceException.class, () -> service.updateTask(request())); verifyNoInteractions(tx, versions);
    }

    @Test void nativeSaveChecksBothActorsAndFreezesTheManagedEnvironment() {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.setTenantId(7L);
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(
                new cn.iocoder.yudao.framework.security.core.LoginUser().setId(7L).setTenantId(7L), new org.springframework.mock.web.MockHttpServletRequest());
        try {
            var profile = DevNativeRuntimeTest.profile("FLINK"); var properties = new cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties(); properties.setProfiles(List.of(profile));
            var support = new DevNativeRuntimeSupport(); ReflectionTestUtils.setField(support, "properties", properties); ReflectionTestUtils.setField(support, "executionAccessService", access); DevNativeRuntimeTest.wireSslBundles(support);
            ReflectionTestUtils.setField(service, "nativeRuntimeSupport", support);
            task = DevNativeRuntimeTest.task("FLINK_SQL", profile); task.setRevision(3); task.setLatestVersion(0); task.setDependencyTaskIdsJson("[]"); when(tasks.selectById(1L)).thenReturn(task);
            var req = request(); req.setExecutionUserId(7L); req.setProgramConfig(DevTaskDefinition.readConfig(task.getProgramConfigJson()));
            service.updateTask(req);
            verify(access, times(2)).requireUserPermission(7L, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
            var snapshot = DevTaskSnapshot.read(savedVersions.getFirst().getSnapshotJson());
            assertNull(snapshot.getTask().getDatabaseId());
            assertEquals(19L, snapshot.getNativeProjectCode()); assertEquals(profile.getRevision(), snapshot.getNativeProfileRevision()); assertNotNull(snapshot.getNativeProfileFingerprint());
            profile.setDsProjectCode(29L); assertThrows(ServiceException.class, () -> support.requireFrozen(snapshot));
        } finally { cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void missingOrCyclicUpstreamVersionsAreRejectedBeforeTheSave() {
        var req = request(); req.setDependencyTaskIds(List.of(2L));
        when(tasks.selectById(2L)).thenReturn(DevTaskDO.builder().id(2L).build());
        assertThrows(ServiceException.class, () -> service.updateTask(req)); verifyNoInteractions(tx, versions);
        when(tasks.selectById(2L)).thenReturn(DevTaskDO.builder().id(2L).currentVersionId(200L).build());
        var upstream = new DevTaskSnapshot(); upstream.setTask(DevTaskDO.builder().id(2L).build()); upstream.setDependencyVersions(Map.of(1L, 90L));
        var previousSelf = new DevTaskSnapshot(); previousSelf.setTask(task);
        when(versions.selectById(200L)).thenReturn(DevTaskVersionDO.builder().snapshotJson(upstream.write()).build()); when(versions.selectById(90L)).thenReturn(DevTaskVersionDO.builder().snapshotJson(previousSelf.write()).build());
        assertThrows(ServiceException.class, () -> service.updateTask(req)); verify(versions, never()).insert(any(DevTaskVersionDO.class));
    }

    @Test void saveClearsRemovedOptionalConfigurationExplicitly() {
        var req = request(); service.updateTask(req); var update = captureUpdate();
        for (String column : List.of("program_config_json", "database_id", "datasource_name", "database_name", "db_type", "ds_datasource_id", "ds_datasource_type", "cron_expression")) assertNull(assignment(update, column), column);
    }

    @Test void rollbackCreatesANewCurrentVersionWithoutModifyingTheHistoricalSnapshot() {
        task.setCurrentVersionId(80L); task.setStatus(1); task.setLatestVersion(4);
        var previous = DevTaskDO.builder().id(1L).name("earlier name").dirId(0L).taskType("SHELL").content("previous")
                .timeoutSeconds(60).maxRows(250).description("earlier description").cronExpression("0 0 * * * *").dependencyTaskIdsJson("[]").executionUserId(77L).build();
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(previous); String historicalJson = snapshot.write();
        var historical = DevTaskVersionDO.builder().taskId(1L).version(2).snapshotJson(historicalJson).build(); when(versions.selectById(70L)).thenReturn(historical);
        service.rollbackVersion(70L, 3); var update = captureUpdate();
        assertEquals("previous", assignment(update, "content")); assertEquals("earlier name", assignment(update, "name")); assertEquals(60, assignment(update, "timeout_seconds"));
        assertEquals(250, assignment(update, "max_rows")); assertEquals(5, assignment(update, "latest_version")); assertEquals(100L, assignment(update, "current_version_id"));
        assertEquals(historicalJson, historical.getSnapshotJson()); verify(versions, never()).updateById(any(DevTaskVersionDO.class));
    }

    @Test void foreignSnapshotOrConcurrentRollbackIsRejected() {
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(DevTaskDO.builder().id(2L).taskType("SHELL").build());
        when(versions.selectById(70L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        assertThrows(ServiceException.class, () -> service.rollbackVersion(70L, 3)); snapshot.getTask().setId(1L);
        when(versions.selectById(70L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        assertThrows(ServiceException.class, () -> service.rollbackVersion(70L, 2)); verifyNoInteractions(tx);
    }

    @Test void deletedOfflineTaskKeepsItsVersionHistory() {
        task.setCurrentVersionId(80L); task.setStatus(0); service.deleteTask(1L);
        verify(tasks).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)); verify(versions, never()).deleteByTaskId(anyLong());
    }

    @Test void enablingSavedVersionChecksItsIdentityAndAdvancesRevision() {
        task.setCurrentVersionId(80L); task.setStatus(0);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        service.updateTaskStatus(1L, 1);
        verify(access).requireUserPermission(77L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        assertEquals(4, assignment(captureUpdate(), "revision"));
    }

    @Test void changingScheduleUsesSavedCronAndAdvancesRevision() {
        task.setCurrentVersionId(80L); task.setStatus(1); task.setCronExpression("0 * * * * ?");
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        var req = new DevTaskScheduleReqVO(); req.setId(1L); req.setScheduleStatus(1); req.setCronExpression(task.getCronExpression());
        service.updateTaskSchedule(req);
        var update = captureUpdate(); assertEquals(4, assignment(update, "revision")); assertNull(assignment(update, "next_run_time")); verify(coordinator).saveIntent(any(), any());
    }

    @Test void manualRunCannotEnqueueAfterTheTaskWasDeletedBetweenPreflightAndLock() {
        task.setCurrentVersionId(80L); task.setStatus(1);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        when(tasks.selectForUpdate(1L)).thenReturn(null);
        var run = new DevTaskRunReqVO(); run.setId(1L); run.setRequestId("deleted-race"); run.setBusinessDate(java.time.LocalDate.now());
        assertThrows(ServiceException.class, () -> service.runTask(run));
        verify(submitter, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test void legacySpringCronCannotBeEnabledAsQuartzWithoutSavingANewDefinition() {
        task.setCurrentVersionId(80L); task.setStatus(1); task.setScheduler("XXL_JOB"); task.setCronExpression("0 0 0 ? * 1");
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        var req = new DevTaskScheduleReqVO(); req.setId(1L); req.setScheduleStatus(1);
        assertThrows(ServiceException.class, () -> service.updateTaskSchedule(req)); verifyNoInteractions(tx, coordinator);
    }

    @Test void disablingNativeScheduleDoesNotNeedTheRevokedHistoricalActorOrRuntime() {
        var profile = DevNativeRuntimeTest.profile("FLINK"); task = DevNativeRuntimeTest.task("FLINK_SQL", profile);
        task.setCurrentVersionId(80L); task.setStatus(1); task.setScheduleStatus(1); task.setRevision(3);
        when(tasks.selectById(1L)).thenReturn(task);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        var nativeSupport = mock(DevNativeRuntimeSupport.class); ReflectionTestUtils.setField(service, "nativeRuntimeSupport", nativeSupport);
        var req = new DevTaskScheduleReqVO(); req.setId(1L); req.setScheduleStatus(0);
        service.updateTaskSchedule(req); verifyNoInteractions(nativeSupport, access); verify(coordinator).saveIntent(any(), any());
        assertEquals(0, assignment(captureUpdate(), "schedule_status"));
    }

    @Test void enablingScheduleChecksTheSavedActorPermissionBeforeWritingAnyIntent() {
        task.setCurrentVersionId(80L); task.setStatus(1); task.setCronExpression("0 * * * * ?");
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().taskId(1L).snapshotJson(snapshot.write()).build());
        doThrow(new ServiceException(1000, "revoked")).when(access).requireUserPermission(77L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        var req = new DevTaskScheduleReqVO(); req.setId(1L); req.setScheduleStatus(1);
        assertThrows(ServiceException.class, () -> service.updateTaskSchedule(req)); verifyNoInteractions(tx, coordinator);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<DevTaskDO> captureUpdate() {
        var captured = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class); verify(tasks).update(any(), captured.capture()); return captured.getValue();
    }

    private Object assignment(LambdaUpdateWrapper<DevTaskDO> wrapper, String column) {
        var matcher = java.util.regex.Pattern.compile("(?:^|,)" + column + "=\\#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)(?:,[^}]*)?}").matcher(wrapper.getSqlSet());
        assertTrue(matcher.find(), () -> "缺少更新字段 " + column + "：" + wrapper.getSqlSet()); return wrapper.getParamNameValuePairs().get(matcher.group(1));
    }
}
