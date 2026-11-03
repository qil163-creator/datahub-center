package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDepartmentScopeTest {
    private final MarketDepartmentScope scope = new MarketDepartmentScope();
    private final AdminUserApi users = mock(AdminUserApi.class);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(scope, "adminUserApi", users);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser().setId(9L).setInfo(Map.of(LoginUser.INFO_KEY_DEPT_ID, "999")), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void liveDepartmentOverridesTokenAndStrictlyExcludesChildrenAndForeignUsers() {
        when(users.getUser(9L)).thenReturn(CommonResult.success(user(9, 10, 0)));
        when(users.getUserListByDeptIds(List.of(10L))).thenReturn(CommonResult.success(List.of(
                user(12, 10, 0), user(13, 11, 0), user(14, 20, 0), user(12, 10, 0), user(15, 10, 1))));
        var result = scope.current();
        assertThat(result.actorId()).isEqualTo(9L);
        assertThat(result.deptId()).isEqualTo(10L);
        // 已停用同部门用户的历史申请仍属于该部门，不因此抹除历史。
        assertThat(result.userIds()).containsExactly(9L, 12L, 15L);
        verify(users).getUser(9L);
        verify(users).getUserListByDeptIds(List.of(10L));
        verifyNoMoreInteractions(users);
    }

    @Test
    void everyRequestReloadsDepartmentAfterTransfer() {
        when(users.getUser(9L)).thenReturn(CommonResult.success(user(9, 10, 0)), CommonResult.success(user(9, 20, 0)));
        when(users.getUserListByDeptIds(List.of(10L))).thenReturn(CommonResult.success(List.of(user(12, 10, 0))));
        when(users.getUserListByDeptIds(List.of(20L))).thenReturn(CommonResult.success(List.of(user(13, 20, 0))));
        assertThat(scope.current().userIds()).containsExactly(9L, 12L);
        assertThat(scope.current().userIds()).containsExactly(9L, 13L);
        verify(users, times(2)).getUser(9L);
    }

    @Test
    void anonymousMissingDisabledOrUnassignedCurrentUserFailsClosed() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(scope::current).isInstanceOf(ServiceException.class);
        verifyNoInteractions(users);
        configure();
        when(users.getUser(9L)).thenReturn(CommonResult.success(null), CommonResult.success(user(9, 10, 1)),
                CommonResult.success(user(10, 10, 0)), CommonResult.success(user(9, 0, 0)));
        for (int index = 0; index < 4; index++)
            assertThatThrownBy(scope::current).isInstanceOf(ServiceException.class);
        verify(users, never()).getUserListByDeptIds(anyCollection());
    }

    @Test
    void lookupFailureOrMissingMembershipResponseNeverFallsBackToToken() {
        when(users.getUser(9L)).thenReturn(CommonResult.success(user(9, 10, 0)));
        when(users.getUserListByDeptIds(List.of(10L))).thenReturn(CommonResult.success(null));
        assertThatThrownBy(scope::current).isInstanceOf(ServiceException.class);
        when(users.getUserListByDeptIds(List.of(10L))).thenThrow(new IllegalStateException("用户服务不可用"));
        assertThatThrownBy(scope::current).isInstanceOf(IllegalStateException.class);
    }

    private static AdminUserRespDTO user(long id, long dept, int status) {
        var user = new AdminUserRespDTO(); user.setId(id); user.setDeptId(dept); user.setStatus(status); return user;
    }
}
