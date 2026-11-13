package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionCreateReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillCorrectionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillCorrectionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillCorrectionStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeStrategyEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据更正被驳回、撤回后重新提交的状态流转测试
 */
class FillCorrectionReworkTest {

    private final FillCorrectionMapper correctionMapper = mock(FillCorrectionMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillTableVersionService tableVersionService = mock(FillTableVersionService.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);

    private FillCorrectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FillCorrectionServiceImpl();
        ReflectionTestUtils.setField(service, "correctionMapper", correctionMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "tableVersionService", tableVersionService);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
    }

    private static FillCorrectionDO correction(FillCorrectionStatusEnum status) {
        return FillCorrectionDO.builder().id(7L).workItemId(11L).originSubmissionId(100L)
                .correctionSubmissionId(101L).status(status.getStatus()).build();
    }

    private FillCorrectionDO lastUpdate(int times) {
        ArgumentCaptor<FillCorrectionDO> captor = ArgumentCaptor.forClass(FillCorrectionDO.class);
        verify(correctionMapper, times(times)).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void rejectedCorrectionSubmissionReturnsToDraft() {
        when(correctionMapper.selectByCorrectionSubmissionId(101L))
                .thenReturn(correction(FillCorrectionStatusEnum.APPROVING));

        assertTrue(service.reopenBySubmissionId(101L));

        assertEquals(FillCorrectionStatusEnum.DRAFT.getStatus(), lastUpdate(1).getStatus());
    }

    @Test
    void reopenIgnoresNormalSubmissionsAndFinishedCorrections() {
        when(correctionMapper.selectByCorrectionSubmissionId(55L)).thenReturn(null);
        when(correctionMapper.selectByCorrectionSubmissionId(101L))
                .thenReturn(correction(FillCorrectionStatusEnum.MERGED));

        assertFalse(service.reopenBySubmissionId(55L));
        assertFalse(service.reopenBySubmissionId(101L));

        verify(correctionMapper, never()).updateById(any(FillCorrectionDO.class));
    }

    @Test
    void resubmissionAfterRejectionStillBelongsToTheCorrection() {
        // 历史数据：更正被驳回后状态仍停在审批中；再次提交必须继续按更正处理并改绑到新快照
        when(correctionMapper.selectActiveByWorkItemId(11L))
                .thenReturn(correction(FillCorrectionStatusEnum.APPROVING));

        assertTrue(service.hasActiveCorrection(11L));
        assertTrue(service.bindSubmissionIfCorrecting(11L, 102L));

        FillCorrectionDO update = lastUpdate(1);
        assertEquals(102L, update.getCorrectionSubmissionId());
        assertEquals(FillCorrectionStatusEnum.APPROVING.getStatus(), update.getStatus());
    }

    @Test
    void mergedItemWithLeftoverCorrectionCanBeCorrectedAgain() {
        // 实例已合并了比更正来源更新的快照，但旧更正仍为进行中：收尾为已合并后允许再次发起
        FillWorkItemDO item = FillWorkItemDO.builder().id(11L).taskId(9L)
                .status(FillWorkItemStatusEnum.MERGED.getStatus()).currentSubmissionId(102L).build();
        when(submissionMapper.selectById(102L)).thenReturn(
                FillSubmissionDO.builder().id(102L).workItemId(11L).tableVersionId(3L).build());
        when(workItemMapper.selectById(11L)).thenReturn(item);
        when(tableVersionService.getTableVersion(3L)).thenReturn(FillTableVersionDO.builder()
                .mergeStrategy(FillMergeStrategyEnum.REPLACE_SCOPE.getStrategy()).build());
        when(correctionMapper.selectActiveByWorkItemId(11L))
                .thenReturn(correction(FillCorrectionStatusEnum.APPROVING));
        when(workItemMapper.compareAndSetStatus(11L, FillWorkItemStatusEnum.MERGED.getStatus(),
                FillWorkItemStatusEnum.REWORKING.getStatus())).thenReturn(1);
        when(assigneeMapper.selectValidListByWorkItemId(11L)).thenReturn(List.of());

        FillCorrectionCreateReqVO reqVO = new FillCorrectionCreateReqVO();
        reqVO.setSubmissionId(102L);
        reqVO.setReason("口径调整");
        service.createCorrection(reqVO);

        assertEquals(FillCorrectionStatusEnum.MERGED.getStatus(), lastUpdate(1).getStatus());
        verify(correctionMapper).insert(any(FillCorrectionDO.class));
    }

}
