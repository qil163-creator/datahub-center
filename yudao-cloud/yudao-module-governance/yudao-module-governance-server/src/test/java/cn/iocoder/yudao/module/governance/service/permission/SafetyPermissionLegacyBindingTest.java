package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionLegacyBindReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.permission.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SafetyPermissionLegacyBindingTest {
    SafetyPermissionTestFixture f;
    SafetyPermissionServiceImpl config;
    MockedStatic<SecurityFrameworkUtils> security;
    @BeforeEach void setup() {
        f = new SafetyPermissionTestFixture();
        config = new SafetyPermissionServiceImpl();
        ReflectionTestUtils.setField(config, "bindingResolver", f.resolver);
        ReflectionTestUtils.setField(config, "tablePermissionMapper", f.tables);
        ReflectionTestUtils.setField(config, "fieldPermissionMapper", f.fields);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
    }
    @AfterEach void cleanup() { security.close(); TenantContextHolder.clear(); }

    @Test void preflightRejectsUnboundTableConfiguration() {
        when(f.tables.countActiveUnbound(10L)).thenReturn(1L);
        assertThrows(ServiceException.class, () -> f.service.requireBoundPolicies(10L));
        verifyNoInteractions(f.database);
    }
    @Test void preflightRejectsUnboundFieldConfiguration() {
        when(f.fields.countActiveUnbound(10L)).thenReturn(1L);
        assertThrows(ServiceException.class, () -> f.service.requireBoundPolicies(10L));
    }
    @Test void verificationNoteAndActorAreRequired() {
        var req = request();
        req.setNote(" ");
        assertThrows(ServiceException.class, () -> config.bindLegacy(req));
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
        assertThrows(ServiceException.class, () -> config.bindLegacy(request()));
        verify(f.tables, never()).selectLegacyForUpdate(any(), any(), any());
    }
    @Test void bindingCannotOverwriteAnExistingExactPolicy() {
        when(f.tables.selectLegacyForUpdate(10L, "orders", 20L)).thenReturn(List.of(
                SafetyTablePermissionDO.builder().id(1L).databaseId(10L).tableName("orders").roleId(20L).permissionType(0).build()));
        when(f.tables.selectByTableRole(10L, "public", "orders", 20L))
                .thenReturn(SafetyTablePermissionDO.builder().id(2L).build());
        assertThrows(ServiceException.class, () -> config.bindLegacy(request()));
        verify(f.tables, never()).update(any(), any());
    }
    @Test void staleOrCaseChangedLegacyFieldCannotBeGuessed() {
        when(f.fields.selectLegacyForUpdate(10L, "orders", 20L)).thenReturn(List.of(
                SafetyFieldPermissionDO.builder().id(2L).fieldName("Mobile").build()));
        assertThrows(ServiceException.class, () -> config.bindLegacy(request()));
        verify(f.fields, never()).update(any(), any());
    }
    @Test void qualifiedReferenceMustNotFallBackToBareName() {
        assertThrows(ServiceException.class, () -> f.resolver.table(10L, "orders", "public", "orders"));
        verify(f.database, never()).resolveQueryTable(any(), any());
    }

    private static SafetyPermissionLegacyBindReqVO request() {
        var req = new SafetyPermissionLegacyBindReqVO();
        req.setDatabaseId(10L); req.setRoleId(20L); req.setLegacyTableName("orders");
        req.setSchemaName("public"); req.setTableName("orders"); req.setTableReference("public.orders");
        req.setNote("已核对物理表和字段");
        return req;
    }
}
