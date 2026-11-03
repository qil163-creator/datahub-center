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

/** Real application entry point; not a claim of Hive quality relation-generation support. */
@EnabledIfSystemProperty(named = "datahub.test.hive.url", matches = "jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.hive.driver", matches = ".+")
class MasterDatabaseResolverHiveTest {

    @Test
    void deployedStandaloneDriverIsUsedByGovernanceConnectionAndMetadata() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var api = mock(DatabaseApi.class);
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(91L); dto.setProductType("HIVE"); dto.setSchema("default");
        dto.setJdbcUrl(System.getProperty("datahub.test.hive.url"));
        dto.setUsername("hive"); dto.setPassword(""); dto.setUsageType(1);
        dto.setConfigurationRevision("provider-revision-1");
        when(api.getDatabaseConnection(91L)).thenReturn(success(dto));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class, () -> api)
                .withBean(MasterDatabaseResolver.class)
                .withPropertyValues("yudao.dbswitch.drivers.HIVE.path=" + jar,
                        "yudao.dbswitch.drivers.HIVE.sha256=" + sha256)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var resolver = context.getBean(MasterDatabaseResolver.class);
                    var info = resolver.resolve(91L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
                    try (var pool = resolver.createDataSource(info); var connection = pool.getConnection();
                         var sql = connection.createStatement()) {
                        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("Apache Hive");
                        assertThat(connection.getMetaData().getDriverVersion()).startsWith("4.1.");
                        assertThat(info.getConfigurationRevision()).isNotEqualTo(dto.getConfigurationRevision());
                        sql.setQueryTimeout(15);
                        String schema = "codex_gov_" + UUID.randomUUID().toString().replace("-", "");
                        sql.execute("CREATE DATABASE " + schema);
                        try {
                            sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT, value STRING) STORED AS TEXTFILE");
                            assertThat(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE)
                                    .queryTableList(connection, schema)).anyMatch(t -> "sample".equals(t.getTableName()));
                            var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE);
                            assertThat(database.queryTableColumnMeta(connection, schema, "sample")).hasSize(2)
                                    .anyMatch(column -> column.getFieldType() == java.sql.Types.BIGINT);
                            assertThat(database.getTableDDL(connection, schema, "sample")).containsIgnoringCase("bigint");
                            try (var rows = sql.executeQuery("SELECT 1")) {
                                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                            }
                        } finally { sql.execute("DROP DATABASE " + schema + " CASCADE"); }
                    }
                });
    }
}
