package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 定时准入只使用已验证持久执行身份，任何退出路径均恢复线程上下文。 */
class AccessRunAuthorizationContextTest {
    private final AccessRunAuthorization authorization = new AccessRunAuthorization();
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final DataAccessExecutor worker = mock(DataAccessExecutor.class);
    private final DataAccessDO snapshot = DataAccessDO.builder().engineType(1)
            .sourceDatabaseId(10L).targetDatabaseId(20L).build();

    AccessRunAuthorizationContextTest() {
        ObjectProvider<DataAccessExecutor> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(worker);
        ReflectionTestUtils.setField(authorization, "access", access);
        ReflectionTestUtils.setField(authorization, "executor", provider);
    }

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test void scheduledAdmissionWithoutCallerUsesVerifiedExecutionIdentityAndRestoresEmptyContext() {
        SecurityContextHolder.clearContext();
        var previous = SecurityContextHolder.getContext();
        doAnswer(invocation -> {
            assertNull(SecurityFrameworkUtils.getLoginUserId());
            return null;
        }).when(access).requireUserPermission(7L, "dataforge:access:run");
        expectWorkerIdentity();
        authorization.execution(snapshot, 7L);
        assertSame(previous, SecurityContextHolder.getContext());
        assertNull(SecurityFrameworkUtils.getLoginUserId());
        verify(access).requireDatabase(10L); verify(access).requireDatabase(20L);
        verify(worker).validateExecutionPermission(snapshot);
    }

    @Test void differentCallerCannotReplaceThePersistedExecutionActor() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
        var previous = SecurityContextHolder.getContext();
        expectWorkerIdentity();
        authorization.execution(snapshot, 7L);
        assertSame(previous, SecurityContextHolder.getContext());
        assertEquals(99L, SecurityFrameworkUtils.getLoginUserId());
        assertEquals("7", snapshot.getCreator());
    }

    @Test void sourcePermissionFailureRestoresOriginalContext() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
        var previous = SecurityContextHolder.getContext();
        doAnswer(invocation -> {
            assertEquals(7L, SecurityFrameworkUtils.getLoginUserId());
            throw new SecurityException("denied");
        }).when(worker).validateExecutionPermission(snapshot);
        assertThrows(SecurityException.class, () -> authorization.execution(snapshot, 7L));
        assertSame(previous, SecurityContextHolder.getContext());
        assertEquals(99L, SecurityFrameworkUtils.getLoginUserId());
    }

    @Test void disabledUserOrRevokedRunPermissionCannotInstallAContextOrReachDatasource() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
        var previous = SecurityContextHolder.getContext();
        doThrow(new SecurityException("disabled or revoked")).when(access)
                .requireUserPermission(7L, "dataforge:access:run");
        assertThrows(SecurityException.class, () -> authorization.execution(snapshot, 7L));
        assertSame(previous, SecurityContextHolder.getContext());
        assertEquals(99L, SecurityFrameworkUtils.getLoginUserId());
        verify(access, never()).requireDatabase(any()); verifyNoInteractions(worker);
    }

    @Test void missingExecutionActorIsRejectedWithoutAuthorizationCalls() {
        assertThrows(RuntimeException.class, () -> authorization.execution(snapshot, null));
        assertThrows(RuntimeException.class, () -> authorization.execution(snapshot, 0L));
        verifyNoInteractions(access, worker);
    }

    private void expectWorkerIdentity() {
        doAnswer(invocation -> {
            assertEquals(7L, SecurityFrameworkUtils.getLoginUserId());
            assertEquals("7", snapshot.getCreator());
            return null;
        }).when(worker).validateExecutionPermission(snapshot);
    }
}
