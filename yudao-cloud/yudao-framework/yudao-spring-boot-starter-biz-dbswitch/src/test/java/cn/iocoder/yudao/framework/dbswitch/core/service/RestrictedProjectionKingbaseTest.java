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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 金仓差异验证。默认镜像 DB_MODE=oracle，字符串比较不区分大小写。
 * 不带引号的列存成小写；带引号的列保留目录大小写，查询时仍不区分大小写。
 * {@code standard_conforming_strings} 可在会话内切换。口令只从环境变量读取。
 */
@EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:[0-9]+/test")
@EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
class RestrictedProjectionKingbaseTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        Class.forName("com.kingbase8.Driver");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.kingbase.url"),
                "system", System.getenv("DATAHUB_KINGBASE_TEST_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id int, name text, secret text)");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1')");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (2,'row','s2')");
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
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.KINGBASE, connection, schema, "citizen",
                List.of("SET standard_conforming_strings = on", "SET standard_conforming_strings = off"));
        assertEquals(0, accepted);

        String quotedTable = "q17q" + Long.toHexString(System.nanoTime());
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + schema + "." + quotedTable + "(\"Id\" int, name text, secret text)");
            sql.execute("INSERT INTO " + schema + "." + quotedTable + " VALUES (7,'row','hidden')");
            String rewritten = RestrictedSelectGuard.restrictProjection(ProductTypeEnum.KINGBASE,
                    "select id, name from " + schema + "." + quotedTable + " where name = 'row'",
                    schema, quotedTable, List.of("Id", "name", "secret"), Set.of("Id", "name")).sql();
            assertTrue(rewritten.contains("\"Id\""));
            assertFalse(rewritten.toLowerCase().contains("secret"));
            try (var rows = sql.executeQuery(rewritten)) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
            String quotedName = RestrictedSelectGuard.restrictProjection(ProductTypeEnum.KINGBASE,
                    "select \"ID\" from " + schema + "." + quotedTable,
                    schema, quotedTable, List.of("Id", "name", "secret"), Set.of("Id", "name")).sql();
            try (var rows = sql.executeQuery(quotedName)) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
        }
    }
}
