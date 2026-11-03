package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #17 达梦差异验证。实例以 CASE_SENSITIVE=1 初始化：不带引号折叠为大写，双引号按原文。
 * 反斜杠不是转义。口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.dm.url", matches = "jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_DM_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionDmTest {
    private Connection connection;
    private String table;

    @BeforeEach
    void create() throws Exception {
        table = "Q17_" + Long.toHexString(System.nanoTime()).toUpperCase();
        Class.forName("dm.jdbc.driver.DmDriver");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.dm.url"),
                "SYSDBA", System.getenv("DATAHUB_DM_TEST_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + table + "(id INT, name VARCHAR(64), secret VARCHAR(64))");
            sql.execute("INSERT INTO " + table + " VALUES (1,'row','s1')");
            sql.execute("INSERT INTO " + table + " VALUES (2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) {
            return;
        }
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP TABLE " + table);
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.DM, connection, "SYSDBA", table,
                List.of("SELECT 1 FROM DUAL"));
        assertEquals(0, accepted);
    }
}
