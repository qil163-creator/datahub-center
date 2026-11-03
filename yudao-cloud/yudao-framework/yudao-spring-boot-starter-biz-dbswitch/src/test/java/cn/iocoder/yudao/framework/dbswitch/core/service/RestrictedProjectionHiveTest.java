package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 Hive 差异验证：apache/hive 4.1.0 普通文本表。反引号是标识符，不带引号和反引号都不区分大小写。
 * 默认 {@code hive.support.quoted.identifiers=column} 时双引号是字符串；{@code standard} 时双引号是标识符。
 * 守卫按 MySQL 词法把双引号当标识符，两种设置下都不把双引号放行。{@code none} 把反引号改成正则，不在本测试的会话里。
 * <p>
 * 连接使用官方 standalone 驱动：{@code datahub.test.hive.url} 形如 {@code jdbc:hive2://127.0.0.1:PORT/default}，
 * {@code datahub.test.hive.driver} 指向 {@code hive-jdbc-4.1.0-standalone.jar}。用户 {@code hive}、空口令。
 */
@EnabledIfSystemProperty(named = "datahub.test.hive.url", matches = "jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.hive.driver", matches = ".+")
class RestrictedProjectionHiveTest {
    private VerifiedDriverTestHandle loader;
    private java.sql.Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        Path driverPath = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        assertTrue(Files.isRegularFile(driverPath));
        schema = RestrictedProjectionDifferential.schemaName("q17");
        loader = new VerifiedDriverTestHandle(driverPath, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE);
        var datasource = loader.dataSource(System.getProperty("datahub.test.hive.url"),
                ProductTypeEnum.HIVE.getDriveClassName(), "hive", "", new Properties());
        connection = datasource.getConnection();
        assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("4.1."));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(180);
            sql.execute("CREATE DATABASE " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id BIGINT, name STRING, secret STRING) "
                    + "STORED AS TEXTFILE");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try {
            if (connection != null) {
                try (var sql = connection.createStatement()) {
                    sql.setQueryTimeout(180);
                    sql.execute("SET hive.support.quoted.identifiers=column");
                    if (schema != null) {
                        sql.execute("DROP DATABASE IF EXISTS " + schema + " CASCADE");
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
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.HIVE, connection, schema, "citizen",
                List.of(
                        "SET hive.support.quoted.identifiers=column",
                        "SET hive.support.quoted.identifiers=standard"));
        assertEquals(0, accepted);
    }
}
