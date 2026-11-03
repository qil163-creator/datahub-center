package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #17 Oracle 23.26 Free 差异验证。不带引号的标识符折叠为大写，双引号按原文。
 * 物理列按折叠后的大写名称绑定。不用 LIMIT。
 */
@EnabledIfSystemProperty(named = "datahub.test.oracle.url", matches = "jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
class RestrictedProjectionOracleTest {
    private Connection connection;
    private String table;

    @BeforeEach
    void create() throws Exception {
        table = "Q17_" + Long.toHexString(System.nanoTime()).toUpperCase();
        connection = DriverManager.getConnection(System.getProperty("datahub.test.oracle.url"), "SYSTEM",
                System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + table + "(id NUMBER, name VARCHAR2(64), secret VARCHAR2(64))");
            sql.execute("INSERT INTO " + table + " VALUES (1,'row','s1')");
            sql.execute("INSERT INTO " + table + " VALUES (2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP TABLE " + table + " PURGE");
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        String qualified = "\"" + "SYSTEM" + "\"." + table;
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.ORACLE, connection, "SYSTEM", table,
                List.of("ALTER SESSION SET OPTIMIZER_MODE = ALL_ROWS"),
                List.of("select id, name from " + qualified,
                        "select id, name from " + qualified + " where name = 'row' order by name desc"));
        assertEquals(0, accepted);
    }
}
