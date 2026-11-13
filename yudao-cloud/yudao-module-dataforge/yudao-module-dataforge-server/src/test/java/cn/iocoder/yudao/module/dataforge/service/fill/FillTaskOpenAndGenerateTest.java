package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPeriodTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillTaskStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.module.dataforge.job.fill.FillTaskGenerateJob;
import com.xxl.job.core.context.XxlJobHelper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务到期开放与生成失败不跳期测试
 */
class FillTaskOpenAndGenerateTest {

    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);
    private final FillPlanMapper planMapper = mock(FillPlanMapper.class);
    private final FillTaskService taskService = mock(FillTaskService.class);

    private FillTaskServiceImpl service() {
        FillTaskServiceImpl service = new FillTaskServiceImpl();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        return service;
    }

    private FillTaskGenerateJob job() {
        FillTaskGenerateJob job = new FillTaskGenerateJob();
        ReflectionTestUtils.setField(job, "planMapper", planMapper);
        ReflectionTestUtils.setField(job, "fillTaskService", taskService);
        return job;
    }

    private static FillWorkItemDO item(long id, FillWorkItemStatusEnum status) {
        return FillWorkItemDO.builder().id(id).taskId(9L).status(status.getStatus()).build();
    }

    @Test
    void dueTaskBecomesRunningAndNotifiesAssigneesOfOpenItems() {
        LocalDateTime now = LocalDateTime.now();
        FillTaskDO task = FillTaskDO.builder().id(9L).status(FillTaskStatusEnum.PENDING.getStatus()).build();
        when(taskMapper.selectListToOpen(eq(now), anyInt())).thenReturn(List.of(task));
        when(taskMapper.compareAndSetStatus(9L, FillTaskStatusEnum.PENDING.getStatus(),
                FillTaskStatusEnum.RUNNING.getStatus())).thenReturn(1);
        FillWorkItemDO open = item(1L, FillWorkItemStatusEnum.PENDING);
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(
                List.of(open, item(2L, FillWorkItemStatusEnum.CANCELLED)));
        when(assigneeMapper.selectValidListByWorkItemId(1L))
                .thenReturn(List.of(FillAssigneeDO.builder().userId(88L).build()));

        assertEquals(1, service().openDueTasks(now));

        verify(notifyService).sendToUsers(FillNotifyTypeEnum.TASK_STARTED, task, open, List.of(88L), null);
        verify(notifyService, times(1)).sendToUsers(any(), any(), any(), any(), any());
    }

    @Test
    void taskOpenedByAnotherInstanceIsNotNotifiedTwice() {
        LocalDateTime now = LocalDateTime.now();
        when(taskMapper.selectListToOpen(eq(now), anyInt())).thenReturn(
                List.of(FillTaskDO.builder().id(9L).status(FillTaskStatusEnum.PENDING.getStatus()).build()));
        when(taskMapper.compareAndSetStatus(9L, FillTaskStatusEnum.PENDING.getStatus(),
                FillTaskStatusEnum.RUNNING.getStatus())).thenReturn(0);

        assertEquals(0, service().openDueTasks(now));

        verify(notifyService, never()).sendToUsers(any(), any(), any(), any(), any());
    }

    @Test
    void failedGenerationRollsTheGeneratePointBackInsteadOfSkippingThePeriod() {
        // 月度计划落后两期：第一期生成失败时退回位点并停止，不能继续生成后一期
        LocalDateTime october = LocalDateTime.now().minusMonths(2).withDayOfMonth(1).withHour(8).withMinute(0)
                .withSecond(0).withNano(0);
        FillPlanDO plan = FillPlanDO.builder().id(1L).periodType(FillPeriodTypeEnum.MONTHLY.getType())
                .nextGenerateTime(october).lastPeriodKey("上一期").build();
        when(planMapper.selectListToGenerate(any(), anyInt())).thenReturn(List.of(plan));
        when(planMapper.compareAndSetGeneratePoint(eq(1L), eq(october), any(), anyString())).thenReturn(1);
        when(taskService.generateForPlan(plan, october)).thenThrow(new IllegalStateException("组织快照缺失"));

        try (MockedStatic<XxlJobHelper> helper = mockStatic(XxlJobHelper.class)) {
            job().execute();
            helper.verify(() -> XxlJobHelper.handleFail(contains("计划 1 期次")));
            helper.verify(() -> XxlJobHelper.handleSuccess(anyString()), never());
        }

        verify(planMapper).rollbackGeneratePoint(eq(1L), any(), eq(october), eq("上一期"));
        verify(taskService, times(1)).generateForPlan(any(), any());
    }

    @Test
    void successfulGenerationCatchesUpAndOpensDueTasks() {
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0)
                .withSecond(0).withNano(0);
        FillPlanDO plan = FillPlanDO.builder().id(1L).periodType(FillPeriodTypeEnum.MONTHLY.getType())
                .nextGenerateTime(lastMonth).build();
        when(planMapper.selectListToGenerate(any(), anyInt())).thenReturn(List.of(plan));
        when(planMapper.compareAndSetGeneratePoint(eq(1L), any(), any(), anyString())).thenReturn(1);
        when(taskService.openDueTasks(any())).thenReturn(2);

        try (MockedStatic<XxlJobHelper> helper = mockStatic(XxlJobHelper.class)) {
            job().execute();
            helper.verify(() -> XxlJobHelper.handleSuccess(contains("开放任务 2 个")));
        }

        verify(planMapper, never()).rollbackGeneratePoint(any(), any(), any(), any());
    }

}
