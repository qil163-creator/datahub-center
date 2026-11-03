package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static org.mockito.Mockito.*;

/** Real cached JAR as a local artifact fixture, without opening a database connection. */
public final class ConfiguredDriverTestSupport {
    private ConfiguredDriverTestSupport() { }

    public static Path copyDriver(Path directory, String name) throws Exception {
        Path original = Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.copy(original, directory.resolve(name));
    }

    public static MasterDatabaseResolver resolver(Path jar, String providerRevision) throws Exception {
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString());
        config.setSha256(DigestUtil.sha256Hex(Files.readAllBytes(jar)));
        var registry = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, config));
        var dto = new DatabaseConnectionRespDTO(); dto.setId(4L); dto.setProductType("POSTGRESQL");
        dto.setSchema("public"); dto.setUsageType(1); dto.setConfigurationRevision(providerRevision);
        dto.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/synthetic_not_connected");
        var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        var resolver = new MasterDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);
        ReflectionTestUtils.setField(resolver, "configuredJdbcDrivers", registry);
        return resolver;
    }
}
