package cn.iocoder.yudao.module.system.controller.admin.auth;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.MenuDO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.permission.MenuMapper;
import cn.iocoder.yudao.module.system.service.permission.MenuServiceImpl;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 验证根目录菜单经真实禁用过滤和登录响应转换后，可以独立下发给资产消费者。 */
class AuthMyAssetMenuContractTest extends BaseMockitoUnitTest {
    private static final String QUERY = "asset:market:my-assets:query";
    @Mock private AdminUserService userService;
    @Mock private RoleService roleService;
    @Mock private PermissionService permissionService;
    @Mock private MenuMapper menuMapper;
    private AuthController controller;
    private MenuDO assetRoot;
    private MenuDO myAssets;
    private RoleDO consumerRole;
    private Set<Long> grantedMenuIds;

    @BeforeEach
    void prepareConsumerWithOnlyRootAndMyAssets() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser().setId(900L), null, List.of()));
        assetRoot = menu(100L, 0L, 1, "/asset", "", null, null, 10);
        myAssets = menu(101L, 100L, 2, "my-assets", QUERY,
                "asset/market/my-assets/index", "AssetMarketMyAssets", 90);
        consumerRole = new RoleDO().setId(500L).setCode("asset_consumer_fixture").setStatus(0);
        grantedMenuIds = Set.of(100L, 101L);
        when(userService.getUser(900L)).thenReturn(new AdminUserDO().setId(900L).setNickname("资产消费者夹具"));
        when(permissionService.getUserRoleIdListByUserId(900L)).thenReturn(Set.of(500L));
        when(roleService.getRoleList(anyCollection())).thenAnswer(call -> new ArrayList<>(List.of(consumerRole)));
        when(permissionService.getRoleMenuListByRoleId(anyCollection())).thenAnswer(call ->
                ((Collection<?>) call.getArgument(0)).contains(500L) ? grantedMenuIds : Set.of());
        // 禁用角色会在访问Mapper之前被排除，因此仅该查询替身允许未使用。
        lenient().when(menuMapper.selectByIds(anyCollection())).thenAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            return new ArrayList<>(List.of(assetRoot, myAssets).stream().filter(menu -> ids.contains(menu.getId())).toList());
        });
        var menus = new MenuServiceImpl();
        ReflectionTestUtils.setField(menus, "menuMapper", menuMapper);
        controller = new AuthController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "menuService", menus);
    }

    @Test
    void loginResponseExposesRootChildComponentAndOnlyConsumerPermission() {
        var result = permissionInfo();
        assertThat(result.getPermissions()).contains(QUERY)
                .doesNotContain("asset:market:query", "asset:data-asset:query", "supply:data-api:query");
        assertThat(result.getRoles()).containsExactly("asset_consumer_fixture");
        assertThat(result.getMenus()).singleElement().satisfies(root -> {
            assertThat(root.getPath()).isEqualTo("/asset");
            assertThat(root.getChildren()).singleElement().satisfies(child -> {
                assertThat(child.getParentId()).isEqualTo(root.getId());
                assertThat(child.getPath()).isEqualTo("my-assets");
                assertThat(child.getComponent()).isEqualTo("asset/market/my-assets/index");
                assertThat(child.getComponentName()).isEqualTo("AssetMarketMyAssets");
                assertThat(child.getVisible()).isTrue();
                assertThat(child.getKeepAlive()).isFalse();
            });
        });
    }

    @Test
    void disablingPageOrAncestorRemovesBothConsumerMenuAndPermission() {
        for (MenuDO disabled : List.of(assetRoot, myAssets)) {
            disabled.setStatus(1);
            var result = permissionInfo();
            assertThat(result.getPermissions()).doesNotContain(QUERY);
            assertThat(result.getMenus()).allSatisfy(root -> assertThat(root.getChildren()).isNullOrEmpty());
            disabled.setStatus(0);
        }
    }

    @Test
    void grantingOnlyLeafCannotCreateAnOrphanMenuOrPermission() {
        grantedMenuIds = Set.of(101L);
        var result = permissionInfo();
        assertThat(result.getMenus()).isEmpty();
        assertThat(result.getPermissions()).doesNotContain(QUERY);
    }

    @Test
    void disabledConsumerRoleCannotExposeTheMenuOrPermission() {
        consumerRole.setStatus(1);
        var result = permissionInfo();
        assertThat(result.getMenus()).isEmpty();
        assertThat(result.getPermissions()).doesNotContain(QUERY);
        assertThat(result.getRoles()).isEmpty();
    }

    private AuthPermissionInfoRespVO permissionInfo() {
        return controller.getPermissionInfo().getCheckedData();
    }

    private static MenuDO menu(long id, long parentId, int type, String path, String permission,
                               String component, String componentName, int sort) {
        return new MenuDO().setId(id).setParentId(parentId).setType(type).setPath(path).setPermission(permission)
                .setComponent(component).setComponentName(componentName).setSort(sort).setName(type == 1 ? "数据资产" : "我的资产")
                .setStatus(0).setVisible(true).setKeepAlive(false).setAlwaysShow(true);
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }
}
