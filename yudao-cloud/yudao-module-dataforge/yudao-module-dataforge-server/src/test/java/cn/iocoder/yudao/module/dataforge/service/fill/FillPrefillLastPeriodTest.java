package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDraftRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillOperationDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDraftRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillOperationMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOperationTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPeriodTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillSubmissionTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上期数据带入测试
 */
class FillPrefillLastPeriodTest {

    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillDraftRowMapper draftRowMapper = mock(FillDraftRowMapper.class);
    private final FillOperationMapper operationMapper = mock(FillOperationMapper.class);
    private final FillSubmissionRowMapper submissionRowMapper = mock(FillSubmissionRowMapper.class);
    private final FillTableVersionService tableVersionService = mock(FillTableVersionService.class);

    private static final String FIELD_SCHEMA = JsonUtils.toJsonString(List.of(
            Map.of("fieldCode", "code", "pk", true, "editable", true),
            Map.of("fieldCode", "name", "editable", true),
            Map.of("fieldCode", "amount", "editable", true),
            Map.of("fieldCode", "secret", "hidden", true, "editable", true),
            Map.of("fieldCode", "total", "editable", false)));

    private FillDraftServiceImpl draftService() {
        FillDraftServiceImpl service = new FillDraftServiceImpl();
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "draftRowMapper", draftRowMapper);
        ReflectionTestUtils.setField(service, "operationMapper", operationMapper);
        ReflectionTestUtils.setField(service, "submissionRowMapper", submissionRowMapper);
        ReflectionTestUtils.setField(service, "tableVersionService", tableVersionService);
        when(tableVersionService.getTableVersion(5L))
                .thenReturn(FillTableVersionDO.builder().id(5L).fieldSchema(FIELD_SCHEMA).build());
        return service;
    }

    private static FillWorkItemDO newItem() {
        return FillWorkItemDO.builder().id(21L).taskId(2L).tableVersionId(5L).reportingOrgId(300L)
                .status(FillWorkItemStatusEnum.PENDING.getStatus()).serverVersion(0L).draftRowCount(0).build();
    }

    private static FillSubmissionRowDO sourceRow(String rowId, Map<String, Object> data) {
        return FillSubmissionRowDO.builder().submissionId(77L).rowId(rowId).rowOrder(BigDecimal.ONE)
                .data(JsonUtils.toJsonString(data)).build();
    }

    @Test
    void prefillCopiesOnlyEditableVisibleNonKeyFieldsAndLogsPrefillOperations() {
        FillDraftServiceImpl service = draftService();
        when(submissionRowMapper.selectListBySubmissionId(77L)).thenReturn(List.of(
                sourceRow("r1", Map.of("code", "K1", "name", "甲", "amount", 10, "secret", "x", "total", 99)),
                sourceRow("r2", Map.of("code", "K2", "secret", "y")),
                sourceRow("r3", Map.of("name", "乙"))));
        when(workItemMapper.advanceServerVersionBy(21L, 0L, 2L, 8L)).thenReturn(1);
        when(draftRowMapper.selectCount(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.toolkit.support.SFunction<FillDraftRowDO, ?>>any(),
                any())).thenReturn(2L);

        int rows = service.prefillFromSubmission(newItem(), 77L, 8L);

        assertThat(rows).isEqualTo(2);
        ArgumentCaptor<FillDraftRowDO> draft = ArgumentCaptor.forClass(FillDraftRowDO.class);
        verify(draftRowMapper, times(2)).insert(draft.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = JsonUtils.parseObject(draft.getAllValues().get(0).getData(), Map.class);
        assertThat(first).containsOnlyKeys("name", "amount");
        assertThat(draft.getAllValues().get(0).getRowId()).startsWith("pf").isNotEqualTo("r1");
        assertThat(draft.getAllValues().get(1).getRowOrder())
                .isGreaterThan(draft.getAllValues().get(0).getRowOrder());

        ArgumentCaptor<FillOperationDO> ops = ArgumentCaptor.forClass(FillOperationDO.class);
        verify(operationMapper, times(2)).insert(ops.capture());
        assertThat(ops.getAllValues()).extracting(FillOperationDO::getOpType)
                .containsOnly(FillOperationTypeEnum.PREFILL.getType());
        assertThat(ops.getAllValues()).extracting(FillOperationDO::getServerVersion).containsExactly(1L, 2L);
        assertThat(ops.getAllValues()).extracting(FillOperationDO::getOperatorUserId).containsOnly(8L);
    }

    @Test
    void prefillSkipsWhenDraftWasAlreadyEdited() {
        FillDraftServiceImpl service = draftService();
        when(submissionRowMapper.selectListBySubmissionId(77L)).thenReturn(List.of(
                sourceRow("r1", Map.of("name", "甲"))));
        when(workItemMapper.advanceServerVersionBy(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(0);

        assertThat(service.prefillFromSubmission(newItem(), 77L, 8L)).isZero();
        verify(draftRowMapper, never()).insert(any(FillDraftRowDO.class));
        verify(operationMapper, never()).insert(any(FillOperationDO.class));
    }

    // ---------------------------------------------------------------- 任务生成时的来源选择

    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillPlanOrgMapper planOrgMapper = mock(FillPlanOrgMapper.class);
    private final FillPlanOrgAssigneeMapper planOrgAssigneeMapper = mock(FillPlanOrgAssigneeMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillDraftService prefillDraftService = mock(FillDraftService.class);

    private FillTaskServiceImpl taskService() {
        FillTaskServiceImpl service = new FillTaskServiceImpl();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "planOrgMapper", planOrgMapper);
        ReflectionTestUtils.setField(service, "planOrgAssigneeMapper", planOrgAssigneeMapper);
        ReflectionTestUtils.setField(service, "notifyService", mock(FillNotifyService.class));
        FillPreviousPeriodService previousPeriodService = new FillPreviousPeriodService();
        ReflectionTestUtils.setField(previousPeriodService, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(previousPeriodService, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "previousPeriodService", previousPeriodService);
        ReflectionTestUtils.setField(service, "draftService", prefillDraftService);
        doAnswer(invocation -> {
            FillTaskDO task = invocation.getArgument(0);
            task.setId(2L);
            return 1;
        }).when(taskMapper).insert(any(FillTaskDO.class));
        doAnswer(invocation -> {
            FillWorkItemDO item = invocation.getArgument(0);
            item.setId(20L + item.getReportingOrgId() / 100);
            return 1;
        }).when(workItemMapper).insert(any(FillWorkItemDO.class));
        when(planOrgMapper.selectListByPlanId(1L)).thenReturn(List.of(
                FillPlanOrgDO.builder().id(31L).planId(1L).reportingOrgId(100L).orgName("甲单位").build(),
                FillPlanOrgDO.builder().id(32L).planId(1L).reportingOrgId(200L).orgName("乙单位").build(),
                FillPlanOrgDO.builder().id(33L).planId(1L).reportingOrgId(300L).orgName("丙单位").build()));
        return service;
    }

    private static FillPlanDO monthlyPlan(int prefill) {
        FillPlanDO plan = FillPlanDO.builder().id(1L).name("月报").tableId(4L).tableVersionId(5L)
                .periodType(FillPeriodTypeEnum.MONTHLY.getType()).prefillLastPeriod(prefill).build();
        plan.setCreator("8");
        return plan;
    }

    @Test
    void generationPrefillsOnlyOrgsWithApprovedNonEmptyLastPeriodData() {
        FillTaskServiceImpl service = taskService();
        when(taskMapper.selectPreviousTask(any(), any(), any()))
                .thenReturn(FillTaskDO.builder().id(1L).planId(1L).build());
        // 甲：上期审批通过 → 带入；乙：上期零上报 → 跳过；丙：上期被驳回 → 跳过
        when(workItemMapper.selectByTaskIdAndOrgId(1L, 100L))
                .thenReturn(FillWorkItemDO.builder().id(11L).currentSubmissionId(71L).build());
        when(workItemMapper.selectByTaskIdAndOrgId(1L, 200L))
                .thenReturn(FillWorkItemDO.builder().id(12L).currentSubmissionId(72L).build());
        when(workItemMapper.selectByTaskIdAndOrgId(1L, 300L))
                .thenReturn(FillWorkItemDO.builder().id(13L).currentSubmissionId(73L).build());
        when(submissionMapper.selectById(71L)).thenReturn(FillSubmissionDO.builder().id(71L)
                .approvalStatus(FillApprovalStatusEnum.APPROVED.getStatus())
                .submissionType(FillSubmissionTypeEnum.NORMAL.getType()).build());
        when(submissionMapper.selectById(72L)).thenReturn(FillSubmissionDO.builder().id(72L)
                .approvalStatus(FillApprovalStatusEnum.APPROVED.getStatus())
                .submissionType(FillSubmissionTypeEnum.EMPTY.getType()).build());
        when(submissionMapper.selectById(73L)).thenReturn(FillSubmissionDO.builder().id(73L)
                .approvalStatus(FillApprovalStatusEnum.REJECTED.getStatus())
                .submissionType(FillSubmissionTypeEnum.NORMAL.getType()).build());

        service.generateForPlan(monthlyPlan(1), LocalDateTime.of(2026, 11, 1, 8, 0));

        ArgumentCaptor<FillWorkItemDO> target = ArgumentCaptor.forClass(FillWorkItemDO.class);
        verify(prefillDraftService).prefillFromSubmission(target.capture(), any(), any());
        assertThat(target.getValue().getReportingOrgId()).isEqualTo(100L);
        verify(prefillDraftService).prefillFromSubmission(any(), org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq(8L));
    }

    @Test
    void generationDoesNotPrefillWhenSwitchIsOff() {
        FillTaskServiceImpl service = taskService();

        service.generateForPlan(monthlyPlan(0), LocalDateTime.of(2026, 11, 1, 8, 0));

        verify(taskMapper, never()).selectPreviousTask(any(), any(), any());
        verify(prefillDraftService, never()).prefillFromSubmission(any(), any(), any());
    }

}
