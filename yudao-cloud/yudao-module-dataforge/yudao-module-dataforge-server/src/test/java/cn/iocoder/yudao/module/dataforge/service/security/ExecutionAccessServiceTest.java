package cn.iocoder.yudao.module.dataforge.service.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionAccessServiceTest {

    private final ExecutionAccessService service = new ExecutionAccessService();
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final AdminUserApi users = mock(AdminUserApi.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        when(users.validateUserList(any())).thenReturn(CommonResult.success(true));
    }

    @Test
    void missingExecutionIdentityFailsClosedWithoutRemoteCalls() {
        assertThrows(ServiceException.class,
                () -> service.requireUserPermission(null, ExecutionAccessService.PERMISSION_DEV_TASK_RUN));
        assertThrows(ServiceException.class, () -> service.requireUserPermission(7L));
        verifyNoInteractions(users, permissions);
    }

    @Test
    void identityWithoutPlatformPermissionIsRejected() {
        when(permissions.hasAnyPermissions(eq(7L), any(String[].class))).thenReturn(CommonResult.success(false));
        assertThrows(ServiceException.class,
                () -> service.requireUserPermission(7L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN));
    }

    @Test
    void identityWithAnyRequestedPermissionIsAccepted() {
        when(permissions.hasAnyPermissions(eq(7L), any(String[].class))).thenReturn(CommonResult.success(true));
        assertDoesNotThrow(() -> service.requireUserPermission(7L,
                ExecutionAccessService.PERMISSION_ETL_RUN, ExecutionAccessService.PERMISSION_ETL_RELEASE));
    }

    @Test
    void disabledOrMissingDatabaseCannotBeUsed() {
        assertThrows(ServiceException.class, () -> service.requireDatabase(null));
        when(databases.selectById(20L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.requireDatabase(20L));
        when(databases.selectById(21L)).thenReturn(DatabaseDO.builder().id(21L).status(1).build());
        assertThrows(ServiceException.class, () -> service.requireDatabase(21L));
        when(databases.selectById(22L)).thenReturn(DatabaseDO.builder().id(22L).status(0).build());
        assertDoesNotThrow(() -> service.requireDatabase(22L));
    }

}
