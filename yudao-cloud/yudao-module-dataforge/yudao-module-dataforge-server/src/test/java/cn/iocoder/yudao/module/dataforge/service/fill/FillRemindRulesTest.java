package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.module.dataforge.job.fill.FillRemindJob;
import com.xxl.job.core.context.XxlJobHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_URGE_TOO_FREQUENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划提醒规则（截止前提醒提前量、催办频控）生效测试
 */
class FillRemindRulesTest {

    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillPlanMapper planMapper = mock(FillPlanMapper.class);
    private final FillNotifyRecordMapper notifyRecordMapper = mock(FillNotifyRecordMapper.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);

    // ---------------------------------------------------------------- 配置解析

    @Test
    void configFallsBackToDefaultsWhenMissingOutOfRangeOrMalformed() {
        FillRemindConfig empty = FillRemindConfig.parse(null);
        assertThat(empty.resolveHoursBeforeDeadline()).isEqualTo(24);
        assertThat(empty.resolveUrgeCooldownMinutes()).isEqualTo(60);

        FillRemindConfig configured = FillRemindConfig.parse(
                "{\"hoursBeforeDeadline\":48,\"urgeCooldownMinutes\":15,\"unknown\":1}");
        assertThat(configured.resolveHoursBeforeDeadline()).isEqualTo(48);
        assertThat(configured.resolveUrgeCooldownMinutes()).isEqualTo(15);

        FillRemindConfig outOfRange = FillRemindConfig.parse("{\"hoursBeforeDeadline\":0,\"urgeCooldownMinutes\":5000}");
        assertThat(outOfRange.resolveHoursBeforeDeadline()).isEqualTo(24);
        assertThat(outOfRange.resolveUrgeCooldownMinutes()).isEqualTo(60);

        FillRemindConfig malformed = FillRemindConfig.parse("{not json");
        assertThat(malformed.resolveHoursBeforeDeadline()).isEqualTo(24);
    }

    // ---------------------------------------------------------------- 临近截止提醒

    private FillRemindJob remindJob() {
        FillRemindJob job = new FillRemindJob();
        ReflectionTestUtils.setField(job, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(job, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(job, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(job, "planMapper", planMapper);
        ReflectionTestUtils.setField(job, "notifyRecordMapper", notifyRecordMapper);
        ReflectionTestUtils.setField(job, "notifyService", notifyService);
        return job;
    }

    private static FillWorkItemDO item(long id, long planId, LocalDateTime deadline) {
        return FillWorkItemDO.builder().id(id).planId(planId).taskId(9L)
                .status(FillWorkItemStatusEnum.FILLING.getStatus()).deadlineTime(deadline).build();
    }

    @Test
    void remindUsesEachPlansLeadTimeAndScansUpToTheMaximumWindow() {
        LocalDateTime in30Hours = LocalDateTime.now().plusHours(30);
        FillWorkItemDO earlyPlanItem = item(1L, 100L, in30Hours);
        FillWorkItemDO defaultPlanItem = item(2L, 200L, in30Hours);
        when(workItemMapper.selectRemindCandidates(any(), any(), anyInt()))
                .thenReturn(List.of(earlyPlanItem, defaultPlanItem));
        when(planMapper.selectByIds(any())).thenReturn(List.of(
                FillPlanDO.builder().id(100L).remindConfig("{\"hoursBeforeDeadline\":48}").build(),
                FillPlanDO.builder().id(200L).build()));
        when(taskMapper.selectById(9L)).thenReturn(FillTaskDO.builder().id(9L).build());

        try (MockedStatic<XxlJobHelper> ignored = mockStatic(XxlJobHelper.class)) {
            remindJob().execute();
        }

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(workItemMapper).selectRemindCandidates(from.capture(), until.capture(), anyInt());
        assertThat(Duration.between(from.getValue(), until.getValue()).toHours()).isEqualTo(168);
        // 48 小时提前量已进入窗口：提醒；默认 24 小时尚未进入窗口：不提醒
        verify(notifyService).sendToUsers(eq(FillNotifyTypeEnum.DEADLINE_APPROACHING), any(),
                eq(earlyPlanItem), any(), any());
        verify(notifyService, never()).sendToUsers(eq(FillNotifyTypeEnum.DEADLINE_APPROACHING), any(),
                eq(defaultPlanItem), any(), any());
        verify(notifyRecordMapper).selectCountSince(1L, FillNotifyTypeEnum.DEADLINE_APPROACHING.getType(),
                in30Hours.minusHours(48));
    }

    @Test
    void remindIsSkippedWhenAlreadySentInTheCurrentWindow() {
        FillWorkItemDO due = item(1L, 200L, LocalDateTime.now().plusHours(2));
        when(workItemMapper.selectRemindCandidates(any(), any(), anyInt())).thenReturn(List.of(due));
        when(planMapper.selectByIds(any())).thenReturn(List.of(FillPlanDO.builder().id(200L).build()));
        when(notifyRecordMapper.selectCountSince(eq(1L), eq(FillNotifyTypeEnum.DEADLINE_APPROACHING.getType()), any()))
                .thenReturn(1L);

        try (MockedStatic<XxlJobHelper> ignored = mockStatic(XxlJobHelper.class)) {
            remindJob().execute();
        }

        verify(notifyService, never()).sendToUsers(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 催办频控

    private FillWorkItemServiceImpl workItemService() {
        FillWorkItemServiceImpl service = new FillWorkItemServiceImpl();
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "planMapper", planMapper);
        ReflectionTestUtils.setField(service, "notifyRecordMapper", notifyRecordMapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        return service;
    }

    @Test
    void urgeCooldownComesFromPlanConfig() {
        FillWorkItemDO urged = item(1L, 100L, LocalDateTime.now().plusDays(1));
        when(workItemMapper.selectById(1L)).thenReturn(urged);
        when(planMapper.selectById(100L)).thenReturn(
                FillPlanDO.builder().id(100L).remindConfig("{\"urgeCooldownMinutes\":15}").build());
        when(notifyRecordMapper.selectCountSince(eq(1L), eq(FillNotifyTypeEnum.URGE.getType()), any())).thenReturn(1L);

        assertThatThrownBy(() -> workItemService().urgeWorkItem(1L))
                .isInstanceOfSatisfying(ServiceException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(FILL_URGE_TOO_FREQUENT.getCode());
                    assertThat(ex.getMessage()).contains("15");
                });
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notifyRecordMapper).selectCountSince(eq(1L), eq(FillNotifyTypeEnum.URGE.getType()), since.capture());
        assertThat(Duration.between(since.getValue(), LocalDateTime.now()).toMinutes()).isBetween(14L, 15L);
    }

}
