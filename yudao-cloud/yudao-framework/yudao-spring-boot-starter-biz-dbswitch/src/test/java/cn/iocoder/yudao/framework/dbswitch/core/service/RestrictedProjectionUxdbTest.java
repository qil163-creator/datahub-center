package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 优炫差异验证。2.1.0.3：不带引号折叠为小写，双引号按原文，反斜杠随 standard_conforming_strings 变化。
 * 驱动不在模块依赖里，测试从 {@code datahub.test.uxdb.driver} 指向的 {@code com.uxsino.uxdb.Driver} 加载。
 * 口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.uxdb.url", matches = "jdbc:uxdb://127[.]0[.]0[.]1:[0-9]+/uxdb")
@EnabledIfSystemProperty(named = "datahub.test.uxdb.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_UXDB_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionUxdbTest {
    private VerifiedDriverTestHandle loader;
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.uxdb.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.UXDB);
        var datasource = loader.dataSource(System.getProperty("datahub.test.uxdb.url"),
                ProductTypeEnum.UXDB.getDriveClassName(), "uxdb",
                System.getenv("DATAHUB_UXDB_TEST_PASSWORD"), new Properties());
        connection = datasource.getConnection();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            try (var version = sql.executeQuery("SELECT version()")) {
                assertTrue(version.next());
                String banner = version.getString(1);
                assertTrue(banner.contains("UXsinoDB 2.1.0.3"), banner);
            }
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id int, name varchar(64), secret varchar(64))");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try {
            if (connection != null) {
                try (var sql = connection.createStatement()) {
                    if (schema != null) {
                        sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                    }
                }
                connection.close();
            }
        } finally {
            if (loader != null) {
                loader.close();
            }
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderEitherStringMode() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.UXDB, connection, schema, "citizen",
                List.of("SET standard_conforming_strings = on", "SET standard_conforming_strings = off"));
        assertEquals(0, accepted);
    }
}
