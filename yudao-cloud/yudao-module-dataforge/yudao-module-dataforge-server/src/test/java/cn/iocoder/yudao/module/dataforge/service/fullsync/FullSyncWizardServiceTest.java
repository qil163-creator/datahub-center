package cn.iocoder.yudao.module.dataforge.service.fullsync;

import cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo.FullSyncWizardConfigVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo.FullSyncWizardSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo.FullSyncWizardPageReqVO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fullsync.FullSyncWizardDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fullsync.FullSyncWizardMapper;
import cn.iocoder.yudao.module.dataforge.service.access.DataAccessService;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.DataSourceApi;
import cn.iocoder.yudao.framework.dolphinscheduler.api.TaskDefinitionApi;
import cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowDefinitionApi;
import cn.iocoder.yudao.framework.dolphinscheduler.request.CreateWorkflowDefinitionRequest;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 整库同步四步向导服务契约测试。
 */
@ExtendWith(MockitoExtension.class)
class FullSyncWizardServiceTest {

    @Mock
    private FullSyncWizardMapper mapper;
    @Mock
    private DatabaseService databaseService;
    @Mock
    private DataAccessService dataAccessService;
    @Mock
    private DolphinSchedulerClient dolphinSchedulerClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    private FullSyncWizardService service;

    @BeforeEach
    void setUp() {
        service = new FullSyncWizardService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "databaseService", databaseService);
        ReflectionTestUtils.setField(service, "dataAccessService", dataAccessService);
        ReflectionTestUtils.setField(service, "dolphinSchedulerClient", dolphinSchedulerClient);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
    }

    @Test
    void createStepOne_doesNotTouchDolphinScheduler() {
        FullSyncWizardConfigVO config = config();
        FullSyncWizardSaveReqVO request = new FullSyncWizardSaveReqVO();
        request.setStep(1);
        request.setConfig(config);
        doAnswer(invocation -> {
            FullSyncWizardDO row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        }).when(mapper).insert(any(FullSyncWizardDO.class));

        var result = service.create(request);

        assertEquals(9L, result.getId());
        assertEquals(1, result.getCurrentStep());
        assertEquals(FullSyncWizardService.STATUS_DRAFT, result.getStatus());
        verifyNoInteractions(dolphinSchedulerClient);
        verifyNoInteractions(dataAccessService);
    }

    @Test
    void createRejectsSameSourceAndTarget() {
        FullSyncWizardConfigVO config = config();
        config.setTargetDatabaseId(config.getSourceDatabaseId());
        FullSyncWizardSaveReqVO request = new FullSyncWizardSaveReqVO();
        request.setStep(1);
        request.setConfig(config);

        assertThrows(RuntimeException.class, () -> service.create(request));
        verifyNoInteractions(mapper);
    }

    @Test
    void pageKeepsConfigurationAndAuditWithoutCallingExecution() {
        FullSyncWizardPageReqVO request = new FullSyncWizardPageReqVO();
        request.setTaskName("' OR 1=1 --");
        request.setSourceDatabaseId(11L);
        request.setTargetDatabaseId(12L);
        FullSyncWizardConfigVO config = config();
        config.setResponsibleName("负责人");
        config.setTableProperties(java.util.Map.of("users", java.util.Map.of("targetTableName", "ods_users")));
        FullSyncWizardDO row = new FullSyncWizardDO();
        row.setId(9007199254740993L);
        row.setRevision(2L);
        row.setCreator("42");
        row.setCreateTime(java.time.LocalDateTime.of(2026, 11, 1, 12, 0));
        row.setConfigJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(config));
        when(mapper.selectPage(eq(request), any(Wrapper.class)))
                .thenReturn(new PageResult<>(java.util.List.of(row), 1L));

        var result = service.page(request);

        assertEquals(1L, result.getTotal());
        assertEquals(row.getId(), result.getList().getFirst().getId());
        assertEquals("42", result.getList().getFirst().getCreator());
        assertEquals(row.getCreateTime(), result.getList().getFirst().getCreateTime());
        assertEquals(config.getTableProperties(), result.getList().getFirst().getConfig().getTableProperties());
        assertEquals("负责人", result.getList().getFirst().getConfig().getResponsibleName());
        verifyNoInteractions(databaseService, dataAccessService, dolphinSchedulerClient);
    }

    @Test
    void deleteRejectsStaleRevision() {
        FullSyncWizardDO row = draft();
        when(mapper.lock(9L)).thenReturn(row);

        assertThrows(RuntimeException.class, () -> service.delete(9L, 1L));

        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRejectsExternalSubmissionStates() {
        FullSyncWizardDO row = draft();
        when(mapper.lock(9L)).thenReturn(row);
        for (String state : java.util.List.of("SUBMITTING", "SUBMITTED", "UNKNOWN")) {
            row.setStatus(state);
            assertThrows(RuntimeException.class, () -> service.delete(9L, 2L));
        }

        verify(mapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(dataAccessService, dolphinSchedulerClient);
    }

    @Test
    void deleteDraftUsesLogicalDeleteWithoutCallingExecution() {
        when(mapper.lock(9L)).thenReturn(draft());

        service.delete(9L, 2L);

        verify(mapper).deleteById(9L);
        verifyNoInteractions(databaseService, dataAccessService, dolphinSchedulerClient);
    }

    @Test
    void uiConfigurationDefaultsRemainCompatibleAndExplicitNullIsRejected() {
        FullSyncWizardConfigVO config = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                "{\"taskName\":\"旧草稿\",\"taskType\":2}", FullSyncWizardConfigVO.class);
        assertEquals(0, config.getTaskStatus());
        assertEquals("MANUAL", config.getExecutionMethod());
        assertNotNull(config.getAppendColumns());
        assertNotNull(config.getTableProperties());
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(config).isEmpty());
            config.setYarnQueue("");
            config.setWorkerGroup("");
            config.setDriverMemory("");
            config.setExecutorMemory("");
            assertTrue(validator.validate(config).isEmpty());
            config.setTaskStatus(null);
            config.setAppendColumns(java.util.Collections.singletonList(null));
            config.setTableProperties(null);
            var paths = validator.validate(config).stream()
                    .map(item -> item.getPropertyPath().toString()).toList();
            assertTrue(paths.contains("taskStatus"));
            assertTrue(paths.contains("tableProperties"));
            assertTrue(paths.stream().anyMatch(path -> path.startsWith("appendColumns")));
        }
    }

    private static FullSyncWizardDO draft() {
        FullSyncWizardDO row = new FullSyncWizardDO();
        row.setId(9L);
        row.setRevision(2L);
        row.setStatus(FullSyncWizardService.STATUS_DRAFT);
        return row;
    }

    @Test
    void dolphinSubmitBuildsDataxTaskParamsWithoutCredentials() {
        String rawScript = "{\"job\":{\"content\":[{\"reader\":{\"name\":\"mysqlreader\"},"
                + "\"writer\":{\"name\":\"mysqlwriter\"}}]}}";
        FullSyncWizardConfigVO config = config();
        config.setExecutionMode(FullSyncWizardService.EXECUTION_MODE_DOLPHIN_DATAX);
        config.setDsProjectCode(100L);
        config.setDsWorkerGroup("default");
        config.setDsTenantCode("tenant_default");
        config.setDsSourceDatasourceCode(11L);
        config.setDsTargetDatasourceCode(12L);
        config.setDataxJobJson(rawScript);
        FullSyncWizardDO wizard = new FullSyncWizardDO();
        wizard.setId(9L);
        wizard.setRevision(2L);

        DataSourceApi dataSources = mock(DataSourceApi.class);
        TaskDefinitionApi taskDefinitions = mock(TaskDefinitionApi.class);
        WorkflowDefinitionApi workflows = mock(WorkflowDefinitionApi.class);
        when(dolphinSchedulerClient.dataSource()).thenReturn(dataSources);
        when(dolphinSchedulerClient.taskDefinition()).thenReturn(taskDefinitions);
        when(dolphinSchedulerClient.workflowDefinition()).thenReturn(workflows);
        when(taskDefinitions.genTaskCodeList(100L, 1)).thenReturn(JSONUtil.parseObj("{\"data\":[222] }"));
        when(workflows.createWorkflowDefinition(eq(100L), any(CreateWorkflowDefinitionRequest.class)))
                .thenReturn(JSONUtil.parseObj("{\"data\":{\"code\":333}}"));

        ReflectionTestUtils.invokeMethod(service, "submitDolphin", wizard, config, false);

        ArgumentCaptor<CreateWorkflowDefinitionRequest> request = ArgumentCaptor.forClass(
                CreateWorkflowDefinitionRequest.class);
        verify(workflows).createWorkflowDefinition(eq(100L), request.capture());
        var node = JSONUtil.parseArray(request.getValue().getTaskDefinitionJson()).getJSONObject(0);
        assertEquals("DATAX", node.getStr("taskType"));
        assertEquals(rawScript, node.getJSONObject("taskParams").getStr("rawScript"));
        assertTrue(node.getJSONObject("taskParams").containsKey("localParams"));
        assertFalse(request.getValue().getTaskDefinitionJson().toLowerCase().contains("password"));
        verify(dataSources).queryDataSource(11);
        verify(dataSources).queryDataSource(12);
    }

    private static FullSyncWizardConfigVO config() {
        FullSyncWizardConfigVO config = new FullSyncWizardConfigVO();
        config.setTaskName("整库同步");
        config.setSourceDatabaseId(11L);
        config.setTargetDatabaseId(12L);
        config.setSourceSelectedTables(java.util.List.of("users"));
        config.setTaskType(2);
        return config;
    }
}

