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
 * #17 DB2 12.1.5.0 差异验证。不带引号的标识符折叠为大写，双引号按原文。
 * 反斜杠不是转义。不用 LIMIT，合法查询用普通 SELECT。当前模式是实例用户 DB2INST1。
 * 口令优先取系统属性 datahub.test.db2.password，回退环境变量 DATAHUB_DB2_TEST_PASSWORD。
 */
@EnabledIfSystemProperty(named = "datahub.test.db2.url", matches = "jdbc:db2://127[.]0[.]0[.]1:[0-9]+/Q17DB")
class RestrictedProjectionDb2Test {
    private Connection connection;
    private String table;

    @BeforeEach
    void create() throws Exception {
        Class.forName(ProductTypeEnum.DB2.getDriveClassName());
        table = "Q17_" + Long.toHexString(System.nanoTime()).toUpperCase();
        connection = DriverManager.getConnection(System.getProperty("datahub.test.db2.url"),
                System.getProperty("datahub.test.db2.user", "db2inst1"),
                password());
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + table + "(id INTEGER, name VARCHAR(64), secret VARCHAR(64))");
            sql.execute("INSERT INTO " + table + " VALUES (1,'row','s1')");
            sql.execute("INSERT INTO " + table + " VALUES (2,'row','s2')");
        }
    }

    /** System property first (existing convention), environment fallback for DPAPI-launched runners. */
    private static String password() {
        String property = System.getProperty("datahub.test.db2.password");
        return property != null ? property : System.getenv("DATAHUB_DB2_TEST_PASSWORD");
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP TABLE " + table);
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        String qualified = "\"DB2INST1\"." + table;
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.DB2, connection, "DB2INST1", table,
                List.of("SET CURRENT SCHEMA = DB2INST1"),
                List.of("select id, name from " + qualified,
                        "select id, name from " + qualified + " where name = 'row' order by name desc"));
        assertEquals(0, accepted);
    }
}
