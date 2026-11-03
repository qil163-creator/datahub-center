package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.DataAccessController;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessTypeMappingPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccessTypeMappingPermissionTest {
    final DataAccessServiceImpl service = new DataAccessServiceImpl();
    final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);

    @BeforeEach void setup() {
        lenient().when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        when(resolver.resolve(anyLong(), any())).thenAnswer(inv -> {
            assertEquals(33L, TenantContextHolder.getTenantId());
            var info = new AccessDatabaseResolver.ConnectionInfo();
            info.setDatabaseId(inv.getArgument(0)); info.setSchema("main");
            info.setProductType(Long.valueOf(1).equals(inv.getArgument(0)) ? ProductTypeEnum.SQLITE3 : ProductTypeEnum.POSTGRESQL);
            return info;
        });
        TenantContextHolder.setTenantId(33L);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }

    @Test void anonymousAndDisabledReferencesNeverReachMetadata() {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.previewTypeMap(request()));
        verifyNoInteractions(resolver, permissions, access);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        doThrow(new IllegalStateException("disabled")).when(access).requireDatabase(2L);
        assertThrows(RuntimeException.class, () -> service.previewTypeMap(request()));
        verifyNoInteractions(resolver, permissions);
    }

    @Test void sourceAndTargetPermissionDenialsNeverOpenAConnection() throws Exception {
        doThrow(new IllegalStateException("denied")).when(permissions).requireExactTables(1L, ProductTypeEnum.SQLITE3, "main", List.of("source"));
        assertThrows(RuntimeException.class, () -> service.previewTypeMap(request()));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());

    }

    @Test void sqlAndSameProductPreviewsAreRejectedAndControllerRequiresExistingQueryPermission() throws Exception {
        var sql = request(); sql.setSourceType(2); sql.setSourceSql("SELECT 1");
        assertThrows(RuntimeException.class, () -> service.previewTypeMap(sql)); verifyNoInteractions(resolver);
        when(resolver.resolve(eq(2L), any())).thenAnswer(inv -> {
            var info = new AccessDatabaseResolver.ConnectionInfo(); info.setProductType(ProductTypeEnum.SQLITE3); info.setSchema("main"); return info;
        });
        assertThrows(RuntimeException.class, () -> service.previewTypeMap(request()));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
        assertEquals("@ss.hasPermission('dataforge:access:query')", DataAccessController.class.getMethod("previewTypeMap",
                AccessTypeMappingPreviewReqVO.class).getAnnotation(PreAuthorize.class).value());
    }

    static AccessTypeMappingPreviewReqVO request() {
        var req = new AccessTypeMappingPreviewReqVO(); req.setSourceDatabaseId(1L); req.setTargetDatabaseId(2L);
        req.setPreviewTableName("source"); req.setMapperType("ALL"); return req;
    }
}
