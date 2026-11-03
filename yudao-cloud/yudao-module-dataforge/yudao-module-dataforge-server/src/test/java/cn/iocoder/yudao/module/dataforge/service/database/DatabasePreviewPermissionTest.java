package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.DatabaseController;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabasePreviewReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DatabasePreviewPermissionTest {
    private final DatabaseServiceImpl service = new DatabaseServiceImpl();
    private final DatabaseApi api = mock(DatabaseApi.class);
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService access =
            mock(cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "databaseApi", api);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        when(databases.selectById(1L)).thenReturn(DatabaseDO.builder().id(1L).status(0).build());
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    private DatabasePreviewReqVO request() {
        var req = new DatabasePreviewReqVO(); req.setDatabaseId(1L); req.setTableName("citizen"); req.setLimit(20); return req;
    }
    @Test void retainsActiveActorAndDedicatedPreviewPermission() {
        when(api.previewTable(any())).thenAnswer(inv -> {
            DatabasePreviewReqDTO req = inv.getArgument(0);
            assertEquals(20, req.getLimit());
            var response = new DatabasePreviewRespDTO();
            response.setColumns(List.of(new DatabasePreviewRespDTO.Column("identity_no", "identity_no", "varchar")));
            response.setRows(List.of(Map.of("identity_no", "synthetic-value")));
            return CommonResult.success(response);
        });
        assertEquals("synthetic-value", service.previewTable(request()).getRows().getFirst().get("identity_no"));
        verify(access).requireUserPermission(7L, "dataforge:database:preview");
    }
    @Test void revokedPreviewPermissionStopsBeforeQuery() {
        doThrow(new SecurityException("revoked")).when(access).requireUserPermission(7L, "dataforge:database:preview");
        assertThrows(SecurityException.class, () -> service.previewTable(request()));
        verifyNoInteractions(api, databases);
    }
    @Test void rejectionDoesNotFallbackToRawDatabaseRead() {
        when(api.previewTable(any())).thenThrow(new ServiceException(400, "denied"));
        assertThrows(ServiceException.class, () -> service.previewTable(request()));
        verify(api).previewTable(any());
    }
    @Test void anonymousAndDisabledSourceNeverReachQueryApi() {
        databases.selectById(1L).setStatus(1);
        assertThrows(ServiceException.class, () -> service.previewTable(request()));
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.previewTable(request()));
        verifyNoInteractions(api);
    }
    @Test void previewRequiresDedicatedPermission() throws Exception {
        assertEquals("@ss.hasPermission('dataforge:database:preview')", DatabaseController.class
                .getMethod("previewTable", DatabasePreviewReqVO.class).getAnnotation(PreAuthorize.class).value());
    }
}
