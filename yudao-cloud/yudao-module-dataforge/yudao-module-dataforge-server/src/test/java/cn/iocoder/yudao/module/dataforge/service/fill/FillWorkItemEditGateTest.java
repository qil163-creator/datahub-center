package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillCorrectionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillCorrectionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeStateEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOverduePolicyEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_WORK_ITEM_NOT_OPEN;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_WORK_ITEM_OVERDUE_LOCKED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 填报编辑拦截（开放时间、硬截止与更正）及发起者查看权限测试
 */
class FillWorkItemEditGateTest {

    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillPlanMapper planMapper = mock(FillPlanMapper.class);
    private final FillCorrectionMapper correctionMapper = mock(FillCorrectionMapper.class);
    private final DeptApi deptApi = mock(DeptApi.class);

    private FillWorkItemServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FillWorkItemServiceImpl();
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "planMapper", planMapper);
        ReflectionTestUtils.setField(service, "correctionMapper", correctionMapper);
        ReflectionTestUtils.setField(service, "deptApi", deptApi);
        when(assigneeMapper.selectByWorkItemIdAndUserId(11L, 88L)).thenReturn(
                FillAssigneeDO.builder().userId(88L).state(FillAssigneeStateEnum.VALID.getState()).build());
        when(deptApi.getDeptMap(any())).thenReturn(Map.of());
    }

    private static FillWorkItemDO item(FillWorkItemStatusEnum status, LocalDateTime deadline) {
        return FillWorkItemDO.builder().id(11L).taskId(9L).planId(1L).reportingOrgId(30L)
                .status(status.getStatus()).deadlineTime(deadline).build();
    }

    private void task(LocalDateTime openTime, FillOverduePolicyEnum policy) {
        when(taskMapper.selectById(9L)).thenReturn(FillTaskDO.builder().id(9L).openTime(openTime)
                .overduePolicy(policy.getPolicy()).build());
    }

    private int editError(FillWorkItemDO item) {
        return assertThrows(ServiceException.class, () -> service.checkEditPermission(item, 88L)).getCode();
    }

    @Test
    void editingIsBlockedBeforeTheOpenTime() {
        task(LocalDateTime.now().plusDays(1), FillOverduePolicyEnum.SOFT);

        assertEquals(FILL_WORK_ITEM_NOT_OPEN.getCode(),
                editError(item(FillWorkItemStatusEnum.PENDING, LocalDateTime.now().plusDays(7))));
    }

    @Test
    void editingIsAllowedOnceTheOpenTimeHasPassed() {
        task(LocalDateTime.now().minusMinutes(1), FillOverduePolicyEnum.HARD);

        assertDoesNotThrow(() -> service.checkEditPermission(
                item(FillWorkItemStatusEnum.PENDING, LocalDateTime.now().plusDays(7)), 88L));
    }

    @Test
    void hardDeadlineLocksNormalReworkAfterTheDeadline() {
        task(LocalDateTime.now().minusDays(10), FillOverduePolicyEnum.HARD);

        assertEquals(FILL_WORK_ITEM_OVERDUE_LOCKED.getCode(),
                editError(item(FillWorkItemStatusEnum.REWORKING, LocalDateTime.now().minusDays(1))));
    }

    @Test
    void hardDeadlineDoesNotLockAnActiveCorrection() {
        // 更正是发起者主动重新开放的；任务已完成时延期接口不可用，继续锁定会让更正无法进行
        task(LocalDateTime.now().minusDays(10), FillOverduePolicyEnum.HARD);
        when(correctionMapper.selectActiveByWorkItemId(11L)).thenReturn(FillCorrectionDO.builder().id(7L).build());

        assertDoesNotThrow(() -> service.checkEditPermission(
                item(FillWorkItemStatusEnum.REWORKING, LocalDateTime.now().minusDays(1)), 88L));
    }

    @Test
    void planCreatorCanViewItemsOfScheduledTasks() {
        // 定时任务生成的任务没有创建人
        when(taskMapper.selectById(9L)).thenReturn(FillTaskDO.builder().id(9L).build());
        FillPlanDO plan = FillPlanDO.builder().id(1L).build();
        plan.setCreator("66");
        when(planMapper.selectById(1L)).thenReturn(plan);
        FillWorkItemDO item = item(FillWorkItemStatusEnum.FILLING, LocalDateTime.now().plusDays(1));

        assertTrue(service.hasViewPermission(item, 66L));
        assertFalse(service.hasViewPermission(item, 67L));
    }

}
