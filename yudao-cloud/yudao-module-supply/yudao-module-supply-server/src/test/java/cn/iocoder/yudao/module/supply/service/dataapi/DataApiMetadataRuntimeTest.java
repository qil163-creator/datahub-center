package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataApiMetadataRuntimeTest {
    @Test void bothColumnsAndKeysUseTheProductDefaultDriver() {
        var fixture = new Fixture();
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(fixture.runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        when(metadata.queryTableColumnMeta("jdbc:test", "test", "", "public", "example")).thenReturn(List.of());
        when(metadata.queryTablePrimaryKeys("jdbc:test", "test", "", "public", "example")).thenReturn(List.of());
        assertTrue(fixture.service.getTableColumns(1L, "example").isEmpty());
        verify(fixture.runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL);
        verify(fixture.runtime, never()).jdbcMetadata(any(), any());
        verify(metadata).queryTableColumnMeta("jdbc:test", "test", "", "public", "example");
        verify(metadata).queryTablePrimaryKeys("jdbc:test", "test", "", "public", "example");
    }

    @Test void defaultMetadataResolutionFailurePropagates() {
        var fixture = new Fixture();
        when(fixture.runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL))
                .thenThrow(new IllegalArgumentException("Default driver unavailable"));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.getTableColumns(1L, "example"));
        verify(fixture.runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL);
        verifyNoMoreInteractions(fixture.runtime);
    }

    private static final class Fixture {
        final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
        final DataApiServiceImpl service = new DataApiServiceImpl();
        Fixture() {
            var resolver = mock(SupplyDatabaseResolver.class);
            var conn = new SupplyDatabaseResolver.ConnectionInfo(); conn.setDatabaseId(1L);
            conn.setProductType(ProductTypeEnum.POSTGRESQL);
            conn.setJdbcUrl("jdbc:test"); conn.setUsername("test"); conn.setPassword(""); conn.setSchema("public");
            when(resolver.resolve(1L)).thenReturn(conn);
            ReflectionTestUtils.setField(service, "databaseResolver", resolver);
            ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        }
    }
}
