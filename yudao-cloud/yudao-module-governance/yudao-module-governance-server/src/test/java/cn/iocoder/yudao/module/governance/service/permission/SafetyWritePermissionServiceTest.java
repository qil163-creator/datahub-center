package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.permission.dto.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.permission.SafetyTablePermissionDO;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SafetyWritePermissionServiceTest {
    SafetyPermissionTestFixture f;
    SafetyWritePermissionService service;
    @BeforeEach void setup() {
        f = new SafetyPermissionTestFixture(); service = new SafetyWritePermissionService();
        ReflectionTestUtils.setField(service, "bindingResolver", f.resolver);
        ReflectionTestUtils.setField(service, "sqlPermissionService", f.service);
        ReflectionTestUtils.setField(service, "tablePermissionMapper", f.tables);
        when(f.database.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of(
                new DatabaseColumnRespDTO("id", "int8"), new DatabaseColumnRespDTO("district_code", "varchar"),
                new DatabaseColumnRespDTO("status", "varchar"))));
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void legacyReadWriteEnumDoesNotAuthorizeAnyMutation() {
        var table = table(); table.setWritePolicy(null); configure(table);
        assertThrows(ServiceException.class, () -> preflight());
    }
    @Test void explicitWriteGrantWorksWithoutInferringItFromReadEnum() {
        var table = table(); table.setPermissionType(1); configure(table);
        var result = preflight();
        assertEquals("UPDATE", result.getOperation()); assertEquals(2, result.getParameterCount());
        assertEquals("r1", result.getConfigurationRevision());
        assertTrue(result.getSql().contains("\"district_code\" = '370102'"));
        assertTrue(result.getSql().contains("\"public\".\"orders\""));
    }
    @Test void explicitDenyWinsOverIndependentWriteGrant() {
        var table = table(); table.setPermissionType(0); configure(table);
        assertThrows(ServiceException.class, () -> preflight());
    }
    @Test void preflightRequiresExplicitPositiveRole() {
        configure(table());
        assertThrows(ServiceException.class, () -> service.preflight(10L, request().getSql(), request().getParameterValues(), null));
        assertThrows(ServiceException.class, () -> service.preflight(10L, request().getSql(), request().getParameterValues(), 0L));
    }
    @Test void configurationRevisionMustMatchAndStayCurrent() {
        configure(table());
        when(f.database.getDatabaseConnection(10L)).thenReturn(CommonResult.success(SafetyPermissionTestFixture.route("r1")),
                CommonResult.success(SafetyPermissionTestFixture.route("r2")));
        assertThrows(ServiceException.class, () -> preflight());
    }
    @Test void preflightIsNotACachedGrantAndRevocationIsEffectiveOnNextAuthorization() {
        configure(table());
        assertNotNull(service.preflight(10L, request().getSql(), request().getParameterValues(), 20L));
        when(f.tables.selectListByTableRole(10L, "public", "orders", List.of(20L))).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> preflight());
    }
    @Test void mismatchedPhysicalPolicyAndUnboundHistoryFailClosed() {
        var table = table(); table.setSchemaName("other"); configure(table);
        assertThrows(ServiceException.class, () -> preflight());
        configure(table()); when(f.tables.countActiveUnbound(10L)).thenReturn(1L);
        assertThrows(ServiceException.class, () -> preflight());
    }
    @Test void scopeUpdateAndUnapprovedColumnsAreDenied() {
        configure(table()); var req = request();
        req.setSql("UPDATE orders SET district_code=? WHERE id=?");
        assertThrows(ServiceException.class, () -> service.preflight(10L, req.getSql(), req.getParameterValues(), 20L));
        req.setSql("DELETE FROM orders WHERE district_code=?"); req.setParameterValues(List.of("370102"));
        assertThrows(ServiceException.class, () -> service.preflight(10L, req.getSql(), req.getParameterValues(), 20L));
    }
    private SafetyWritePermissionRespDTO preflight() {
        var request = request();
        return service.preflight(10L, request.getSql(), request.getParameterValues(), 20L);
    }

    static SafetyWritePolicyDTO policy() {
        var policy = new SafetyWritePolicyDTO(); policy.setOperations(List.of("UPDATE", "INSERT", "DELETE"));
        policy.setWriteColumns(List.of("id", "status", "district_code")); policy.setFilterColumns(List.of("id"));
        policy.setRowScope(Map.of("district_code", "370102")); policy.setUnrestrictedRows(false); return policy;
    }
    static cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyWritePreflightReqVO request() {
        var request = new cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyWritePreflightReqVO(); request.setDatabaseId(10L); request.setRoleId(20L);
        request.setSql("UPDATE orders SET status=? WHERE id=?");
        request.setParameterValues(List.of("DONE", "9007199254740993")); return request;
    }
    static SafetyTablePermissionDO table() {
        return SafetyTablePermissionDO.builder().id(1L).databaseId(10L).schemaName("public").tableName("orders")
                .roleId(20L).status(0).permissionType(2).writePolicy(JsonUtils.toJsonString(policy())).build();
    }
    void configure(SafetyTablePermissionDO table) {
        when(f.tables.selectListByTableRole(10L, "public", "orders", List.of(20L))).thenReturn(List.of(table));
    }
}
