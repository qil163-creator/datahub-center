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
 * #17 神通差异验证。V8.0.251217：不带引号折叠为大写，双引号按原文，反斜杠不是转义。
 * 驱动不在模块依赖里，测试从 {@code datahub.test.oscar.driver} 指向的 {@code com.oscar.Driver} 加载。
 * 口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.oscar.url", matches = "jdbc:oscar://127[.]0[.]0[.]1:[0-9]+/OSRDB")
@EnabledIfSystemProperty(named = "datahub.test.oscar.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OSCAR_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionOscarTest {
    private VerifiedDriverTestHandle loader;
    private Connection connection;
    private String table;

    @BeforeEach
    void create() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.oscar.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        table = "Q17_" + Long.toHexString(System.nanoTime()).toUpperCase();
        loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR);
        var datasource = loader.dataSource(System.getProperty("datahub.test.oscar.url"),
                ProductTypeEnum.OSCAR.getDriveClassName(), "SYSDBA",
                System.getenv("DATAHUB_OSCAR_TEST_PASSWORD"), new Properties());
        connection = datasource.getConnection();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            try (var version = sql.executeQuery("SELECT VERSION()")) {
                assertTrue(version.next());
                assertTrue(version.getString(1).contains("8.0.251217"), version.getString(1));
            }
            sql.execute("CREATE TABLE " + table + "(id INT, name VARCHAR(64), secret VARCHAR(64))");
            sql.execute("INSERT INTO " + table + " VALUES (1,'row','s1')");
            sql.execute("INSERT INTO " + table + " VALUES (2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try {
            if (connection != null) {
                try (var sql = connection.createStatement()) {
                    if (table != null) {
                        sql.execute("DROP TABLE " + table);
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
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.OSCAR, connection, "SYSDBA", table,
                List.of("SELECT 1 FROM DUAL"));
        assertEquals(0, accepted);
    }
}
