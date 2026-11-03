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
 * #17 Sybase ASE 差异验证。16.0 SP02 PL02，排序 bin_iso_1：带引号和不带引号都按原文，不折叠。
 * quoted_identifier on 时双引号是标识符。方括号也是标识符。不用 LIMIT，合法查询用 TOP。
 * 驱动不在模块依赖里，测试从 {@code datahub.test.sybase.driver} 指向的 {@code com.sybase.jdbc4.jdbc.SybDriver} 加载。
 * 口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.sybase.url",
        matches = "jdbc:sybase:Tds:127[.]0[.]0[.]1:[0-9]+/tempdb")
@EnabledIfSystemProperty(named = "datahub.test.sybase.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_SYBASE_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionSybaseTest {
    private VerifiedDriverTestHandle loader;
    private Connection connection;
    private String table;

    @BeforeEach
    void create() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.sybase.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        table = "q17_" + Long.toHexString(System.nanoTime());
        loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SYBASE);
        var datasource = loader.dataSource(System.getProperty("datahub.test.sybase.url"),
                ProductTypeEnum.SYBASE.getDriveClassName(), "sa",
                System.getenv("DATAHUB_SYBASE_TEST_PASSWORD"), new Properties());
        connection = datasource.getConnection();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("SET QUOTED_IDENTIFIER ON");
            try (var version = sql.executeQuery("SELECT @@version")) {
                assertTrue(version.next());
                String banner = version.getString(1);
                assertTrue(banner.contains("Adaptive Server Enterprise/16.0 SP02 PL02"), banner);
            }
            sql.execute("CREATE TABLE dbo." + table + "(id int, name varchar(64), secret varchar(64))");
            sql.execute("INSERT INTO dbo." + table + " VALUES (1,'row','s1')");
            sql.execute("INSERT INTO dbo." + table + " VALUES (2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try {
            if (connection != null) {
                try (var sql = connection.createStatement()) {
                    if (table != null) {
                        sql.execute("SET QUOTED_IDENTIFIER ON");
                        sql.execute("DROP TABLE dbo." + table);
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
        String qualified = "\"dbo\"." + table;
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.SYBASE, connection, "dbo", table,
                List.of("SET QUOTED_IDENTIFIER ON"),
                List.of("select id, name from " + qualified,
                        "select id, name from " + qualified + " where name = 'row' order by name desc",
                        "select top 1 id from " + qualified + " order by id"));
        assertEquals(0, accepted);
    }
}
