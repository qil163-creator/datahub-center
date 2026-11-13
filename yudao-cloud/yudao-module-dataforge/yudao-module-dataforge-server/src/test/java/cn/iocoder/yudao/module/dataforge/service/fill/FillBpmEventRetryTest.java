package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillEventInboxDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventInboxMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillInboxStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BPM 审批回调的失败重试与整体回滚测试
 */
class FillBpmEventRetryTest {

    private final FillEventInboxMapper inboxMapper = mock(FillEventInboxMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillMergeService mergeService = mock(FillMergeService.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);
    private final FillCollabService collabService = mock(FillCollabService.class);
    private final FillCorrectionService correctionService = mock(FillCorrectionService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private FillBpmEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FillBpmEventServiceImpl();
        ReflectionTestUtils.setField(service, "inboxMapper", inboxMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "mergeService", mergeService);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        ReflectionTestUtils.setField(service, "collabService", collabService);
        ReflectionTestUtils.setField(service, "correctionService", correctionService);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        when(assigneeMapper.selectValidListByWorkItemId(anyLong())).thenReturn(List.of());
    }

    private static BpmProcessInstanceStatusEvent event(BpmProcessInstanceStatusEnum status) {
        BpmProcessInstanceStatusEvent event = new BpmProcessInstanceStatusEvent();
        event.setId("pi-1");
        event.setStatus(status.getStatus());
        event.setBusinessKey("101");
        event.setReason("审批意见");
        return event;
    }

    private void newInbox() {
        doAnswer(invocation -> {
            invocation.<FillEventInboxDO>getArgument(0).setId(5L);
            return 1;
        }).when(inboxMapper).insert(any(FillEventInboxDO.class));
    }

    private void existingInbox(boolean retryable) {
        doThrow(new DuplicateKeyException("uk_event_id")).when(inboxMapper).insert(any(FillEventInboxDO.class));
        when(inboxMapper.selectByEventId("pi-1:" + BpmProcessInstanceStatusEnum.APPROVE.getStatus()))
                .thenReturn(FillEventInboxDO.builder().id(5L).build());
        when(inboxMapper.reacquireForRetry(eq(5L), any())).thenReturn(retryable ? 1 : 0);
    }

    private void submission(FillApprovalStatusEnum status) {
        when(submissionMapper.selectByProcessInstanceId("pi-1")).thenReturn(FillSubmissionDO.builder()
                .id(101L).workItemId(11L).taskId(9L).processInstanceId("pi-1")
                .approvalStatus(status.getStatus()).build());
    }

    private Integer inboxResultStatus() {
        ArgumentCaptor<FillEventInboxDO> captor = ArgumentCaptor.forClass(FillEventInboxDO.class);
        verify(inboxMapper).updateById(captor.capture());
        return captor.getValue().getStatus();
    }

    @Test
    void alreadyHandledEventIsIgnored() {
        existingInbox(false);

        service.handleStatusEvent(event(BpmProcessInstanceStatusEnum.APPROVE));

        verify(submissionMapper, never()).selectByProcessInstanceId(any());
        verify(inboxMapper, never()).updateById(any(FillEventInboxDO.class));
    }

    @Test
    void failedEventIsProcessedAgainOnRedelivery() {
        existingInbox(true);
        submission(FillApprovalStatusEnum.APPROVING);

        service.handleStatusEvent(event(BpmProcessInstanceStatusEnum.APPROVE));

        verify(mergeService).createBatchForSubmission(101L);
        verify(workItemMapper).compareAndSetStatus(11L, FillWorkItemStatusEnum.APPROVING.getStatus(),
                FillWorkItemStatusEnum.WAIT_MERGE.getStatus());
        assertEquals(FillInboxStatusEnum.PROCESSED.getStatus(), inboxResultStatus());
        verify(transactionManager).commit(any());
    }

    @Test
    void processingFailureRollsBackAndMarksInboxFailed() {
        newInbox();
        submission(FillApprovalStatusEnum.APPROVING);
        when(mergeService.createBatchForSubmission(101L)).thenThrow(new IllegalStateException("目标配置缺失"));

        service.handleStatusEvent(event(BpmProcessInstanceStatusEnum.APPROVE));

        // 业务处理所在事务回滚，已做的“提交标记通过”不会单独留下
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertEquals(FillInboxStatusEnum.FAILED.getStatus(), inboxResultStatus());
        verify(notifyService, never()).sendToUsers(any(), any(), any(), any(), any());
    }

    @Test
    void repeatedApprovalStillEnsuresMergeBatchExists() {
        // 历史上处理到一半的事件：提交已标记通过，但合并批次没有建出来
        existingInbox(true);
        submission(FillApprovalStatusEnum.APPROVED);

        service.handleStatusEvent(event(BpmProcessInstanceStatusEnum.APPROVE));

        verify(mergeService).createBatchForSubmission(101L);
        verify(submissionMapper, never()).updateById(any(FillSubmissionDO.class));
        verify(notifyService, never()).sendToUsers(eq(FillNotifyTypeEnum.APPROVED), any(), any(), any(), any());
    }

    @Test
    void rejectionReopensCorrectionOfTheSubmission() {
        newInbox();
        submission(FillApprovalStatusEnum.APPROVING);

        service.handleStatusEvent(event(BpmProcessInstanceStatusEnum.REJECT));

        verify(correctionService).reopenBySubmissionId(101L);
        verify(workItemMapper, atLeastOnce()).compareAndSetStatus(11L,
                FillWorkItemStatusEnum.APPROVING.getStatus(), FillWorkItemStatusEnum.REWORKING.getStatus());
    }

}
