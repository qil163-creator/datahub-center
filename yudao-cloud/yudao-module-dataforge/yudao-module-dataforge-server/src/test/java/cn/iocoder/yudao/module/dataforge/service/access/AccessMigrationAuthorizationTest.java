package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 去除治理策略后仍保留任务权限、连接修订及实际目标计划约束。 */
class AccessMigrationAuthorizationTest {
    final AccessMigrationAuthorization authorization = new AccessMigrationAuthorization();
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    final AccessDatabaseResolver.ConnectionInfo route = route();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(authorization, "executionAccess", access);
        ReflectionTestUtils.setField(authorization, "databaseResolver", resolver);
        when(resolver.resolve(eq(10L), any())).thenReturn(route);
    }
    @Test void completeActualPlanRemainsTheExecutionSource() {
        var plan = plan();
        assertSame(plan, authorization.authorizeTarget(route, 7L, "output", List.of("id", "payload"), plan));
        assertDoesNotThrow(() -> authorization.requireSourceBinding(route, 7L, "output"));
        verify(resolver, times(2)).resolve(eq(10L), any());
    }
    @Test void alteredShapeOrParametersCannotPassTechnicalValidation() {
        var original = plan(); var insert = original.steps().getLast();
        var altered = new ManagedSchemaScriptPlan(original.product(), original.schemaName(), List.of(
                original.steps().getFirst(), new ManagedSchemaScriptPlan.Step(insert.sql(), insert.operation(),
                Set.of("output"), List.of(1, 0), null)));
        assertThrows(IllegalStateException.class, () -> authorization.authorizeTarget(route, 7L,
                "output", List.of("id", "payload"), altered));
        assertThrows(IllegalStateException.class, () -> authorization.authorizeTarget(route, 7L,
                "other", List.of("id", "payload"), original));
        assertThrows(IllegalStateException.class, () -> authorization.authorizeTarget(route, 7L,
                "output", List.of("id", "id"), original));
    }
    @Test void invalidScopeAndStaleRouteAreRejected() {
        var changed = route(); changed.setConfigurationRevision("revision-2");
        when(resolver.resolve(eq(10L), any())).thenReturn(changed);
        assertThrows(IllegalStateException.class, () -> authorization.requireSourceBinding(route, 7L, "output"));
        assertThrows(IllegalStateException.class, () -> authorization.authorizeTarget(route, 7L,
                "output", List.of("id", "payload"), plan()));
        route.setConfigurationRevision(null);
        assertThrows(IllegalStateException.class, () -> authorization.requireSourceBinding(route, 7L, "output"));
    }
    @Test void ordinaryRunPermissionIsStillRequired() {
        authorization.requireTargetPermission(7L);
        verify(access).requireUserPermission(7L, "dataforge:access:run");
        doThrow(new SecurityException("revoked")).when(access).requireUserPermission(7L, "dataforge:access:run");
        assertThrows(SecurityException.class, () -> authorization.requireTargetPermission(7L));
    }
    @Test void tableWorkerRestoresItsSecurityContextAfterSuccessAndDenial() {
        var previous = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        doAnswer(invocation -> {
            assertEquals(7L, cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId());
            return null;
        }).when(access).requireUserPermission(7L, "dataforge:access:run");
        try {
            assertEquals(plan(), authorization.authorizeTable(route, route, 7L, "output", "output",
                    List.of("id", "payload"), plan()));
            assertSame(previous, org.springframework.security.core.context.SecurityContextHolder.getContext());
            doThrow(new SecurityException("revoked")).when(access).requireUserPermission(7L, "dataforge:access:run");
            assertThrows(SecurityException.class, () -> authorization.authorizeTable(route, route, 7L,
                    "output", "output", List.of("id", "payload"), plan()));
            assertSame(previous, org.springframework.security.core.context.SecurityContextHolder.getContext());
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    static ManagedSchemaScriptPlan plan() {
        return ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "warehouse", List.of(
                "CREATE TABLE \"warehouse\".\"output\" (\"id\" INTEGER PRIMARY KEY, \"payload\" VARCHAR(64))",
                "INSERT INTO \"warehouse\".\"output\" (\"id\", \"payload\") VALUES (?, ?)"));
    }
    static AccessDatabaseResolver.ConnectionInfo route() {
        var result = new AccessDatabaseResolver.ConnectionInfo(); result.setDatabaseId(10L);
        result.setProductType(ProductTypeEnum.POSTGRESQL); result.setSchema("warehouse");
        result.setConfigurationRevision("revision-1"); return result;
    }
}
