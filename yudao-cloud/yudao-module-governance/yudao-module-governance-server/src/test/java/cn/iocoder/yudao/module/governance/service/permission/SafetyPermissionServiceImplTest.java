package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.dto.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SafetyPermissionServiceImplTest {
    SafetyPermissionTestFixture fixture;
    SafetyPermissionServiceImpl service;
    MetaDbMapper metaDb;
    MetaTableMapper metaTables;
    @BeforeEach void setup() {
        fixture = new SafetyPermissionTestFixture();
        service = new SafetyPermissionServiceImpl();
        metaDb = mock(MetaDbMapper.class);
        metaTables = mock(MetaTableMapper.class);
        ReflectionTestUtils.setField(service, "databaseApi", fixture.database);
        ReflectionTestUtils.setField(service, "bindingResolver", fixture.resolver);
        ReflectionTestUtils.setField(service, "metaDbMapper", metaDb);
        ReflectionTestUtils.setField(service, "metaTableMapper", metaTables);
        ReflectionTestUtils.setField(service, "metaColumnMapper", mock(MetaColumnMapper.class));
        ReflectionTestUtils.setField(service, "tablePermissionMapper", fixture.tables);
        ReflectionTestUtils.setField(service, "fieldPermissionMapper", fixture.fields);
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void liveFailureDoesNotFallbackToStaleBareMetadata() {
        when(fixture.database.getQueryTableList(10L)).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> service.getTableList(10L));
        verifyNoInteractions(metaDb, metaTables);
    }
    @Test void emptyExactListRemainsEmpty() {
        when(fixture.database.getQueryTableList(10L)).thenReturn(CommonResult.success(List.of()));
        assertTrue(service.getTableList(10L).isEmpty());
        verifyNoInteractions(metaTables);
    }
    @Test void sameTableInTwoSchemasRemainsTwoObjects() {
        var first = fixture.database.resolveQueryTable(10L, "public.orders").getCheckedData();
        var second = fixture.database.resolveQueryTable(10L, "\"Other\".orders").getCheckedData();
        when(fixture.database.getQueryTableList(10L)).thenReturn(CommonResult.success(List.of(first, second)));
        var result = service.getTableList(10L);
        assertEquals(List.of("public", "Other"), result.stream().map(item -> item.getSchemaName()).toList());
        assertNotEquals(result.get(0).getTableReference(), result.get(1).getTableReference());
    }
    @Test void columnsUseExactPhysicalBinding() {
        var result = service.getColumnList(10L, "Other", "orders", "\"Other\".orders");
        assertEquals("id", result.getFirst().getFieldName());
        verify(fixture.database).getQueryTableColumns(argThat(req ->
                "Other".equals(req.getSchemaName()) && "orders".equals(req.getTableName())));
        verify(fixture.database, never()).getTableColumnList(any(), any());
    }
    @Test void oldConfigAndColumnSignaturesRejectInsteadOfGuessingSchema() {
        assertThrows(ServiceException.class, () -> service.getConfig(10L, "orders", 20L));
        assertThrows(ServiceException.class, () -> service.getColumnList(10L, "orders"));
    }
}
