package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationTaskServiceTest {
    private final IntegrationTaskService service = new IntegrationTaskService();
    private final IntegrationTaskMapper tasks = mock(IntegrationTaskMapper.class);
    private final EtlPipelineService pipelines = mock(EtlPipelineService.class);
    private final IntegrationCategoryMapper categories = mock(IntegrationCategoryMapper.class);
    private final IntegrationRecordMapper records = mock(IntegrationRecordMapper.class);
    private final AdminUserApi users = mock(AdminUserApi.class);

    @BeforeEach void inject() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "integration"),
                IntegrationTaskDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "integration-category"),
                IntegrationCategoryDO.class);
        ReflectionTestUtils.setField(service, "taskMapper", tasks);
        ReflectionTestUtils.setField(service, "categoryMapper", categories);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        ReflectionTestUtils.setField(service, "pipelineService", pipelines);
    }

    @Test void dataxDraftCannotSaveAnExtraComponentBeforeDatabaseMutationOrDsPublish() {
        var task = task(); task.setEngine("DATAX"); task.setMode("OFFLINE"); when(tasks.selectById(7L)).thenReturn(task);
        ReflectionTestUtils.setField(service, "flowCompiler", new IntegrationFlowCompiler());
        var ds = mock(IntegrationDsRuntime.class); ReflectionTestUtils.setField(service, "dsRuntime", ds);
        var request = new FlowReq(); request.setId(7L); request.setRevision(0); request.setDraft(true);
        request.setFlowJson("{\"nodes\":[{\"id\":\"a\",\"properties\":{\"toolCode\":\"read-csv\",\"nodeJson\":{}}}],\"edges\":[]}");
        assertThrows(ServiceException.class, () -> service.saveFlow(request));
        verify(tasks, never()).update(any(), any(Wrapper.class)); verifyNoInteractions(ds);
    }

    @Test void scriptPublicationActivatesOnlyAfterDsPublishAndSuccessfulDatabaseCas() {
        var saving = spy(service); doReturn(new TaskResp()).when(saving).get(7L);
        var compiler = mock(IntegrationFlowCompiler.class); var ds = mock(IntegrationDsRuntime.class);
        ReflectionTestUtils.setField(saving, "flowCompiler", compiler); ReflectionTestUtils.setField(saving, "dsRuntime", ds);
        var task = task(); when(tasks.selectById(7L)).thenReturn(task);
        var publication = new IntegrationFlowCompiler.Publication(List.of(), null); when(compiler.compileForPublication(eq(task), anyString())).thenReturn(publication);
        when(ds.publish(eq(task), anyList())).thenReturn(91L); when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(1);
        var request = new FlowReq(); request.setId(7L); request.setRevision(0); request.setFlowJson("{}"); request.setDraft(false);
        saving.saveFlow(request);
        var order = inOrder(compiler, ds, tasks); order.verify(compiler).compileForPublication(task, "{}"); order.verify(ds).publish(task, publication.steps());
        order.verify(tasks).update(isNull(), any(Wrapper.class)); order.verify(compiler).completePublication(publication);
        verify(compiler, never()).abortPublication(any());
    }

    @Test void dsPublishOrDatabaseCasFailureDiscardsInactiveScriptPublication() {
        for (boolean publishFails : List.of(true, false)) {
            var compiler = mock(IntegrationFlowCompiler.class); var ds = mock(IntegrationDsRuntime.class);
            ReflectionTestUtils.setField(service, "flowCompiler", compiler); ReflectionTestUtils.setField(service, "dsRuntime", ds);
            var task = task(); when(tasks.selectById(7L)).thenReturn(task);
            var publication = new IntegrationFlowCompiler.Publication(List.of(), null); when(compiler.compileForPublication(eq(task), anyString())).thenReturn(publication);
            if (publishFails) when(ds.publish(eq(task), anyList())).thenThrow(new IllegalStateException("DS unavailable"));
            else { when(ds.publish(eq(task), anyList())).thenReturn(91L); when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(0); }
            var request = new FlowReq(); request.setId(7L); request.setRevision(0); request.setFlowJson("{}"); request.setDraft(false);
            assertThrows(RuntimeException.class, () -> service.saveFlow(request));
            verify(compiler).abortPublication(publication); verify(compiler, never()).completePublication(any());
        }
    }

    @Test void schemaOnlyReadsTaskAndNeverTouchesDsOrExecutionRecords() {
        var schema = mock(IntegrationSchemaService.class); var ds = mock(IntegrationDsRuntime.class);
        ReflectionTestUtils.setField(service, "schemaService", schema); ReflectionTestUtils.setField(service, "dsRuntime", ds);
        var task = task(); when(tasks.selectById(7L)).thenReturn(task);
        var request = new FlowReq(); request.setId(7L); request.setRevision(0); request.setFlowJson("{\"nodes\":[],\"edges\":[]}");
        var result = new cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult(); when(schema.resolve(task, request.getFlowJson())).thenReturn(result);
        assertSame(result, service.schema(request)); verify(tasks).selectById(7L); verifyNoMoreInteractions(tasks);
        verifyNoInteractions(ds, pipelines, records);
        request.setRevision(1); assertCode(INTEGRATION_CONFLICT.getCode(), () -> service.schema(request));
        verify(schema, times(1)).resolve(any(), anyString());
    }

    @Test void importedRunningSummaryCannotStartOrStopALocalProcess() {
        var task = task(); task.setImportedSnapshot(true); task.setLastRunStatus("RUNNING");
        task.setConfigurationStatus("COMPLETE"); task.setStatus(1);
        when(tasks.selectById(7L)).thenReturn(task);
        assertCode(INTEGRATION_FLOW_NOT_READY.getCode(), () -> service.run(7L));
        assertCode(INTEGRATION_FLOW_NOT_READY.getCode(), () -> service.stop(7L));
        verifyNoInteractions(pipelines);
    }

    @Test void legacyLocalBindingMustBeRepublishedInsteadOfBypassingDs() {
        var task = task(); task.setConfigurationStatus("COMPLETE"); task.setStatus(1); task.setPipelineId(99L);
        when(tasks.selectById(7L)).thenReturn(task); when(pipelines.runPipeline(99L)).thenReturn(1001L);
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.run(7L));
        verify(pipelines, never()).runPipeline(anyLong());
    }

    @Test void onlineSourceCannotBeCloned() {
        var source = task(); source.setStatus(1);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(source);
        assertCode(INTEGRATION_STATE_INVALID.getCode(), () -> service.cloneTask(7L));
        verify(tasks, never()).insert(any(IntegrationTaskDO.class));
    }

    @Test void clonePreservesGraphButDropsBindingsHistoryAndEnabledState() {
        var source = task(); source.setPipelineId(55L); source.setImportedSnapshot(true);
        source.setLastRunStatus("RUNNING"); source.setLastRunTime(LocalDateTime.of(2026, 10, 22, 12, 0));
        source.setLastRunDuration(500L); source.setFlowJson("{\"nodes\":[{\"id\":\"input\"}],\"edges\":[]}");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(source);
        doAnswer(invocation -> { ((IntegrationTaskDO) invocation.getArgument(0)).setId(101L); return 1; })
                .when(tasks).insert(any(IntegrationTaskDO.class));
        assertEquals(101L, service.cloneTask(7L));
        var capture = ArgumentCaptor.forClass(IntegrationTaskDO.class); verify(tasks).insert(capture.capture());
        var clone = capture.getValue();
        assertEquals(source.getFlowJson(), clone.getFlowJson()); assertEquals("IDLE", clone.getLastRunStatus());
        assertNull(clone.getPipelineId()); assertNull(clone.getLastRunTime()); assertNull(clone.getLastRunDuration());
        assertFalse(clone.getImportedSnapshot()); assertEquals(0, clone.getStatus()); assertEquals(0, clone.getScheduleStatus());
    }

    @Test void staleStatusChangeCannotReportSuccess() {
        when(tasks.selectById(7L)).thenReturn(task());
        var request = new StatusReq(); request.setId(7L); request.setRevision(2); request.setStatus(0);
        when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertCode(INTEGRATION_CONFLICT.getCode(), () -> service.status(request));
    }

    @Test void unconfiguredTaskCannotBeEnabled() {
        when(tasks.selectById(7L)).thenReturn(task());
        var request = new StatusReq(); request.setId(7L); request.setRevision(0); request.setStatus(1);
        assertCode(INTEGRATION_FLOW_NOT_READY.getCode(), () -> service.status(request));
        verify(tasks, never()).update(any(), any(Wrapper.class));
    }

    @Test void scheduleMustBeDisabledBeforeChangingItsPeriod() {
        var task = task(); task.setScheduleStatus(1); task.setCronExpression("0 0 0 * * ?");
        when(tasks.selectById(7L)).thenReturn(task);
        var request = new ScheduleReq(); request.setId(7L); request.setRevision(0);
        request.setScheduleStatus(0); request.setCronExpression("0 0 1 * * ?");
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.schedule(request));
        verifyNoInteractions(pipelines); verify(tasks, never()).update(any(), any(Wrapper.class));
    }

    @Test void cronPreviewUsesExistingQuartzSemanticsAndRejectsInvalidInput() {
        assertEquals(5, service.cronPreview("0 0 0 1 1 ?").size());
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.cronPreview("not a cron"));
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.cronPreview("* * * * *"));
    }

    @Test void categoryCannotMoveIntoItsDescendant() {
        var parent = category(1L, 0L); var child = category(2L, 1L);
        when(categories.selectById(1L)).thenReturn(parent); when(categories.selectById(2L)).thenReturn(child);
        when(categories.selectList()).thenReturn(List.of(parent, child));
        var request = new CategorySaveReq(); request.setId(1L); request.setParentId(2L); request.setName("父类目");
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.saveCategory(request, true));
        verify(categories).lockTree(); verify(categories, never()).updateById(any(IntegrationCategoryDO.class));
    }

    @Test void categoryWithTasksCannotBeDeleted() {
        when(categories.selectById(1L)).thenReturn(category(1L, 0L));
        when(categories.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(tasks.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.deleteCategory(1L));
        verify(categories, never()).deleteById(anyLong());
    }

    @Test void categoryWithChildrenCannotBeDeleted() {
        when(categories.selectById(1L)).thenReturn(category(1L, 0L));
        when(categories.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.deleteCategory(1L));
        verify(categories, never()).deleteById(anyLong());
    }

    @Test void categoryNameConflictIsABusinessFailure() {
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate")).when(categories).insert(any(IntegrationCategoryDO.class));
        var request = new CategorySaveReq(); request.setParentId(0L); request.setName("重复类目");
        assertCode(INTEGRATION_CONFIG_INVALID.getCode(), () -> service.saveCategory(request, false));
    }

    @Test void categoryInputRejectsSymbolOnlyAndOversizedNames() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); var request = new CategorySaveReq(); request.setParentId(0L);
            request.setName("!!!"); assertFalse(validator.validate(request).isEmpty());
            request.setName("类".repeat(51)); assertFalse(validator.validate(request).isEmpty());
            request.setName("数据同步_ODS01"); assertTrue(validator.validate(request).isEmpty());
        }
    }

    @Test void editableSpeedLimitsStayInsideJavascriptSafeIntegerRange() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); var config = new EngineConfig();
            config.setByteSpeed(9007199254740991L); assertTrue(validator.validate(config).isEmpty());
            config.setByteSpeed(9007199254740992L); assertFalse(validator.validate(config).isEmpty());
        }
    }

    @Test void statisticsFollowShippedTaskAndCompletedInstanceDefinitions() {
        when(tasks.selectCount(any(Wrapper.class))).thenReturn(1L, 1L, 3L);
        when(records.selectCount(any(Wrapper.class))).thenReturn(2L, 0L, 1L);
        var result = service.statistics("OFFLINE");
        assertEquals(1L, result.getRunning()); assertEquals(3L, result.getTodayFailed());
        assertEquals(2L, result.getTodayExecuted()); assertEquals(100, result.getTodaySuccessRate());
        assertTrue(result.getImportedSnapshot());
    }

    @Test void missingDsDefinitionDoesNotHideOtherPageRowsOrChangeOriginalBinding() {
        var ds = mock(IntegrationDsRuntime.class); ReflectionTestUtils.setField(service, "dsRuntime", ds);
        var missing = boundTask(7L); var available = boundTask(8L);
        var request = new PageReq(); request.setMode("OFFLINE");
        when(tasks.selectPage(eq(request), any(Wrapper.class))).thenReturn(new PageResult<>(List.of(missing, available), 2L));
        doThrow(new IntegrationDsRuntime.MissingWorkflowDefinitionException()).when(ds).refresh(missing);

        var result = service.page(request);

        assertEquals(2L, result.getTotal()); assertEquals(2, result.getList().size());
        var unavailable = result.getList().getFirst();
        assertFalse(unavailable.getRuntimeAvailable()); assertFalse(unavailable.getExecutable());
        assertTrue(unavailable.getRuntimeMessage().contains("不存在"));
        assertEquals(missing.getFlowJson(), unavailable.getFlowJson());
        assertTrue(result.getList().get(1).getRuntimeAvailable());
        assertTrue(result.getList().get(1).getExecutable()); assertNull(result.getList().get(1).getRuntimeMessage());
        assertOriginalBinding(missing);
        verify(ds).refresh(missing); verify(ds).refresh(available);
        verify(tasks).selectPage(eq(request), any(Wrapper.class)); verifyNoMoreInteractions(tasks);
        verify(records, times(2)).selectCount(any(Wrapper.class)); verifyNoMoreInteractions(records);
    }

    @Test void missingDsDefinitionLeavesTaskDetailsReadableWithoutChangingStoredState() {
        var ds = mock(IntegrationDsRuntime.class); ReflectionTestUtils.setField(service, "dsRuntime", ds);
        var missing = boundTask(7L); when(tasks.selectById(7L)).thenReturn(missing);
        doThrow(new IntegrationDsRuntime.MissingWorkflowDefinitionException()).when(ds).refresh(missing);

        var result = service.get(7L);

        assertFalse(result.getRuntimeAvailable()); assertFalse(result.getExecutable());
        assertTrue(result.getRuntimeMessage().contains("不存在"));
        assertEquals("SUCCESS", result.getLastRunStatus()); assertEquals(1, result.getStatus());
        assertEquals(91L, result.getDsProjectCode()); assertEquals(92L, result.getDsDefinitionCode());
        assertEquals(missing.getFlowJson(), result.getFlowJson()); assertOriginalBinding(missing);
        verify(tasks).selectById(7L); verifyNoMoreInteractions(tasks);
        verify(records).selectCount(any(Wrapper.class)); verifyNoMoreInteractions(records);
    }

    @Test void missingDsDefinitionMarksStatisticsIncompleteAndKeepsKnownCounts() {
        var ds = mock(IntegrationDsRuntime.class); ReflectionTestUtils.setField(service, "dsRuntime", ds);
        var missing = boundTask(7L); var available = boundTask(8L);
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(missing, available));
        when(tasks.selectCount(any(Wrapper.class))).thenReturn(1L, 1L, 3L);
        when(records.selectCount(any(Wrapper.class))).thenReturn(2L, 0L, 1L);
        doThrow(new IntegrationDsRuntime.MissingWorkflowDefinitionException()).when(ds).refresh(missing);

        var result = service.statistics("OFFLINE");

        assertFalse(result.getRuntimeAvailable()); assertTrue(result.getRuntimeMessage().contains("1 个任务"));
        assertTrue(result.getRuntimeMessage().contains("不完整"));
        assertEquals(1L, result.getRunning()); assertEquals(3L, result.getTodayFailed());
        assertEquals(2L, result.getTodayExecuted()); assertEquals(100, result.getTodaySuccessRate());
        assertTrue(result.getImportedSnapshot()); assertOriginalBinding(missing);
        verify(ds).refresh(missing); verify(ds).refresh(available);
        verify(tasks).selectList(any(Wrapper.class)); verify(tasks, times(3)).selectCount(any(Wrapper.class));
        verifyNoMoreInteractions(tasks);
        verify(records, times(3)).selectCount(any(Wrapper.class)); verifyNoMoreInteractions(records);
    }

    @Test void authenticationAndNetworkFailuresAreNotReportedAsMissingDefinitions() {
        var ds = mock(IntegrationDsRuntime.class); ReflectionTestUtils.setField(service, "dsRuntime", ds);
        var task = boundTask(7L); when(tasks.selectById(7L)).thenReturn(task);
        var request = new PageReq(); request.setMode("OFFLINE");
        when(tasks.selectPage(eq(request), any(Wrapper.class))).thenReturn(new PageResult<>(List.of(task), 1L));
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        for (var failure : List.of(new DolphinSchedulerException("HTTP 401"),
                new DolphinSchedulerException("HTTP 403"), new DolphinSchedulerException("HTTP 403", 403, null),
                new IllegalStateException("Connection refused"))) {
            doThrow(failure).when(ds).refresh(task);
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.page(request)));
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.get(7L)));
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.statistics("OFFLINE")));
            assertOriginalBinding(task);
        }
        verify(tasks, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(records);
    }

    @Test void cloneUsesCurrentCreatorInsteadOfTheImportedCreator() {
        var source = task(); source.setCreatorName("源创建人");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(source);
        var actor = new AdminUserRespDTO(); actor.setId(5L); actor.setNickname("当前创建人");
        when(users.getUser(5L)).thenReturn(CommonResult.success(actor));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(5L);
            service.cloneTask(7L);
        }
        var capture = ArgumentCaptor.forClass(IntegrationTaskDO.class); verify(tasks).insert(capture.capture());
        assertEquals("当前创建人", capture.getValue().getCreatorName());
        assertEquals("5", capture.getValue().getCreator());
    }

    private static IntegrationCategoryDO category(Long id, Long parent) {
        var category = new IntegrationCategoryDO(); category.setId(id); category.setParentId(parent); category.setName("类目" + id);
        return category;
    }

    private static IntegrationTaskDO task() {
        var task = new IntegrationTaskDO(); task.setId(7L); task.setRevision(0); task.setMode("OFFLINE");
        task.setName("离线任务"); task.setStatus(0); task.setScheduleStatus(0); task.setConfigurationStatus("UNCONFIGURED");
        return task;
    }

    private static IntegrationTaskDO boundTask(Long id) {
        var task = task(); task.setId(id); task.setRevision(3); task.setStatus(1);
        task.setConfigurationStatus("COMPLETE"); task.setDsProjectCode(91L); task.setDsDefinitionCode(92L);
        task.setFlowJson("{\"nodes\":[{\"id\":\"original\"}],\"edges\":[]}");
        task.setLastRunStatus("SUCCESS"); task.setImportedSnapshot(false);
        return task;
    }

    private static void assertOriginalBinding(IntegrationTaskDO task) {
        assertEquals(91L, task.getDsProjectCode()); assertEquals(92L, task.getDsDefinitionCode());
        assertEquals(3, task.getRevision()); assertEquals(1, task.getStatus()); assertEquals(0, task.getScheduleStatus());
        assertEquals("COMPLETE", task.getConfigurationStatus()); assertEquals("SUCCESS", task.getLastRunStatus());
        assertEquals("{\"nodes\":[{\"id\":\"original\"}],\"edges\":[]}", task.getFlowJson());
    }

    private static void assertCode(Integer code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ServiceException.class, operation).getCode());
    }
}
