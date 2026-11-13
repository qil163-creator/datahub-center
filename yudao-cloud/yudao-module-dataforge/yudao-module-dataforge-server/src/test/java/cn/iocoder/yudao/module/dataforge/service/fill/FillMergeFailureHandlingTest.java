package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillMergeBatchDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillMergeBatchMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeBatchStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 合并失败处理测试：只通知一次、执行中超时接管、退回整改
 */
class FillMergeFailureHandlingTest {

    private final FillMergeBatchMapper mergeBatchMapper = mock(FillMergeBatchMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);
    private final FillCollabService collabService = mock(FillCollabService.class);
    private final FillCorrectionService correctionService = mock(FillCorrectionService.class);

    private FillMergeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FillMergeServiceImpl();
        ReflectionTestUtils.setField(service, "mergeBatchMapper", mergeBatchMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        ReflectionTestUtils.setField(service, "collabService", collabService);
        ReflectionTestUtils.setField(service, "correctionService", correctionService);
        when(assigneeMapper.selectValidListByWorkItemId(anyLong()))
                .thenReturn(List.of(FillAssigneeDO.builder().userId(88L).build()));
    }

    /** 提交快照查不到会让合并在读取阶段失败，用来模拟一次失败的执行 */
    private FillMergeBatchDO batch(FillMergeBatchStatusEnum status, int retryCount) {
        FillMergeBatchDO batch = FillMergeBatchDO.builder().id(3L).submissionId(101L).workItemId(11L).taskId(9L)
                .status(status.getStatus()).retryCount(retryCount).build();
        when(mergeBatchMapper.selectById(3L)).thenReturn(batch);
        return batch;
    }

    private FillMergeBatchDO lastBatchUpdate() {
        ArgumentCaptor<FillMergeBatchDO> captor = ArgumentCaptor.forClass(FillMergeBatchDO.class);
        verify(mergeBatchMapper).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void firstFailureNotifiesAssignees() {
        batch(FillMergeBatchStatusEnum.PENDING, 0);
        when(mergeBatchMapper.compareAndSetRunning(3L, FillMergeBatchStatusEnum.PENDING.getStatus())).thenReturn(1);

        service.executeBatch(3L);

        FillMergeBatchDO update = lastBatchUpdate();
        assertEquals(FillMergeBatchStatusEnum.FAILED.getStatus(), update.getStatus());
        assertEquals(1, update.getRetryCount());
        verify(notifyService).sendToUsers(eq(FillNotifyTypeEnum.MERGE_FAILED), any(), any(), eq(List.of(88L)), any());
    }

    @Test
    void repeatedFailureDoesNotNotifyAgain() {
        batch(FillMergeBatchStatusEnum.FAILED, 3);
        when(mergeBatchMapper.compareAndSetRunning(3L, FillMergeBatchStatusEnum.FAILED.getStatus())).thenReturn(1);

        service.executeBatch(3L);

        assertEquals(4, lastBatchUpdate().getRetryCount());
        verify(notifyService, never()).sendToUsers(any(), any(), any(), any(), any());
        verify(workItemMapper).compareAndSetStatus(11L, FillWorkItemStatusEnum.MERGING.getStatus(),
                FillWorkItemStatusEnum.MERGE_FAILED.getStatus());
    }

    @Test
    void runningBatchIsOnlyTakenOverWhenStale() {
        batch(FillMergeBatchStatusEnum.RUNNING, 0);
        when(mergeBatchMapper.takeOverStaleRunning(eq(3L), any())).thenReturn(0);

        service.executeBatch(3L);

        // 仍在正常执行窗口内：不重复执行
        verify(submissionMapper, never()).selectById(anyLong());
        verify(mergeBatchMapper, never()).compareAndSetRunning(anyLong(), any());
    }

    @Test
    void staleRunningBatchIsExecutedAgain() {
        batch(FillMergeBatchStatusEnum.RUNNING, 0);
        when(mergeBatchMapper.takeOverStaleRunning(eq(3L), any())).thenReturn(1);

        service.executeBatch(3L);

        verify(submissionMapper).selectById(101L);
    }

    @Test
    void finishedBatchIsNeverExecutedAgain() {
        batch(FillMergeBatchStatusEnum.SUCCESS, 0);

        service.executeBatch(3L);

        verify(mergeBatchMapper, never()).compareAndSetRunning(anyLong(), any());
        verify(submissionMapper, never()).selectById(anyLong());
    }

    @Test
    void returnToReworkMovesItemAndNotifiesWithReason() {
        batch(FillMergeBatchStatusEnum.FAILED, 8);
        when(mergeBatchMapper.compareAndSetStatus(3L, FillMergeBatchStatusEnum.FAILED.getStatus(),
                FillMergeBatchStatusEnum.RETURNED.getStatus())).thenReturn(1);
        when(workItemMapper.compareAndSetStatus(11L, FillWorkItemStatusEnum.MERGE_FAILED.getStatus(),
                FillWorkItemStatusEnum.REWORKING.getStatus())).thenReturn(1);

        service.returnToRework(3L, " 证件号与正式表重复 ");

        verify(correctionService).reopenBySubmissionId(101L);
        verify(notifyService).sendToUsers(eq(FillNotifyTypeEnum.MERGE_RETURNED), any(), any(), eq(List.of(88L)),
                eq(Map.of("reason", "证件号与正式表重复")));
    }

    @Test
    void returnToReworkRejectsBatchThatIsNotFailed() {
        batch(FillMergeBatchStatusEnum.RUNNING, 1);
        when(mergeBatchMapper.compareAndSetStatus(3L, FillMergeBatchStatusEnum.FAILED.getStatus(),
                FillMergeBatchStatusEnum.RETURNED.getStatus())).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.returnToRework(3L, "原因"));

        verify(workItemMapper, never()).compareAndSetStatus(anyLong(), any(), any());
        verify(notifyService, never()).sendToUsers(any(), any(), any(), any(), any());
    }

    @Test
    void returnToReworkFailsWhenItemAlreadyLeftMergeFailed() {
        batch(FillMergeBatchStatusEnum.FAILED, 2);
        when(mergeBatchMapper.compareAndSetStatus(3L, FillMergeBatchStatusEnum.FAILED.getStatus(),
                FillMergeBatchStatusEnum.RETURNED.getStatus())).thenReturn(1);
        when(workItemMapper.compareAndSetStatus(11L, FillWorkItemStatusEnum.MERGE_FAILED.getStatus(),
                FillWorkItemStatusEnum.REWORKING.getStatus())).thenReturn(0);

        // 抛错后由事务回滚批次状态
        assertThrows(ServiceException.class, () -> service.returnToRework(3L, "原因"));

        verify(correctionService, never()).reopenBySubmissionId(anyLong());
    }

}
