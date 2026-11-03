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
 * #17 openGauss 差异验证：openGauss 为 PostgreSQL 家族（线协议兼容、standard_conforming_strings 同款会话开关，
 * 见组件 {@code isLikePostgres()}）。两种字符串转义语义下，受限投影输出都不能借字面值/注释/美元引用触达未批准列。
 * <p>
 * 连接沿用组件内既有 openGauss 真实库测试约定：系统属性 {@code datahub.test.opengauss.url} 指向本机实例，
 * 口令来自环境变量 {@code DATAHUB_OPENGAUSS_TEST_PASSWORD}（不写入源码/测试数据）。
 */
@EnabledIfSystemProperty(named = "datahub.test.opengauss.url", matches = "jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/(?:postgres|dbswitch_test)")
@EnabledIfEnvironmentVariable(named = "DATAHUB_OPENGAUSS_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionOpenGaussTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        Class.forName("org.opengauss.Driver");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.opengauss.url"),
                System.getProperty("datahub.test.opengauss.user", "codex_test"), System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id int, name text, secret text)");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderEitherStringMode() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.OPENGAUSS, connection, schema, "citizen",
                List.of("SET standard_conforming_strings = on", "SET standard_conforming_strings = off"));
        // 加固后所有越权输入都在授权前被拒绝；合法查询的真实执行覆盖由 run(...) 内部断言。
        assertEquals(0, accepted);
    }
}
