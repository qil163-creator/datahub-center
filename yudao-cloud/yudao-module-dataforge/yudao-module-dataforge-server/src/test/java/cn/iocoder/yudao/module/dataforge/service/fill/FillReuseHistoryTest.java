package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillReuseReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDraftRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDraftRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOperationTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复用任意历史期次数据：来源范围与带入操作
 */
class FillReuseHistoryTest {

    private final FillWorkItemService workItemService = mock(FillWorkItemService.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillPreviousPeriodService previousPeriodService = mock(FillPreviousPeriodService.class);
    private final FillDraftRowMapper draftRowMapper = mock(FillDraftRowMapper.class);
    private final FillSubmissionRowMapper submissionRowMapper = mock(FillSubmissionRowMapper.class);
    private final FillTableVersionService tableVersionService = mock(FillTableVersionService.class);

    private final FillWorkItemDO item = FillWorkItemDO.builder().id(21L).taskId(3L).tableVersionId(5L)
            .reportingOrgId(300L).build();
    private final FillTaskDO current = FillTaskDO.builder().id(3L).planId(1L)
            .periodStart(LocalDateTime.of(2026, 10, 1, 0, 0)).build();
    private final FillTaskDO august = FillTaskDO.builder().id(1L).planId(1L).name("月报（2026-08）").periodKey("2026-08").build();
    private final FillTaskDO september = FillTaskDO.builder().id(2L).planId(1L).name("月报（2026-09）").periodKey("2026-09").build();

    private FillDraftServiceImpl service;

    @BeforeEach
    void setUp() {
        FillDraftServiceImpl target = new FillDraftServiceImpl();
        ReflectionTestUtils.setField(target, "workItemService", workItemService);
        ReflectionTestUtils.setField(target, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(target, "previousPeriodService", previousPeriodService);
        ReflectionTestUtils.setField(target, "draftRowMapper", draftRowMapper);
        ReflectionTestUtils.setField(target, "submissionRowMapper", submissionRowMapper);
        ReflectionTestUtils.setField(target, "tableVersionService", tableVersionService);
        service = spy(target);
        when(workItemService.getWorkItemValidated(21L)).thenReturn(item);
        when(taskMapper.selectById(3L)).thenReturn(current);
        when(taskMapper.selectEarlierTasks(eq(1L), eq(current.getPeriodStart()), eq(3L), any(Integer.class)))
                .thenReturn(List.of(september, august));
        // 9 月零上报（不可复用），8 月有数据
        when(previousPeriodService.findApprovedSubmission(september, 300L))
                .thenReturn(FillSubmissionDO.builder().id(72L).rowCount(0).build());
        when(previousPeriodService.findApprovedSubmission(august, 300L))
                .thenReturn(FillSubmissionDO.builder().id(71L).rowCount(2).build());
        when(tableVersionService.getTableVersion(5L)).thenReturn(FillTableVersionDO.builder().id(5L)
                .fieldSchema(JsonUtils.toJsonString(List.of(
                        Map.of("fieldCode", "name", "editable", true),
                        Map.of("fieldCode", "amount", "editable", true),
                        Map.of("fieldCode", "total", "editable", false)))).build());
        doReturn(new FillOperationApplyRespVO()).when(service).applyOperations(eq(21L), any());
    }

    private static FillReuseReqVO req(long submissionId, int mode) {
        FillReuseReqVO reqVO = new FillReuseReqVO();
        reqVO.setSubmissionId(submissionId);
        reqVO.setMode(mode);
        reqVO.setExpectedVersion(8L);
        return reqVO;
    }

    @Test
    void listsEarlierApprovedPeriodsThatHaveData() {
        assertThat(service.getReuseSources(21L)).singleElement()
                .satisfies(source -> {
                    assertThat(source.getSubmissionId()).isEqualTo(71L);
                    assertThat(source.getPeriodKey()).isEqualTo("2026-08");
                });
    }

    @Test
    void replaceRemovesCurrentRowsThenAddsOnlyEditableValues() {
        when(draftRowMapper.selectListByWorkItemId(21L)).thenReturn(List.of(
                FillDraftRowDO.builder().rowId("old1").build()));
        when(submissionRowMapper.selectListBySubmissionId(71L)).thenReturn(List.of(
                FillSubmissionRowDO.builder().rowId("s1").rowOrder(BigDecimal.ONE)
                        .data(JsonUtils.toJsonString(Map.of("name", "甲", "amount", "10.50", "total", 99))).build()));

        service.reuse(21L, req(71L, 2));

        ArgumentCaptor<FillOperationApplyReqVO> captor = ArgumentCaptor.forClass(FillOperationApplyReqVO.class);
        verify(service).applyOperations(eq(21L), captor.capture());
        List<FillOperationApplyReqVO.OperationItem> ops = captor.getValue().getOperations();
        assertThat(captor.getValue().getExpectedVersion()).isEqualTo(8L);
        assertThat(ops).extracting(FillOperationApplyReqVO.OperationItem::getOpType).containsExactly(
                FillOperationTypeEnum.REMOVE_ROW.getType(), FillOperationTypeEnum.ADD_ROW.getType());
        assertThat(ops.get(0).getRowId()).isEqualTo("old1");
        assertThat(ops.get(1).getValue()).isEqualTo(Map.of("name", "甲", "amount", "10.50"));
    }

    @Test
    void rejectsSourcesOutsideTheReusableListAndKeepsDraftWhenNothingToBringIn() {
        assertThatThrownBy(() -> service.reuse(21L, req(72L, 1))).isInstanceOf(ServiceException.class);
        when(submissionRowMapper.selectListBySubmissionId(71L)).thenReturn(List.of(
                FillSubmissionRowDO.builder().rowId("s1").data(JsonUtils.toJsonString(Map.of("total", 1))).build()));
        assertThatThrownBy(() -> service.reuse(21L, req(71L, 2))).isInstanceOf(ServiceException.class);
        verify(draftRowMapper, never()).selectListByWorkItemId(21L);
        verify(service, never()).applyOperations(eq(21L), any());
    }

}
