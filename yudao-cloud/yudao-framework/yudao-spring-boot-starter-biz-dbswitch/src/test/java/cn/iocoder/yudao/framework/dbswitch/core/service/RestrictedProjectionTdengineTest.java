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
 * #17 TDengine 3.3.8.8 普通表差异验证。反引号是标识符，双引号是字符串。
 * 普通表首列必须是时间戳；投影测试不把时间戳放进批准列。
 */
@EnabledIfSystemProperty(named = "datahub.test.tdengine.url", matches = "jdbc:TAOS-RS://127[.]0[.]0[.]1:[0-9]+/")
class RestrictedProjectionTdengineTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        Class.forName("com.taosdata.jdbc.rs.RestfulDriver");
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.tdengine.url"), "root",
                System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE DATABASE " + schema + " VGROUPS 1 BUFFER 8");
            sql.execute("CREATE TABLE " + schema + ".citizen(ts TIMESTAMP, id BIGINT, name BINARY(64), secret BINARY(64))");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES "
                    + "('2026-09-28 00:00:01',1,'row','s1'),('2026-09-28 00:00:02',2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + schema);
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.TDENGINE, connection, schema, "citizen",
                List.of("SELECT SERVER_VERSION()"));
        assertEquals(0, accepted);
    }
}
