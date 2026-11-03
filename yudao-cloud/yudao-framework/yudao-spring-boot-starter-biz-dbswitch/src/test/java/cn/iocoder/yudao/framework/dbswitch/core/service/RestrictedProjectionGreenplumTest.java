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
 * #17 Greenplum 差异验证。产品驱动是 {@code org.postgresql.Driver}。
 * 7.1.0 单容器普通表：不带引号折叠为小写，双引号按原文；{@code standard_conforming_strings}
 * 可在会话内切换，off 时反斜杠是转义。口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
@EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionGreenplumTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        Class.forName("org.postgresql.Driver");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.greenplum.url"),
                "gpadmin", System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id int, name text, secret text)");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) {
            return;
        }
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderEitherStringMode() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.GREENPLUM, connection, schema, "citizen",
                List.of("SET standard_conforming_strings = on", "SET standard_conforming_strings = off"));
        assertEquals(0, accepted);
    }
}
