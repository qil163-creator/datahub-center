package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Connectivity/metadata smoke test only. Does not claim Hive relation-generation or quality compatibility. */
@EnabledIfSystemProperty(named = "datahub.test.hive.url", matches = "jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.hive.driver", matches = ".+")
class HiveIsolatedDriverHiveTest {
    @Test
    void explicitlySuppliedStandaloneDriverUsesExistingIsolatedComponent() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        try (var loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE)) {
            var datasource = loader.dataSource(System.getProperty("datahub.test.hive.url"),
                    ProductTypeEnum.HIVE.getDriveClassName(), "hive", "", new Properties());
            try (var connection = datasource.getConnection(); var sql = connection.createStatement()) {
                assertEquals("Apache Hive", connection.getMetaData().getDatabaseProductName());
                assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("4.1."));
                sql.setQueryTimeout(15);
                String schema = "codex_hive_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("CREATE DATABASE " + schema);
                try {
                    sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT, value STRING) STORED AS TEXTFILE");
                    var tables = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE).queryTableList(connection, schema);
                    assertTrue(tables.stream().anyMatch(table -> "sample".equals(table.getTableName())));
                    try (var rows = sql.executeQuery("SELECT 1")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
                } finally { sql.execute("DROP DATABASE " + schema + " CASCADE"); }
            }
        }
    }
}
