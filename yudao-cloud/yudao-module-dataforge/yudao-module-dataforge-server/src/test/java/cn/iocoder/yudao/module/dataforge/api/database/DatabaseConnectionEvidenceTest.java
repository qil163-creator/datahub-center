package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseConnectionEvidenceTest {
    @Test void providerReturnsRevisionFromSameResolvedDatabaseRow() {
        var resolver = spy(new AccessDatabaseResolver());
        doReturn(ProductTypeEnum.POSTGRESQL).when(resolver).resolveProductType(any());
        var database = new DatabaseDO();
        database.setId(4L); database.setUsageType(1); database.setDatabaseSchema("public");
        database.setUpdateTime(LocalDateTime.of(2026, 9, 23, 10, 0, 0, 123456000));
        var info = resolver.resolve(database);
        var api = new DatabaseApiImpl();
        var providerResolver = mock(AccessDatabaseResolver.class);
        when(providerResolver.resolve(eq(4L), any())).thenReturn(info);
        ReflectionTestUtils.setField(api, "databaseResolver", providerResolver);
        var response = api.getDatabaseConnection(4L).getCheckedData();
        assertThat(response.getConfigurationRevision()).isEqualTo("2026-09-23T10:00:00.123456");
        assertThat(response.getUsageType()).isEqualTo(1);
        database.setUpdateTime(null);
        assertThat(resolver.resolve(database).getConfigurationRevision()).isNull();
    }
}
