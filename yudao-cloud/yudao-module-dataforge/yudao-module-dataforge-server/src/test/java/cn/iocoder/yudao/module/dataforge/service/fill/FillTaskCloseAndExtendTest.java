package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskCloseRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskExtendReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillCorrectionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillCorrectionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillCorrectionStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillTaskStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_DEADLINE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_STATUS_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_WORK_ITEM_MISMATCH;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_WORK_ITEM_STATUS_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结束填报与截止时间延期测试
 */
class FillTaskCloseAndExtendTest {

    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillCorrectionMapper correctionMapper = mock(FillCorrectionMapper.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);
    private final FillCollabService collabService = mock(FillCollabService.class);

    private static FillTaskDO task(int status) {
        return FillTaskDO.builder().id(9L).name("月报（2026-10）").status(status)
                .openTime(LocalDateTime.now().minusDays(3))
                .deadlineTime(LocalDateTime.now().minusHours(1)).build();
    }

    private static FillWorkItemDO item(long id, FillWorkItemStatusEnum status) {
        return FillWorkItemDO.builder().id(id).taskId(9L).orgName("组织" + id).status(status.getStatus())
                .overdue(1).deadlineTime(LocalDateTime.now().minusHours(1)).build();
    }

    // ---------------------------------------------------------------- 结束填报

    private FillTaskServiceImpl taskService() {
        FillCorrectionServiceImpl correctionService = new FillCorrectionServiceImpl();
        ReflectionTestUtils.setField(correctionService, "correctionMapper", correctionMapper);
        ReflectionTestUtils.setField(correctionService, "workItemMapper", workItemMapper);
        FillTaskServiceImpl service = new FillTaskServiceImpl();
        ReflectionTestUtils.setField(service, "accessGuard", org.mockito.Mockito.mock(FillTaskAccessGuard.class));
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        ReflectionTestUtils.setField(service, "collabService", collabService);
        ReflectionTestUtils.setField(service, "correctionService", correctionService);
        return service;
    }

    @Test
    void closeCancelsUnsubmittedRestoresCorrectionsAndLeavesInFlightRunning() {
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.RUNNING.getStatus()));
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(
                item(1L, FillWorkItemStatusEnum.PENDING),
                item(2L, FillWorkItemStatusEnum.FILLING),
                item(3L, FillWorkItemStatusEnum.REWORKING),
                item(4L, FillWorkItemStatusEnum.REWORKING),
                item(5L, FillWorkItemStatusEnum.APPROVING),
                item(6L, FillWorkItemStatusEnum.MERGED)));
        int reworking = FillWorkItemStatusEnum.REWORKING.getStatus();
        int cancelled = FillWorkItemStatusEnum.CANCELLED.getStatus();
        int merged = FillWorkItemStatusEnum.MERGED.getStatus();
        when(workItemMapper.compareAndSetStatus(anyLong(), any(), eq(cancelled))).thenReturn(1);
        // 4 号是已合并后发起的更正：恢复为已合并，不取消
        when(correctionMapper.selectActiveByWorkItemId(4L)).thenReturn(FillCorrectionDO.builder().id(40L)
                .workItemId(4L).status(FillCorrectionStatusEnum.DRAFT.getStatus()).build());
        when(workItemMapper.compareAndSetStatus(4L, reworking, merged)).thenReturn(1);

        FillTaskCloseRespVO result = taskService().closeTask(9L);

        assertThat(result.getCancelledCount()).isEqualTo(3);
        assertThat(result.getRestoredCount()).isEqualTo(1);
        assertThat(result.getInFlightCount()).isEqualTo(1);
        assertThat(result.getTaskStatus()).isEqualTo(FillTaskStatusEnum.RUNNING.getStatus());
        verify(workItemMapper).compareAndSetStatus(1L, FillWorkItemStatusEnum.PENDING.getStatus(), cancelled);
        verify(workItemMapper).compareAndSetStatus(2L, FillWorkItemStatusEnum.FILLING.getStatus(), cancelled);
        verify(workItemMapper).compareAndSetStatus(3L, reworking, cancelled);
        verify(workItemMapper, never()).compareAndSetStatus(4L, reworking, cancelled);
        verify(workItemMapper, never()).compareAndSetStatus(eq(5L), any(), any());
        ArgumentCaptor<FillCorrectionDO> correction = ArgumentCaptor.forClass(FillCorrectionDO.class);
        verify(correctionMapper).updateById(correction.capture());
        assertThat(correction.getValue().getStatus()).isEqualTo(FillCorrectionStatusEnum.CANCELLED.getStatus());
        ArgumentCaptor<FillTaskDO> taskUpdate = ArgumentCaptor.forClass(FillTaskDO.class);
        verify(taskMapper).updateById(taskUpdate.capture());
        assertThat(taskUpdate.getValue().getMergedOrgCount()).isEqualTo(2);
        assertThat(taskUpdate.getValue().getStatus()).isEqualTo(FillTaskStatusEnum.RUNNING.getStatus());
        verify(notifyService, times(4)).sendToUsers(eq(FillNotifyTypeEnum.TASK_CLOSED), any(), any(), any(), any());
    }

    @Test
    void closeFinishesImmediatelyWhenNothingIsInFlight() {
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.RUNNING.getStatus()));
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(
                item(1L, FillWorkItemStatusEnum.PENDING), item(6L, FillWorkItemStatusEnum.MERGED)));
        when(workItemMapper.compareAndSetStatus(anyLong(), any(), any())).thenReturn(1);

        assertThat(taskService().closeTask(9L).getTaskStatus()).isEqualTo(FillTaskStatusEnum.FINISHED.getStatus());
    }

    @Test
    void closeWithoutAnyMergedOrgCancelsTheTask() {
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.PENDING.getStatus()));
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(item(1L, FillWorkItemStatusEnum.PENDING)));
        when(workItemMapper.compareAndSetStatus(anyLong(), any(), any())).thenReturn(1);

        assertThat(taskService().closeTask(9L).getTaskStatus()).isEqualTo(FillTaskStatusEnum.CANCELLED.getStatus());
    }

    @Test
    void closeRereadsAfterConcurrentTransitionAndTreatsSubmissionAsInFlight() {
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.RUNNING.getStatus()));
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(item(1L, FillWorkItemStatusEnum.FILLING)));
        // 结束时填报人恰好提交：CAS 失败，重读后已进入提交校验，不取消
        when(workItemMapper.compareAndSetStatus(anyLong(), any(), any())).thenReturn(0);
        when(workItemMapper.selectById(1L)).thenReturn(item(1L, FillWorkItemStatusEnum.SUBMIT_VALIDATING));

        FillTaskCloseRespVO result = taskService().closeTask(9L);

        assertThat(result.getCancelledCount()).isZero();
        assertThat(result.getInFlightCount()).isEqualTo(1);
        verify(notifyService, never()).sendToUsers(any(), any(), any(), any(), any());
    }

    @Test
    void closeRejectsFinishedTask() {
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.FINISHED.getStatus()));

        assertThatThrownBy(() -> taskService().closeTask(9L))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_STATUS_INVALID.getCode()));
        verify(workItemMapper, never()).selectListByTaskId(anyLong());
    }

    // ---------------------------------------------------------------- 截止时间延期

    private FillWorkItemServiceImpl workItemService() {
        FillWorkItemServiceImpl service = new FillWorkItemServiceImpl();
        ReflectionTestUtils.setField(service, "accessGuard", org.mockito.Mockito.mock(FillTaskAccessGuard.class));
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        ReflectionTestUtils.setField(service, "collabService", collabService);
        return service;
    }

    private static FillTaskExtendReqVO extendReq(LocalDateTime deadline, List<Long> workItemIds) {
        FillTaskExtendReqVO reqVO = new FillTaskExtendReqVO();
        reqVO.setTaskId(9L);
        reqVO.setDeadlineTime(deadline);
        reqVO.setWorkItemIds(workItemIds);
        return reqVO;
    }

    @Test
    void extendWholeTaskPushesBackUnsubmittedItemsClearsOverdueAndNeverShortens() {
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(2);
        FillWorkItemDO laterAlready = item(3L, FillWorkItemStatusEnum.FILLING);
        laterAlready.setDeadlineTime(newDeadline.plusDays(1));
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.RUNNING.getStatus()));
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(
                item(1L, FillWorkItemStatusEnum.PENDING),
                item(2L, FillWorkItemStatusEnum.APPROVING),
                laterAlready));

        int extended = workItemService().extendDeadline(extendReq(newDeadline, null));

        assertThat(extended).isEqualTo(1);
        ArgumentCaptor<FillWorkItemDO> update = ArgumentCaptor.forClass(FillWorkItemDO.class);
        verify(workItemMapper).updateById(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(1L);
        assertThat(update.getValue().getDeadlineTime()).isEqualTo(newDeadline);
        assertThat(update.getValue().getOverdue()).isZero();
        ArgumentCaptor<FillTaskDO> taskUpdate = ArgumentCaptor.forClass(FillTaskDO.class);
        verify(taskMapper).updateById(taskUpdate.capture());
        assertThat(taskUpdate.getValue().getDeadlineTime()).isEqualTo(newDeadline);
        verify(notifyService).sendToUsers(eq(FillNotifyTypeEnum.DEADLINE_EXTENDED), any(),
                any(FillWorkItemDO.class), any(), any());
        verify(collabService).broadcast(eq(1L), eq(FillSubmissionServiceImpl.WS_STATUS_CHANGED), any());
    }

    @Test
    void extendSpecificItemsValidatesOwnershipAndStatusAndKeepsTaskDeadline() {
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(2);
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.RUNNING.getStatus()));
        FillWorkItemDO otherTask = item(7L, FillWorkItemStatusEnum.FILLING);
        otherTask.setTaskId(10L);
        when(workItemMapper.selectListByIds(any())).thenReturn(List.of(otherTask));
        assertThatThrownBy(() -> workItemService().extendDeadline(extendReq(newDeadline, List.of(7L))))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_WORK_ITEM_MISMATCH.getCode()));

        when(workItemMapper.selectListByIds(any())).thenReturn(List.of(item(2L, FillWorkItemStatusEnum.APPROVING)));
        assertThatThrownBy(() -> workItemService().extendDeadline(extendReq(newDeadline, List.of(2L))))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_WORK_ITEM_STATUS_INVALID.getCode()));

        when(workItemMapper.selectListByIds(any())).thenReturn(List.of(item(1L, FillWorkItemStatusEnum.REWORKING)));
        assertThat(workItemService().extendDeadline(extendReq(newDeadline, List.of(1L)))).isEqualTo(1);
        verify(taskMapper, never()).updateById(any(FillTaskDO.class));
    }

    @Test
    void extendRejectsPastDeadlineAndClosedTask() {
        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.RUNNING.getStatus()));
        assertThatThrownBy(() -> workItemService().extendDeadline(
                extendReq(LocalDateTime.now().minusMinutes(1), null)))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_DEADLINE_INVALID.getCode()));

        when(taskMapper.selectById(9L)).thenReturn(task(FillTaskStatusEnum.CANCELLED.getStatus()));
        assertThatThrownBy(() -> workItemService().extendDeadline(
                extendReq(LocalDateTime.now().plusDays(1), null)))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_STATUS_INVALID.getCode()));
        verify(workItemMapper, never()).updateById(any(FillWorkItemDO.class));
    }

}
