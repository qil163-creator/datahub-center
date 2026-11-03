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
 * #17 SQL Server 2000 差异验证。8.00.760 SP3，排序 SQL_Latin1_General_CP1_CI_AS。
 * 不带引号和 QUOTED_IDENTIFIER ON 下的双引号都不区分大小写。方括号是标识符。
 * 不用 LIMIT，合法查询用 TOP。一次只插入一行。
 * 模块里的 mssql-jdbc 13.4 拒绝 server major version 8。测试从
 * {@code datahub.test.sql2000.driver} 加载同一类名的 JDBC Driver 3.0.1301.101。
 * 口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.sql2000.url",
        matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=master")
@EnabledIfSystemProperty(named = "datahub.test.sql2000.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_SQL2000_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionSqlServer2000Test {
    private VerifiedDriverTestHandle loader;
    private Connection connection;
    private String table;

    @BeforeEach
    void create() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.sql2000.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        table = "q17_" + Long.toHexString(System.nanoTime());
        loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER2000);
        var datasource = loader.dataSource(System.getProperty("datahub.test.sql2000.url"),
                ProductTypeEnum.SQLSERVER2000.getDriveClassName(), "sa",
                System.getenv("DATAHUB_SQL2000_TEST_PASSWORD"), new Properties());
        connection = datasource.getConnection();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("SET QUOTED_IDENTIFIER ON");
            try (var version = sql.executeQuery("SELECT @@version")) {
                assertTrue(version.next());
                String banner = version.getString(1);
                assertTrue(banner.contains("SQL Server  2000 - 8.00.760"), banner);
            }
            try (var version = sql.executeQuery(
                    "SELECT CAST(SERVERPROPERTY('ProductLevel') AS varchar(20)), "
                            + "CAST(DATABASEPROPERTYEX('master','Collation') AS varchar(80))")) {
                assertTrue(version.next());
                assertEquals("SP3", version.getString(1));
                assertEquals("SQL_Latin1_General_CP1_CI_AS", version.getString(2));
            }
            assertEquals("3.0.1301.101", connection.getMetaData().getDriverVersion());
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
                        sql.execute("IF OBJECT_ID('dbo." + table + "') IS NOT NULL DROP TABLE dbo." + table);
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
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.SQLSERVER2000, connection, "dbo", table,
                List.of("SET QUOTED_IDENTIFIER ON"),
                List.of("select id, name from " + qualified,
                        "select id, name from " + qualified + " where name = 'row' order by name desc",
                        "select top 1 id from " + qualified + " order by id"));
        assertEquals(0, accepted);
    }
}
