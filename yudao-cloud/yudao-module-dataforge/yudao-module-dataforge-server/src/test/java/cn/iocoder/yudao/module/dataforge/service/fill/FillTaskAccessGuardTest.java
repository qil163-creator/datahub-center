package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_NOT_INITIATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务写操作对象级数据范围：发起者或超级管理员
 */
class FillTaskAccessGuardTest {

    private final FillPlanMapper planMapper = mock(FillPlanMapper.class);
    private final PermissionApi permissionApi = mock(PermissionApi.class);
    private final FillTaskAccessGuard guard = new FillTaskAccessGuard();
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(guard, "planMapper", planMapper);
        ReflectionTestUtils.setField(guard, "permissionApi", permissionApi);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(8L);
        FillPlanDO plan = FillPlanDO.builder().id(1L).build();
        plan.setCreator("5");
        when(planMapper.selectById(1L)).thenReturn(plan);
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    private static FillTaskDO task(String creator) {
        FillTaskDO task = FillTaskDO.builder().id(9L).planId(1L).build();
        task.setCreator(creator);
        return task;
    }

    @Test
    void planCreatorAndTaskCreatorPassWithoutRoleLookup() {
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(5L);
        assertThatCode(() -> guard.checkInitiator(task(""))).doesNotThrowAnyException();

        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(8L);
        assertThatCode(() -> guard.checkInitiator(task("8"))).doesNotThrowAnyException();
        verify(permissionApi, never()).hasAnyRoles(anyLong(), any(String[].class));
    }

    @Test
    void superAdminPassesAndOthersAreRejected() {
        when(permissionApi.hasAnyRoles(8L, "super_admin")).thenReturn(CommonResult.success(true));
        assertThatCode(() -> guard.checkInitiator(task("3"))).doesNotThrowAnyException();

        when(permissionApi.hasAnyRoles(8L, "super_admin")).thenReturn(CommonResult.success(false));
        assertThatThrownBy(() -> guard.checkInitiator(task("3")))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_TASK_NOT_INITIATOR.getCode()));
    }

    @Test
    void roleLookupFailureFailsClosed() {
        when(permissionApi.hasAnyRoles(8L, "super_admin")).thenThrow(new IllegalStateException("system 服务不可用"));

        assertThatThrownBy(() -> guard.checkInitiator(task("3"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatedKeyIsStablePerRowAndDistinctAcrossWorkItems() {
        String key = FillMergeServiceImpl.generatedKey(21L, "r1");
        assertThat(key).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(FillMergeServiceImpl.generatedKey(21L, "r1")).isEqualTo(key);
        assertThat(FillMergeServiceImpl.generatedKey(22L, "r1")).isNotEqualTo(key);
        assertThat(FillMergeServiceImpl.generatedKey(21L, "r2")).isNotEqualTo(key);
    }

}
