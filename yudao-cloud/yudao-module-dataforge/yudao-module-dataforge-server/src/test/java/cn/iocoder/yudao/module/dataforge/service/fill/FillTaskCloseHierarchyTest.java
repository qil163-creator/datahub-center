package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskCloseRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskProgressRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillCorrectionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillTaskStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多级下发下的结束填报与任务进度：下级不计入完成判断
 */
class FillTaskCloseHierarchyTest {

    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillCorrectionMapper correctionMapper = mock(FillCorrectionMapper.class);

    private FillTaskServiceImpl taskService() {
        FillCorrectionServiceImpl correctionService = new FillCorrectionServiceImpl();
        ReflectionTestUtils.setField(correctionService, "correctionMapper", correctionMapper);
        ReflectionTestUtils.setField(correctionService, "workItemMapper", workItemMapper);
        FillTaskServiceImpl service = new FillTaskServiceImpl();
        ReflectionTestUtils.setField(service, "accessGuard", mock(FillTaskAccessGuard.class));
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", mock(FillAssigneeMapper.class));
        ReflectionTestUtils.setField(service, "notifyService", mock(FillNotifyService.class));
        ReflectionTestUtils.setField(service, "collabService", mock(FillCollabService.class));
        ReflectionTestUtils.setField(service, "correctionService", correctionService);
        return service;
    }

    private static FillWorkItemDO item(long id, Long parentId, FillWorkItemStatusEnum status, Long submissionId) {
        return FillWorkItemDO.builder().id(id).taskId(9L).parentWorkItemId(parentId).orgName("组织" + id)
                .status(status.getStatus()).currentSubmissionId(submissionId).build();
    }

    @Test
    void closeCancelsOpenChildrenStopsParentReviewAndKeepsRolledUp() {
        when(taskMapper.selectById(9L)).thenReturn(FillTaskDO.builder().id(9L)
                .status(FillTaskStatusEnum.RUNNING.getStatus()).build());
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(
                item(1L, null, FillWorkItemStatusEnum.MERGED, 10L),
                item(2L, 1L, FillWorkItemStatusEnum.ROLLED_UP, 20L),
                item(3L, 1L, FillWorkItemStatusEnum.APPROVING, 30L),
                item(4L, 1L, FillWorkItemStatusEnum.FILLING, null)));
        when(submissionMapper.selectById(30L)).thenReturn(FillSubmissionDO.builder().id(30L)
                .reviewMode(FillSubmissionDO.REVIEW_PARENT)
                .approvalStatus(FillApprovalStatusEnum.APPROVING.getStatus()).build());
        when(submissionMapper.markReviewed(eq(30L), eq(FillApprovalStatusEnum.TERMINATED.getStatus()), any(), any(), any()))
                .thenReturn(1);
        when(workItemMapper.compareAndSetStatus(anyLong(), any(), eq(FillWorkItemStatusEnum.CANCELLED.getStatus())))
                .thenReturn(1);

        FillTaskCloseRespVO result = taskService().closeTask(9L);

        // 顶层已合并、没有进行中的顶层 → 任务结束；两个下级被取消，已汇入的保持不变
        assertThat(result.getTaskStatus()).isEqualTo(FillTaskStatusEnum.FINISHED.getStatus());
        assertThat(result.getInFlightCount()).isZero();
        assertThat(result.getCancelledCount()).isEqualTo(2);
        verify(workItemMapper, never()).compareAndSetStatus(eq(2L), any(), any());
    }

    @Test
    void progressCountsTopLevelOnly() {
        when(taskMapper.selectById(9L)).thenReturn(FillTaskDO.builder().id(9L).build());
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(
                item(1L, null, FillWorkItemStatusEnum.MERGED, 10L),
                item(5L, null, FillWorkItemStatusEnum.FILLING, null),
                item(2L, 1L, FillWorkItemStatusEnum.ROLLED_UP, 20L)));

        FillTaskProgressRespVO progress = taskService().getTaskProgress(9L);

        assertThat(progress.getTotalOrgCount()).isEqualTo(2);
        assertThat(progress.getMergedCount()).isEqualTo(1);
        assertThat(progress.getCompletionRate()).isEqualTo(50);
        assertThat(progress.getChildOrgCount()).isEqualTo(1);
    }

}
