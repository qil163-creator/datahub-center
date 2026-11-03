package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 ClickHouse 差异验证：25.8.33.6 使用反引号，库名和列名区分大小写且不折叠，双引号是标识符。
 * 字符串默认把反斜杠当转义。会话 {@code dialect} 只能是 clickhouse、kusto、prql、promql；
 * 守卫输出的反引号 SQL 只在 clickhouse 方言下执行。kusto/prql/promql 解析失败即不执行。
 * <p>
 * 连接沿用既有约定：{@code datahub.test.clickhouse.url} 形如 {@code jdbc:clickhouse://127.0.0.1:PORT/default}，
 * 用户默认 {@code default}、空口令。建库用 Atomic，建表用 MergeTree。
 */
@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class RestrictedProjectionClickHouseTest {
    private static final List<String> PHYSICAL = List.of("id", "name", "secret");
    private static final Set<String> ALLOWED = Set.of("id", "name");
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = RestrictedProjectionDifferential.open(System.getProperty("datahub.test.clickhouse.url"),
                System.getProperty("datahub.test.clickhouse.user", "default"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE DATABASE " + schema + " ENGINE = Atomic");
            sql.execute("CREATE TABLE " + schema + ".citizen(id Int64, name String, secret String) "
                    + "ENGINE = MergeTree ORDER BY id");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("SET dialect = 'clickhouse'");
            sql.execute("DROP DATABASE IF EXISTS " + schema + " SYNC");
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.CLICKHOUSE, connection, schema, "citizen",
                List.of("SET dialect = 'clickhouse'"));
        assertEquals(0, accepted);
        String rendered = RestrictedSelectGuard.restrictProjection(ProductTypeEnum.CLICKHOUSE,
                "select id, name from `" + schema + "`.citizen where name = 'row'",
                schema, "citizen", PHYSICAL, ALLOWED).sql();
        for (String dialect : List.of("kusto", "prql", "promql")) {
            try (var sql = connection.createStatement()) {
                sql.execute("SET dialect = '" + dialect + "'");
                try (var rows = sql.executeQuery(rendered)) {
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                        assertTrue(ALLOWED.contains(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT)),
                                dialect);
                    }
                } catch (SQLException ignored) {
                    // 这些方言无法解析反引号 SQL，查询不会执行。
                }
            }
        }
    }
}
