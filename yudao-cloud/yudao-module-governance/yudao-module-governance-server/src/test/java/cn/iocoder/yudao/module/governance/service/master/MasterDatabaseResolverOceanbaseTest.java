package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real application entry point; not a claim of OceanBase quality relation-generation support. */
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.driver", matches = ".+")
class MasterDatabaseResolverOceanbaseTest {

    @Test
    void deployedStandaloneDriverIsUsedByGovernanceConnectionAndMetadata() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.oceanbase.driver")).toRealPath();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var api = mock(DatabaseApi.class);
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(91L); dto.setProductType("OCEANBASE"); dto.setSchema("oceanbase");
        dto.setJdbcUrl(System.getProperty("datahub.test.oceanbase.url"));
        dto.setUsername("root@test"); dto.setPassword(""); dto.setUsageType(1);
        dto.setConfigurationRevision("provider-revision-1");
        when(api.getDatabaseConnection(91L)).thenReturn(success(dto));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class, () -> api)
                .withBean(MasterDatabaseResolver.class)
                .withPropertyValues("yudao.dbswitch.drivers.OCEANBASE.path=" + jar,
                        "yudao.dbswitch.drivers.OCEANBASE.sha256=" + sha256)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var resolver = context.getBean(MasterDatabaseResolver.class);
                    var info = resolver.resolve(91L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
                    try (var pool = resolver.createDataSource(info); var connection = pool.getConnection();
                         var sql = connection.createStatement()) {
                        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("OceanBase");
                        assertThat(connection.getMetaData().getDriverVersion()).startsWith("2.4.12");
                        assertThat(info.getConfigurationRevision()).isNotEqualTo(dto.getConfigurationRevision());
                        sql.setQueryTimeout(15);
                        String schema = "codex_gov_" + UUID.randomUUID().toString().replace("-", "");
                        sql.execute("CREATE DATABASE " + schema);
                        try {
                            sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY, value VARCHAR(40))");
                            assertThat(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OCEANBASE)
                                    .queryTableList(connection, schema)).anyMatch(t -> "sample".equals(t.getTableName()));
                            var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OCEANBASE);
                            assertThat(database.queryTableColumnMeta(connection, schema, "sample")).hasSize(2)
                                    .anyMatch(column -> column.getFieldType() == java.sql.Types.BIGINT);
                            assertThat(database.getTableDDL(connection, schema, "sample")).containsIgnoringCase("bigint");
                            try (var rows = sql.executeQuery("SELECT 1")) {
                                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                            }
                        } finally { sql.execute("DROP DATABASE " + schema); }
                    }
                });
    }
}
