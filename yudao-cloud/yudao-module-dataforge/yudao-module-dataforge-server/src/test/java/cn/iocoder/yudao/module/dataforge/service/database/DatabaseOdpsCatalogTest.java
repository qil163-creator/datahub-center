package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.CatalogMetadata;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DatabaseOdpsCatalogTest {
    private final DatabaseServiceImpl service = new DatabaseServiceImpl();
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final DatabaseTypeMapper types = mock(DatabaseTypeMapper.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final CatalogMetadata catalog = mock(CatalogMetadata.class);

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "databaseTypeMapper", types);
        ReflectionTestUtils.setField(service, "accessDatabaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        var database = DatabaseDO.builder().id(7L).databaseType(91).databaseName("fixture_project")
                .userName("fixture-access-id")
                .extendConfig("{\"schemaVersion\":1,\"endpoint\":\"https://odps.fixture.test/api\",\"timeoutSeconds\":5}").build();
        when(databases.selectById(7L)).thenReturn(database);
        when(types.selectById(91L)).thenReturn(DatabaseTypeDO.builder().id(91L).typeCode("ODPS").build());
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(7L); connection.setProductType(ProductTypeEnum.ODPS);
        connection.setUsername("fixture-access-id"); connection.setPassword("fixture-secret");
        when(resolver.resolve(eq(7L), any())).thenReturn(connection);
        when(runtime.catalogMetadata(ProductTypeEnum.ODPS)).thenReturn(catalog);
    }

    @Test void tableDirectoryUsesStoredProjectAndSecretInComponentOnly() {
        when(catalog.tables(any())).thenReturn(List.of(new CatalogMetadata.Table("measurements", null, null)));
        var result = service.getTableList(7L);
        assertEquals("measurements", result.getFirst().getTableName());
        assertNull(result.getFirst().getViewTable());
        var request = ArgumentCaptor.forClass(ConnectionProbeRequest.class);
        verify(catalog).tables(request.capture());
        assertEquals("https://odps.fixture.test/api", request.getValue().endpoint());
        assertEquals("fixture_project", request.getValue().requiredProperty("project"));
        assertEquals("fixture-secret", request.getValue().password());
        assertEquals(Duration.ofSeconds(5), request.getValue().timeout());
        assertFalse(request.getValue().toString().contains("fixture-secret"));
        assertFalse(result.toString().contains("fixture-secret"));
        verify(runtime, never()).jdbcMetadata(any());
    }

    @Test void nativeFactsMapWithoutInventingKeysNullabilityOrLengthsAndZeroScaleSurvives() {
        var columns = List.of(new CatalogMetadata.Column("amount", "DECIMAL(30,0)", "精确值", null, null, 30, 0, false),
                new CatalogMetadata.Column("ds", "STRING", "分区", null, null, null, null, true));
        when(catalog.describe(any(), eq("measurements"))).thenReturn(new CatalogMetadata.Structure(
                new CatalogMetadata.Table("measurements", "测量值", false), columns));
        var result = service.getTableMeta(7L, "measurements");
        assertEquals("fixture_project", result.getSchemaName());
        assertEquals("测量值", result.getRemarks());
        assertNull(result.getCreateSql());
        var amount = result.getColumns().getFirst();
        assertEquals("DECIMAL(30,0)", amount.getFieldTypeName());
        assertEquals(30, amount.getPrecision()); assertEquals(0, amount.getScale());
        assertNull(amount.getLength()); assertNull(amount.getNullable()); assertNull(amount.getPk()); assertNull(amount.getAutoIncrement());
        assertTrue(result.getColumns().get(1).getPartitionKey());
        verify(catalog, never()).tables(any());
        verify(runtime, never()).jdbcMetadata(any());
    }

    @Test void missingOrDeniedDatasourceNeverReachesSdkCatalog() {
        assertThrows(ServiceException.class, () -> service.getTableList(999L));
        when(resolver.resolve(eq(7L), any())).thenThrow(new IllegalStateException("denied"));
        assertThrows(IllegalStateException.class, () -> service.getTableMeta(7L, "measurements"));
        verifyNoInteractions(catalog);
    }

    @Test void vendorFailureCannotLeakCredentialsOrReturnSuccessfulEmptyMetadata() {
        when(catalog.tables(any())).thenThrow(new IllegalStateException("private-body fixture-secret"));
        when(catalog.describe(any(), any())).thenThrow(new IllegalStateException("private-body fixture-secret"));
        var listFailure = assertThrows(ServiceException.class, () -> service.getTableList(7L));
        var metaFailure = assertThrows(ServiceException.class, () -> service.getTableMeta(7L, "measurements"));
        for (var failure : List.of(listFailure, metaFailure)) {
            assertFalse(failure.getMessage().contains("fixture-secret"));
            assertFalse(failure.getMessage().contains("private-body"));
            assertNull(failure.getCause());
        }
    }
}
