package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.DataPushConfigController;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.DataPushTaskController;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushConfigMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataPushTaskInlineConfigTest extends BaseMockitoUnitTest {

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void requiresExactlyOneConfigurationAndValidatesNestedFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = taskRequest();
            assertTrue(validator.validate(request).isEmpty());
            request.setPushConfigId(10L);
            assertFalse(validator.validate(request).isEmpty());
            request.setPushConfig(null);
            assertTrue(validator.validate(request).isEmpty());
            request.setPushConfigId(null);
            assertFalse(validator.validate(request).isEmpty());
            request.setPushConfig(configRequest());
            request.getPushConfig().setName(null);
            assertTrue(validator.validate(request).stream().anyMatch(violation ->
                    "pushConfig.name".equals(violation.getPropertyPath().toString())));
        }
    }

    @Test
    void credentialBearingEntryPointsDisableRequestBodyLogging() throws Exception {
        var entries = List.of(
                DataPushTaskController.class.getMethod("createTask", DataPushTaskSaveReqVO.class),
                DataPushTaskController.class.getMethod("updateTask", DataPushTaskSaveReqVO.class),
                DataPushTaskController.class.getMethod("preview", DataPushPreviewReqVO.class),
                DataPushConfigController.class.getMethod("testConnection", DataPushConfigSaveReqVO.class));
        for (var entry : entries) {
            var accessLog = entry.getAnnotation(ApiAccessLog.class);
            assertNotNull(accessLog, entry.getName());
            assertFalse(accessLog.requestEnable(), entry.getName());
        }
    }

    @Test
    void refusesMissingOrConflictingConfigurationBeforePersistence() {
        var fixture = new Fixture();
        var request = taskRequest();
        request.setPushConfigId(10L);
        assertThrows(ServiceException.class, () -> fixture.tasksService.createTask(request));
        request.setPushConfig(null);
        request.setPushConfigId(null);
        assertThrows(ServiceException.class, () -> fixture.tasksService.createTask(request));
        verifyNoInteractions(fixture.configs, fixture.tasks);
    }

    @Test
    void createRejectsEmbeddedExistingConfigurationId() {
        var fixture = new Fixture();
        var request = taskRequest();
        request.getPushConfig().setId(10L);
        assertThrows(ServiceException.class, () -> fixture.tasksService.createTask(request));
        verifyNoInteractions(fixture.configs, fixture.tasks);
    }

    @Test
    void validatesSourceBeforeCreatingAnyInlineConfiguration() {
        var fixture = new Fixture();
        when(fixture.databases.selectById(5L)).thenReturn(null);
        var failure = assertThrows(ServiceException.class, () -> fixture.tasksService.createTask(taskRequest()));
        assertEquals(DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS.getCode(), failure.getCode());
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
        verify(fixture.tasks, never()).insert(any(DataPushTaskDO.class));
    }

    @Test
    void savesNewTaskWithItsOwnInlineConfiguration() {
        var fixture = new Fixture();
        fixture.persistIds();
        assertEquals(20L, fixture.tasksService.createTask(taskRequest()));
        var saved = ArgumentCaptor.forClass(DataPushTaskDO.class);
        verify(fixture.tasks).insert(saved.capture());
        assertEquals(30L, saved.getValue().getPushConfigId());
        assertEquals(DataPushTaskDO.STATUS_UNRELEASED, saved.getValue().getStatus());
        verify(fixture.configs).insert(any(DataPushConfigDO.class));
    }

    @Test
    void supportsLegacyConfigurationReferenceWithoutCreatingAnotherConfiguration() {
        var fixture = new Fixture();
        fixture.persistIds();
        var request = taskRequest();
        request.setPushConfig(null);
        request.setPushConfigId(10L);
        fixture.tasksService.createTask(request);
        verify(fixture.configs).selectOne(any());
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
        var saved = ArgumentCaptor.forClass(DataPushTaskDO.class);
        verify(fixture.tasks).insert(saved.capture());
        assertEquals(10L, saved.getValue().getPushConfigId());
    }

    @Test
    void restoresOnlyCurrentTasksSecretsWithoutMutatingIncomingRequest() {
        var fixture = new Fixture();
        var request = configRequest();
        request.setId(10L);
        request.getConfig().getHttp().setHeaders(List.of(header(DataPushSecretMasker.MASK)));
        var prepared = fixture.configService.prepareTaskConfig(request, 10L);
        var content = JsonUtils.parseObject(prepared.getChannelJson(), DataPushChannelConfigVO.class);
        assertEquals(fixture.syntheticSecret, content.getHttp().getHeaders().getFirst().getValue());
        assertEquals(DataPushSecretMasker.MASK, request.getConfig().getHttp().getHeaders().getFirst().getValue());
        request.setId(11L);
        assertThrows(ServiceException.class, () -> fixture.configService.prepareTaskConfig(request, 10L));
        verify(fixture.configs, never()).selectById(11L);
    }

    @Test
    void cannotResolveConfigurationOutsideVisibleTenant() {
        var fixture = new Fixture();
        when(fixture.configs.selectById(10L)).thenReturn(null);
        var request = configRequest();
        request.setId(10L);
        assertThrows(ServiceException.class, () -> fixture.configService.prepareTaskConfig(request, 10L));
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
    }

    @Test
    void exclusiveTaskEditsConfigurationInPlace() {
        var fixture = new Fixture();
        fixture.persistIds();
        when(fixture.tasks.selectCountByConfigId(10L)).thenReturn(1L);
        when(fixture.configs.updateById(any(DataPushConfigDO.class))).thenReturn(1);
        fixture.idleUpdate();
        var request = taskRequest();
        request.setId(20L);
        request.getPushConfig().setId(10L);
        fixture.tasksService.updateTask(request);
        verify(fixture.configs).updateById(any(DataPushConfigDO.class));
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
        var saved = ArgumentCaptor.forClass(DataPushTaskDO.class);
        verify(fixture.tasks).updateById(saved.capture());
        assertEquals(10L, saved.getValue().getPushConfigId());
    }

    @Test
    void sharedConfigurationIsCopiedWithoutChangingOtherTasks() {
        var fixture = new Fixture();
        fixture.persistIds();
        when(fixture.tasks.selectCountByConfigId(10L)).thenReturn(2L);
        fixture.idleUpdate();
        var request = taskRequest();
        request.setId(20L);
        request.getPushConfig().setId(10L);
        fixture.tasksService.updateTask(request);
        verify(fixture.configs, never()).updateById(any(DataPushConfigDO.class));
        var saved = ArgumentCaptor.forClass(DataPushTaskDO.class);
        verify(fixture.tasks).updateById(saved.capture());
        assertEquals(30L, saved.getValue().getPushConfigId());
        assertEquals(10L, fixture.original.getId());
    }

    @Test
    void releasedTaskRejectsInlineChangesBeforeConfigurationPreparation() {
        var fixture = new Fixture();
        fixture.current.setStatus(DataPushTaskDO.STATUS_RELEASED);
        var request = taskRequest();
        request.setId(20L);
        assertThrows(ServiceException.class, () -> fixture.tasksService.updateTask(request));
        verify(fixture.configs, never()).selectById(anyLong());
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
    }

    @Test
    void previewReadsInlineDraftWithoutPersistingIt() throws Exception {
        var fixture = new Fixture();
        when(fixture.executor.preview(any(), any(DataPushConfigDO.class), eq(10)))
                .thenReturn(new DataPushPreviewRespVO());
        var preview = previewRequest();
        fixture.tasksService.preview(preview);
        var draft = ArgumentCaptor.forClass(DataPushTaskDO.class);
        verify(fixture.executor).preview(draft.capture(), any(DataPushConfigDO.class), eq(10));
        assertNull(draft.getValue().getId());
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
        verify(fixture.configs, never()).updateById(any(DataPushConfigDO.class));
        verify(fixture.tasks, never()).insert(any(DataPushTaskDO.class));
    }

    @Test
    void editingPreviewUsesOwnedConfigurationAndKeepsTaskWatermarkIdentity() throws Exception {
        var fixture = new Fixture();
        var preview = previewRequest();
        preview.setId(20L);
        preview.getPushConfig().setId(10L);
        preview.getPushConfig().getConfig().getHttp().setHeaders(List.of(header(DataPushSecretMasker.MASK)));
        fixture.tasksService.preview(preview);
        var draft = ArgumentCaptor.forClass(DataPushTaskDO.class);
        verify(fixture.executor).preview(draft.capture(), any(DataPushConfigDO.class), eq(10));
        assertEquals(20L, draft.getValue().getId());
        assertEquals(DataPushSecretMasker.MASK,
                preview.getPushConfig().getConfig().getHttp().getHeaders().getFirst().getValue());
        preview.getPushConfig().setId(11L);
        assertThrows(ServiceException.class, () -> fixture.tasksService.preview(preview));
        verify(fixture.configs, never()).selectById(11L);
        verify(fixture.configs, never()).insert(any(DataPushConfigDO.class));
    }

    @Test
    void changedConfigurationSnapshotRejectsLegacyReferenceBeforeTaskWrite() {
        var fixture = new Fixture();
        var changed = DataPushConfigDO.builder().id(10L).channelType(2).build();
        changed.setUpdateTime(fixture.original.getUpdateTime().plusSeconds(1));
        when(fixture.configs.selectOne(any())).thenReturn(changed);
        var request = taskRequest();
        request.setPushConfig(null);
        request.setPushConfigId(10L);
        assertThrows(ServiceException.class, () -> fixture.tasksService.createTask(request));
        verify(fixture.tasks, never()).insert(any(DataPushTaskDO.class));
    }

    @Test
    void directConfigurationUpdateRejectsConcurrentSnapshotChange() {
        var fixture = new Fixture();
        var changed = DataPushConfigDO.builder().id(10L).channelType(1).build();
        changed.setUpdateTime(fixture.original.getUpdateTime().plusSeconds(1));
        when(fixture.configs.selectOne(any())).thenReturn(changed);
        var request = configRequest();
        request.setId(10L);
        assertThrows(ServiceException.class, () -> fixture.configService.updateConfig(request));
        verify(fixture.configs, never()).updateById(any(DataPushConfigDO.class));
    }

    static DataPushConfigSaveReqVO configRequest() {
        var http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.invalid/receive");
        var channel = new DataPushChannelConfigVO();
        channel.setHttp(http);
        var request = new DataPushConfigSaveReqVO();
        request.setName("任务接收端");
        request.setChannelType(1);
        request.setConfig(channel);
        return request;
    }

    static DataPushTaskSaveReqVO taskRequest() {
        var request = new DataPushTaskSaveReqVO();
        request.setName("内嵌配置测试任务");
        request.setPushConfig(configRequest());
        request.setSourceDatabaseId(5L);
        request.setPushType(2);
        request.setSqlText("SELECT id FROM source_rows");
        request.setPushMode(1);
        request.setTaskType(1);
        return request;
    }

    private static DataPushPreviewReqVO previewRequest() {
        var preview = new DataPushPreviewReqVO();
        preview.setPushConfig(configRequest());
        preview.setSourceDatabaseId(5L);
        preview.setPushType(2);
        preview.setSqlText("SELECT id FROM source_rows");
        preview.setPushMode(1);
        preview.setTaskType(1);
        preview.setName("草稿测试任务");
        return preview;
    }

    private static DataPushChannelConfigVO.KeyValue header(String value) {
        var header = new DataPushChannelConfigVO.KeyValue();
        header.setKey("Authorization");
        header.setValue(value);
        return header;
    }

    private static final class Fixture {
        private final DataPushConfigMapper configs = mock(DataPushConfigMapper.class);
        private final DataPushTaskMapper tasks = mock(DataPushTaskMapper.class);
        private final DatabaseMapper databases = mock(DatabaseMapper.class);
        private final DataPushRunCoordinator coordinator = mock(DataPushRunCoordinator.class);
        private final DataPushExecutor executor = mock(DataPushExecutor.class);
        private final DataPushConfigServiceImpl configService = new DataPushConfigServiceImpl();
        private final DataPushTaskServiceImpl tasksService = new DataPushTaskServiceImpl();
        private final String syntheticSecret = "synthetic-" + UUID.randomUUID();
        private final DataPushConfigDO original;
        private final DataPushTaskDO current = DataPushTaskDO.builder().id(20L).pushConfigId(10L).status(0).build();

        private Fixture() {
            TenantContextHolder.setTenantId(1L);
            TenantContextHolder.setIgnore(false);
            ReflectionTestUtils.setField(configService, "configMapper", configs);
            ReflectionTestUtils.setField(configService, "taskMapper", tasks);
            ReflectionTestUtils.setField(configService, "databaseMapper", databases);
            ReflectionTestUtils.setField(configService, "httpPushUrlGuard", mock(HttpPushUrlGuard.class));
            ReflectionTestUtils.setField(tasksService, "taskMapper", tasks);
            ReflectionTestUtils.setField(tasksService, "configMapper", configs);
            ReflectionTestUtils.setField(tasksService, "configService", configService);
            ReflectionTestUtils.setField(tasksService, "databaseMapper", databases);
            ReflectionTestUtils.setField(tasksService, "runCoordinator", coordinator);
            ReflectionTestUtils.setField(tasksService, "pushExecutor", executor);
            var content = configRequest().getConfig();
            content.getHttp().setHeaders(List.of(header(syntheticSecret)));
            original = DataPushConfigDO.builder().id(10L).channelType(1).channelJson(JsonUtils.toJsonString(content)).build();
            original.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
            lenient().when(configs.selectById(10L)).thenReturn(original);
            lenient().when(configs.selectOne(any())).thenReturn(original);
            lenient().when(databases.selectById(5L)).thenReturn(DatabaseDO.builder().id(5L).build());
            lenient().when(tasks.selectById(20L)).thenReturn(current);
        }

        private void persistIds() {
            lenient().when(configs.insert(any(DataPushConfigDO.class))).thenAnswer(invocation -> {
                invocation.<DataPushConfigDO>getArgument(0).setId(30L);
                return 1;
            });
            lenient().when(tasks.insert(any(DataPushTaskDO.class))).thenAnswer(invocation -> {
                invocation.<DataPushTaskDO>getArgument(0).setId(20L);
                return 1;
            });
        }

        @SuppressWarnings("unchecked")
        private void idleUpdate() {
            when(coordinator.withIdleTask(eq(20L), any())).thenAnswer(invocation ->
                    invocation.<Function<DataPushTaskDO, Object>>getArgument(1).apply(current));
            when(tasks.updateById(any(DataPushTaskDO.class))).thenReturn(1);
        }
    }
}
