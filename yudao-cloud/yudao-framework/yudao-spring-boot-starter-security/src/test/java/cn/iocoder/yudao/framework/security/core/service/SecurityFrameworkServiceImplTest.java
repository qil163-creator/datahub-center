package cn.iocoder.yudao.framework.security.core.service;

import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityFrameworkServiceImplTest {

    private final MutablePermissionApi permissionApi = new MutablePermissionApi();
    private final SecurityFrameworkService service = new SecurityFrameworkServiceImpl(permissionApi);

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void grantAndRevocationTakeEffectOnTheNextPermissionAndRoleCheck() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L), new MockHttpServletRequest());
        assertFalse(service.hasAnyPermissions("query", "edit"));
        assertFalse(service.hasRole("writer"));
        permissionApi.granted = true;
        assertTrue(service.hasAnyPermissions("query", "edit"));
        assertTrue(service.hasRole("writer"));
        permissionApi.granted = false;
        assertFalse(service.hasAnyPermissions("query", "edit"));
        assertFalse(service.hasRole("writer"));
    }

    @Test
    void authorityFailureNeverReusesAnEarlierGrant() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L), new MockHttpServletRequest());
        permissionApi.granted = true;
        assertTrue(service.hasPermission("edit"));
        permissionApi.unavailable = true;
        assertThrows(IllegalStateException.class, () -> service.hasPermission("edit"));
    }

    @Test
    void anonymousAndCrossTenantVisitKeepTheirExistingBehavior() {
        assertFalse(service.hasPermission("edit"));
        assertFalse(service.hasRole("writer"));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L).setTenantId(1L).setVisitTenantId(2L),
                new MockHttpServletRequest());
        assertTrue(service.hasPermission("edit"));
        assertTrue(service.hasRole("writer"));
        assertEquals(0, permissionApi.checks);
    }

    private static final class MutablePermissionApi implements PermissionCommonApi {
        private boolean granted;
        private boolean unavailable;
        private int checks;

        @Override
        public CommonResult<Boolean> hasAnyPermissions(Long userId, String... permissions) {
            return check(userId);
        }

        @Override
        public CommonResult<Boolean> hasAnyRoles(Long userId, String... roles) {
            return check(userId);
        }

        private CommonResult<Boolean> check(Long userId) {
            assertEquals(42L, userId);
            checks++;
            if (unavailable) {
                throw new IllegalStateException("权限服务不可用");
            }
            return CommonResult.success(granted);
        }

        @Override
        public CommonResult<DeptDataPermissionRespDTO> getDeptDataPermission(Long userId) {
            throw new UnsupportedOperationException();
        }
    }
}
