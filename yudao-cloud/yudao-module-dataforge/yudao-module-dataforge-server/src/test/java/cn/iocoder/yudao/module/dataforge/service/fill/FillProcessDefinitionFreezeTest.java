package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.enums.ErrorCodeConstants;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventOutboxMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillEventTypeEnum;
import cn.iocoder.yudao.module.dataforge.job.fill.FillOutboxRelayJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_PROCESS_NO_ACTIVE_VERSION;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FillProcessDefinitionFreezeTest {

    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillEventOutboxMapper outboxMapper = mock(FillEventOutboxMapper.class);
    private final FillBpmAdapter bpmAdapter = mock(FillBpmAdapter.class);
    private final FillTaskServiceImpl service = new FillTaskServiceImpl();

    FillProcessDefinitionFreezeTest() {
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "accessGuard", org.mockito.Mockito.mock(FillTaskAccessGuard.class));
        ReflectionTestUtils.setField(service, "outboxMapper", outboxMapper);
        ReflectionTestUtils.setField(service, "bpmAdapter", bpmAdapter);
    }

    private static FillTaskDO task(String definitionId) {
        return FillTaskDO.builder().id(9L).name("月报").processDefinitionKey("fill_approval")
                .processDefinitionId(definitionId).build();
    }

    @Test
    void alreadyFrozenVersionIsKeptWithoutCallingBpm() {
        when(taskMapper.selectById(9L)).thenReturn(task("fill_approval:1:a"));

        assertThat(service.freezeProcessDefinition(9L, "fill_approval")).isEqualTo("fill_approval:1:a");
        verify(bpmAdapter, never()).getActiveProcessDefinitionId(anyString());
        verify(taskMapper, never()).freezeProcessDefinitionIfAbsent(anyLong(), anyString());
    }

    @Test
    void firstFreezeUsesActiveVersionAndConcurrentWinnerIsReread() {
        when(taskMapper.selectById(9L)).thenReturn(task(null), task("fill_approval:1:winner"));
        when(bpmAdapter.getActiveProcessDefinitionId("fill_approval")).thenReturn("fill_approval:2:b");

        assertThat(service.freezeProcessDefinition(9L, "fill_approval")).isEqualTo("fill_approval:1:winner");
        verify(taskMapper).freezeProcessDefinitionIfAbsent(9L, "fill_approval:2:b");
    }

    @Test
    void freezeFailsWhenProcessHasNoActiveVersion() {
        when(taskMapper.selectById(9L)).thenReturn(task(null));

        assertThatThrownBy(() -> service.freezeProcessDefinition(9L, "fill_approval"))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_PROCESS_NO_ACTIVE_VERSION.getCode()));
    }

    @Test
    void switchMovesToActiveVersionAndReschedulesWaitingStarts() {
        when(taskMapper.selectById(9L)).thenReturn(task("fill_approval:1:a"));
        when(bpmAdapter.getActiveProcessDefinitionId("fill_approval")).thenReturn("fill_approval:2:b");
        when(taskMapper.switchProcessDefinition(9L, "fill_approval:1:a", "fill_approval:2:b")).thenReturn(1);

        assertThat(service.switchProcessDefinition(9L)).isEqualTo("fill_approval:2:b");
        verify(outboxMapper).rescheduleFailedByTask(eq(9L),
                eq(FillEventTypeEnum.FILL_BPM_START_REQUESTED.getType()), any());
    }

    @Test
    void concurrentSwitchIsRejected() {
        when(taskMapper.selectById(9L)).thenReturn(task("fill_approval:1:a"));
        when(bpmAdapter.getActiveProcessDefinitionId("fill_approval")).thenReturn("fill_approval:2:b");
        when(taskMapper.switchProcessDefinition(9L, "fill_approval:1:a", "fill_approval:2:b")).thenReturn(0);

        assertThatThrownBy(() -> service.switchProcessDefinition(9L))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_STATUS_INVALID.getCode()));
        verify(outboxMapper, never()).rescheduleFailedByTask(anyLong(), anyString(), any());
    }

    @Test
    void startProcessPinsFrozenDefinition() {
        BpmProcessInstanceApi api = mock(BpmProcessInstanceApi.class);
        FillBpmAdapter adapter = new FillBpmAdapter();
        ReflectionTestUtils.setField(adapter, "bpmProcessInstanceApi", api);
        when(api.createProcessInstance(eq(5L), any())).thenReturn(CommonResult.success("pi-1"));

        adapter.startProcess(FillSubmissionDO.builder().id(3L).submitterUserId(5L).build(),
                FillWorkItemDO.builder().id(4L).build(), task("fill_approval:1:a"));

        ArgumentCaptor<BpmProcessInstanceCreateReqDTO> request = ArgumentCaptor.forClass(BpmProcessInstanceCreateReqDTO.class);
        verify(api).createProcessInstance(eq(5L), request.capture());
        assertThat(request.getValue().getProcessDefinitionKey()).isEqualTo("fill_approval");
        assertThat(request.getValue().getProcessDefinitionId()).isEqualTo("fill_approval:1:a");
        assertThat(request.getValue().getBusinessKey()).isEqualTo("3");
    }

    @Test
    void supersededFrozenVersionIsNotSilentlyReplaced() {
        FillOutboxRelayJob job = new FillOutboxRelayJob();
        FillTaskService taskService = mock(FillTaskService.class);
        ReflectionTestUtils.setField(job, "bpmAdapter", bpmAdapter);
        ReflectionTestUtils.setField(job, "fillTaskService", taskService);
        FillSubmissionDO submission = FillSubmissionDO.builder().id(3L).submitterUserId(5L).build();
        FillWorkItemDO workItem = FillWorkItemDO.builder().id(4L).build();
        FillTaskDO frozen = task("fill_approval:1:a");
        when(bpmAdapter.startProcess(submission, workItem, frozen))
                .thenThrow(new ServiceException(ErrorCodeConstants.PROCESS_DEFINITION_IS_SUSPENDED));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(job, "startWithFrozenDefinition",
                submission, workItem, frozen))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("切换到当前流程版本");
        verify(taskService, never()).freezeProcessDefinition(anyLong(), anyString());
    }

    @Test
    void unfrozenTaskIsFrozenBeforeFirstStart() {
        FillOutboxRelayJob job = new FillOutboxRelayJob();
        FillTaskService taskService = mock(FillTaskService.class);
        ReflectionTestUtils.setField(job, "bpmAdapter", bpmAdapter);
        ReflectionTestUtils.setField(job, "fillTaskService", taskService);
        FillTaskDO unfrozen = task(null);
        when(taskService.freezeProcessDefinition(9L, "fill_approval")).thenReturn("fill_approval:2:b");
        when(bpmAdapter.startProcess(any(), any(), any())).thenReturn("pi-2");

        Object instance = ReflectionTestUtils.invokeMethod(job, "startWithFrozenDefinition",
                FillSubmissionDO.builder().id(3L).build(), FillWorkItemDO.builder().id(4L).build(), unfrozen);

        assertThat(instance).isEqualTo("pi-2");
        assertThat(unfrozen.getProcessDefinitionId()).isEqualTo("fill_approval:2:b");
    }
}
