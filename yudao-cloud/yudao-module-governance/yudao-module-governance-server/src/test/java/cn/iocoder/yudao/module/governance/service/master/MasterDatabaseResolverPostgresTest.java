package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
class MasterDatabaseResolverPostgresTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void defaultAndPinnedDriversPreserveActualMetadataAndRelationIdentity(boolean pinned) throws Exception {
        var dto = new DatabaseConnectionRespDTO(); dto.setId(92L); dto.setProductType("POSTGRESQL");
        dto.setSchema("public"); dto.setUsageType(1); dto.setConfigurationRevision("provider-pg-1");
        dto.setJdbcUrl(System.getProperty("datahub.test.postgres.url"));
        dto.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); dto.setPassword("");
        var api = mock(DatabaseApi.class); when(api.getDatabaseConnection(92L)).thenReturn(success(dto));
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class, () -> api).withBean(MasterDatabaseResolver.class);
        if (pinned) {
            Path driver = Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
            runner = runner.withPropertyValues("yudao.dbswitch.drivers.POSTGRESQL.path=" + driver,
                    "yudao.dbswitch.drivers.POSTGRESQL.sha256=" + DigestUtil.sha256Hex(Files.readAllBytes(driver)));
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var resolver = context.getBean(MasterDatabaseResolver.class);
            var info = resolver.resolve(92L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
            if (pinned) { assertThat(info.getConfigurationRevision()).startsWith("jdbc-v1:"); }
            else { assertThat(info.getConfigurationRevision()).isEqualTo(dto.getConfigurationRevision()); }
            try (var pool = resolver.createDataSource(info); var connection = pool.getConnection();
                 var sql = connection.createStatement()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
                String schema = "codex_driver_" + UUID.randomUUID().toString().replace("-", "");
                sql.setQueryTimeout(15); sql.execute("CREATE SCHEMA " + schema);
                try {
                    sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                    var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
                    assertThat(database.queryTableList(connection, schema)).anyMatch(t -> "sample".equals(t.getTableName()));
                    assertThat(database.queryTableColumnMeta(connection, schema, "sample")).hasSize(1);
                    String before = database.queryRelationFingerprint(connection, schema + ".sample");
                    assertThat(before).hasSize(64);
                    // Current PG identity binds table OID, not relfilenode; verify same-name object replacement.
                    sql.execute("DROP TABLE " + schema + ".sample");
                    sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT)");
                    assertThat(database.queryRelationFingerprint(connection, schema + ".sample")).isNotEqualTo(before);
                } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        });
    }
}
