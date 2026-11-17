package cn.iocoder.yudao.framework.security.core.service;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import lombok.AllArgsConstructor;

import java.util.Arrays;

import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.skipPermissionCheck;

/**
 * 默认的 {@link SecurityFrameworkService} 实现类
 *
 * @author 芋道源码
 */
@AllArgsConstructor
public class SecurityFrameworkServiceImpl implements SecurityFrameworkService {

    // System 负责授权关系缓存及失效；业务服务每次鉴权都读取其当前判断。
    private final PermissionCommonApi permissionApi;

    @Override
    public boolean hasPermission(String permission) {
        return hasAnyPermissions(permission);
    }

    @Override
    public boolean hasAnyPermissions(String... permissions) {
        // 特殊：跨租户访问
        if (skipPermissionCheck()) {
            return true;
        }

        // 权限校验
        Long userId = getLoginUserId();
        if (userId == null) {
            return false;
        }
        return permissionApi.hasAnyPermissions(userId, permissions).getCheckedData();
    }

    @Override
    public boolean hasRole(String role) {
        return hasAnyRoles(role);
    }

    @Override
    public boolean hasAnyRoles(String... roles) {
        // 特殊：跨租户访问
        if (skipPermissionCheck()) {
            return true;
        }

        // 权限校验
        Long userId = getLoginUserId();
        if (userId == null) {
            return false;
        }
        return permissionApi.hasAnyRoles(userId, roles).getCheckedData();
    }

    @Override
    public boolean hasScope(String scope) {
        return hasAnyScopes(scope);
    }

    @Override
    public boolean hasAnyScopes(String... scope) {
        // 特殊：跨租户访问
        if (skipPermissionCheck()) {
            return true;
        }

        // 权限校验
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        if (user == null) {
            return false;
        }
        return CollUtil.containsAny(user.getScopes(), Arrays.asList(scope));
    }

}
