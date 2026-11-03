package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.*;
import cn.iocoder.yudao.module.dataforge.enums.push.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.push.channel.DbPushChannel;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataPushExecutionOutcomeTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void mqPreflightAndSendingFailuresRespectPersistentBoundary(boolean beforeSending) {
        var executor = new DataPushExecutor();
        var tasks = mock(DataPushTaskMapper.class);
        var configs = mock(DataPushConfigMapper.class);
        var watermarks = mock(DataPushIncreaseLogMapper.class);
        var registry = mock(DataPushChannelRegistry.class);
        var channel = mock(DataPushChannel.class);
        var coordinator = mock(DataPushRunCoordinator.class);
        var resolver = mock(AccessDatabaseResolver.class);
        var task = new DataPushTaskDO(); task.setId(1L); task.setSourceDatabaseId(10L); task.setPushConfigId(2L);
        task.setCreator("20"); task.setPushType(PushTypeEnum.SQL.getType()); task.setSqlText("SELECT id FROM public.orders");
        var config = new DataPushConfigDO(); config.setChannelType(PushChannelTypeEnum.MQ.getType()); config.setChannelJson("{}");
        when(tasks.selectById(1L)).thenReturn(task);
        when(configs.selectById(2L)).thenReturn(config);
        when(registry.get(PushChannelTypeEnum.MQ.getType())).thenReturn(channel);
        when(resolver.resolve(eq(10L), any())).thenReturn(new AccessDatabaseResolver.ConnectionInfo());
        when(coordinator.startSending(any())).thenReturn(true);
        when(coordinator.finish(any(), anyInt(), any())).thenReturn(true);
        if (beforeSending) {
            doThrow(new IllegalArgumentException("invalid acks")).when(channel).validateConfiguration("{}");
        } else {
            when(channel.openSession("{}")).thenThrow(new IllegalStateException("uncertain connection"));
        }
        ReflectionTestUtils.setField(executor, "taskMapper", tasks);
        ReflectionTestUtils.setField(executor, "configMapper", configs);
        ReflectionTestUtils.setField(executor, "logMapper", mock(DataPushTaskLogMapper.class));
        ReflectionTestUtils.setField(executor, "increaseLogMapper", watermarks);
        ReflectionTestUtils.setField(executor, "channelRegistry", registry);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "executionAccessService", mock(ExecutionAccessService.class));
        var queries = mock(DatabaseQuerySupport.class);
        when(queries.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", queries);
        ReflectionTestUtils.setField(executor, "opsSupport", mock(DataPushOpsSupport.class));

        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        var run = DataPushTaskLogDO.builder().id(11L).pushTaskId(1L).runType(PushRunTypeEnum.HAND.getType())
                .runStatus(1).dataCount(0L).build();
        ReflectionTestUtils.invokeMethod(executor, "execute", run);
        verify(coordinator).finish(same(run), eq(beforeSending ? 1 : 2), isNull());
        assertEquals(beforeSending ? 4 : 5, run.getRunStatus());
        verifyNoInteractions(watermarks);
        if (beforeSending) {
            verify(coordinator, never()).startSending(any());
            verify(channel, never()).openSession(any());
            verifyNoInteractions(resolver);
        } else {
            var order = inOrder(channel, coordinator);
            order.verify(channel).validateConfiguration("{}");
            order.verify(coordinator).startSending(run);
            order.verify(channel).openSession("{}");
        }
    }

    @Test void unknownDatabaseOutcomeMustNotBecomeOrdinaryFailed() throws Exception {
        var executor = new DataPushExecutor();
        var tasks = mock(DataPushTaskMapper.class); var configs = mock(DataPushConfigMapper.class);
        var logs = mock(DataPushTaskLogMapper.class); var watermarks = mock(DataPushIncreaseLogMapper.class);
        var registry = mock(DataPushChannelRegistry.class); var channel = mock(DbPushChannel.class);
        var coordinator = mock(DataPushRunCoordinator.class);
        when(coordinator.startSending(any())).thenReturn(true);
        when(coordinator.finish(any(), anyInt(), any())).thenReturn(true);
        var resolver = mock(AccessDatabaseResolver.class);
        var task = new DataPushTaskDO(); task.setId(1L); task.setSourceDatabaseId(10L); task.setPushConfigId(2L);
        task.setCreator("20"); task.setPushType(PushTypeEnum.SQL.getType()); task.setSqlText("SELECT id FROM public.orders");
        var config = new DataPushConfigDO(); config.setChannelType(PushChannelTypeEnum.DB.getType());
        when(tasks.selectById(1L)).thenReturn(task); when(configs.selectById(2L)).thenReturn(config);
        when(registry.get(PushChannelTypeEnum.DB.getType())).thenReturn(channel);
        when(resolver.resolve(eq(10L), any())).thenReturn(new AccessDatabaseResolver.ConnectionInfo());
        when(channel.syncFromSource(any(), any(), any(), any(), any(), any(), any())).thenThrow(new UnconfirmedWriteException());
        ReflectionTestUtils.setField(executor, "taskMapper", tasks); ReflectionTestUtils.setField(executor, "configMapper", configs);
        ReflectionTestUtils.setField(executor, "logMapper", logs); ReflectionTestUtils.setField(executor, "increaseLogMapper", watermarks);
        ReflectionTestUtils.setField(executor, "channelRegistry", registry); ReflectionTestUtils.setField(executor, "dbPushChannel", channel);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "executionAccessService", mock(ExecutionAccessService.class));
        var queries = mock(DatabaseQuerySupport.class);
        when(queries.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", queries);
        ReflectionTestUtils.setField(executor, "opsSupport", mock(DataPushOpsSupport.class));

        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        var run = DataPushTaskLogDO.builder().id(11L).pushTaskId(1L).runType(PushRunTypeEnum.HAND.getType())
                .runStatus(1).dataCount(0L).build();
        ReflectionTestUtils.invokeMethod(executor, "execute", run);
        var saved = ArgumentCaptor.forClass(DataPushTaskLogDO.class); verify(coordinator).finish(saved.capture(), eq(2), isNull());
        assertEquals(5, saved.getValue().getRunStatus(), "结果未知必须保留独立状态，不能释放为普通失败");
        verifyNoInteractions(watermarks);
        verify(logs, never()).updateById(any(DataPushTaskLogDO.class));
    }
}
