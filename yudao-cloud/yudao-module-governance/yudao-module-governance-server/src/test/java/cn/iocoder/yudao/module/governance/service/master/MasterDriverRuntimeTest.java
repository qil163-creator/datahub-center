package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_DATABASE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MasterDriverRuntimeTest {
    @Test void resolvedPoolsShareTheApplicationDriverBudgetAndOptions() {
        try (var drivers = ConfiguredJdbcDrivers.empty(); var factory = mockStatic(DataSourceUtils.class)) {
            var api = mock(DatabaseApi.class); var dto = connection();
            when(api.getDatabaseConnection(1L)).thenReturn(CommonResult.success(dto));
            var resolver = new MasterDatabaseResolver();
            ReflectionTestUtils.setField(resolver, "databaseApi", api);
            ReflectionTestUtils.setField(resolver, "configuredJdbcDrivers", drivers);
            var options = new DbSwitchOptions(1024, true, false, 20, 0, 10_000, 500, 0);
            ReflectionTestUtils.setField(resolver, "dbSwitchOptions", options);
            var pool = mock(HikariDataSource.class);
            factory.when(() -> DataSourceUtils.createTargetDataSource(any(), isNull(), isNull(), isNull(),
                    same(drivers.runtimes()), same(options))).thenReturn(pool);
            assertSame(pool, resolver.createDataSource(resolver.resolve(1L, MASTER_MODEL_DATABASE_NOT_EXISTS)));
            factory.verify(() -> DataSourceUtils.createTargetDataSource(any(), isNull(), isNull(), isNull(),
                    same(drivers.runtimes()), same(options)));
        }
    }

    @Test void resolvedPoolsAndQueriesUseTheProductDefaultDriverBinding() throws Exception {
        Path artifact = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var profile = new DbSwitchProperties.DriverProfile();
        profile.setProduct(ProductTypeEnum.POSTGRESQL);
        profile.setPath(artifact.toAbsolutePath().toString());
        profile.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        profile.setDefaultForProduct(true);
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(), Map.of("pg-default", profile));
             var factory = mockStatic(DataSourceUtils.class)) {
            var api = mock(DatabaseApi.class); var dto = connection();
            dto.setConfigurationRevision("provider-v1");
            when(api.getDatabaseConnection(1L)).thenReturn(CommonResult.success(dto));
            var resolver = new MasterDatabaseResolver();
            ReflectionTestUtils.setField(resolver, "databaseApi", api);
            ReflectionTestUtils.setField(resolver, "configuredJdbcDrivers", drivers);
            var binding = drivers.find(ProductTypeEnum.POSTGRESQL).orElseThrow();
            var resolved = resolver.resolve(1L, MASTER_MODEL_DATABASE_NOT_EXISTS);
            assertEquals("provider-v1", resolved.getProviderConfigurationRevision());
            assertTrue(resolved.getConfigurationRevision().startsWith("jdbc-v1:"));
            var query = resolver.queryConnectionSpec(resolved);
            assertNull(query.driverProfileId());
            assertEquals(resolved.getConfigurationRevision(), query.configurationGeneration());
            var pool = mock(HikariDataSource.class);
            factory.when(() -> DataSourceUtils.createTargetDataSource(any(), isNull(), isNull(), same(binding),
                    same(drivers.runtimes()), any())).thenReturn(pool);
            assertSame(pool, resolver.createDataSource(resolved));
            factory.verify(() -> DataSourceUtils.createTargetDataSource(any(), isNull(), isNull(), same(binding),
                    same(drivers.runtimes()), any()));
            var unresolved = new MasterDatabaseResolver.ConnectionInfo();
            unresolved.setProductType(ProductTypeEnum.POSTGRESQL);
            unresolved.setJdbcUrl(dto.getJdbcUrl());
            unresolved.setUsername(dto.getUsername());
            assertThrows(IllegalArgumentException.class, () -> resolver.createDataSource(unresolved));
        }
    }

    private DatabaseConnectionRespDTO connection() {
        var dto = new DatabaseConnectionRespDTO(); dto.setId(1L); dto.setProductType("POSTGRESQL");
        dto.setJdbcUrl("jdbc:postgresql://fixture.invalid/test"); dto.setUsername("fixture");
        dto.setPassword(""); dto.setSchema("public"); return dto;
    }
}
