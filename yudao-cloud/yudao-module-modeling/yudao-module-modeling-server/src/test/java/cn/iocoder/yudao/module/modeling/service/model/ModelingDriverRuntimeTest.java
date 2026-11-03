package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelingDriverRuntimeTest {
    @Test void reverseModelingUsesTheProductDefaultDriver() {
        var api = mock(DatabaseApi.class);
        var runtime = mock(DbSwitchRuntime.class);
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        var dto = new DatabaseConnectionRespDTO(); dto.setId(11L); dto.setProductType("postgres");
        dto.setJdbcUrl("jdbc:postgresql://fixture.invalid/test");
        dto.setUsername("fixture"); dto.setPassword(""); dto.setSchema("public");
        when(api.getDatabaseConnection(11L)).thenReturn(CommonResult.success(dto));
        when(metadata.queryTableSchema(dto.getJdbcUrl(), "fixture", "", "public", "orders", 30_000))
                .thenReturn(new TableSchemaMetadata(List.of(), List.of()));
        var resolver = new ModelingDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        ReflectionTestUtils.setField(resolver, "dbSwitchRuntime", runtime);
        assertTrue(resolver.importFields(11L, "orders").isEmpty());
        verify(runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL);
        verify(metadata).queryTableSchema(dto.getJdbcUrl(), "fixture", "", "public", "orders", 30_000);
    }
}
