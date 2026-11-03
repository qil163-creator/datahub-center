package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 相同条件合并分别在原生 PG/MySQL/MariaDB 执行；不根据方言字符串推导其他产品通过。 */
@EnabledIfSystemProperty(named = "datahub.test.rowfilter.url", matches = "jdbc:(postgresql|mysql|mariadb)://127[.]0[.]0[.]1:[0-9]+/.*")
class RestrictedRowFilterJdbcTest {
    private Connection connection;
    private ProductTypeEnum product;
    private String schema;
    private String table;
    private static final List<String> PHYSICAL = List.of("id", "name", "org_id", "secret");

    @BeforeEach void setup() throws SQLException {
        product = ProductTypeEnum.valueOf(System.getProperty("datahub.test.rowfilter.product"));
        if (!Set.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB).contains(product)) {
            throw new IllegalArgumentException("Unverified row filter product");
        }
        String password = System.getenv("DATAHUB_ROW_FILTER_TEST_PASSWORD");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.rowfilter.url"),
                System.getProperty("datahub.test.rowfilter.user", "dataforge_test"), password == null ? "" : password);
        schema = "row_filter_" + UUID.randomUUID().toString().replace("-", "");
        table = RestrictedSelectGuard.qualifiedTableName(product, schema, "citizen");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute((product == ProductTypeEnum.POSTGRESQL ? "CREATE SCHEMA " : "CREATE DATABASE ") + schema);
            sql.execute("CREATE TABLE " + table + "(id INTEGER,name VARCHAR(64),org_id INTEGER,secret VARCHAR(64))");
            sql.execute("INSERT INTO " + table + " VALUES(1,'one',9,'y'),(2,'two',11,'y'),(3,'three',9,NULL),(4,'four',10,'y')");
        }
    }

    @AfterEach void cleanup() throws SQLException {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.setQueryTimeout(10);
            if (schema != null) {
                assertTrue(schema.matches("row_filter_[a-f0-9]{32}"));
                sql.execute(product == ProductTypeEnum.POSTGRESQL
                        ? "DROP SCHEMA IF EXISTS " + schema + " CASCADE" : "DROP DATABASE IF EXISTS " + schema);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"id=1 OR id=2 OR id=3 OR id=4", "(id=1 OR id=2) OR (id=3 OR id=4)",
            "id<=2 OR id>=3", "(id=1 AND name='one') OR id>1"})
    void andGroupingRestrictsRowsWithoutReleasingPolicyColumns(String predicate) throws SQLException {
        var result = merge("SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY id LIMIT 3",
                List.of("org_id=9 OR org_id=10", "secret IS NOT NULL"));
        var ids = new ArrayList<Integer>();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            try (var rows = sql.executeQuery(result.sql())) {
                assertEquals(2, rows.getMetaData().getColumnCount());
                assertEquals("id", rows.getMetaData().getColumnLabel(1));
                assertEquals("name", rows.getMetaData().getColumnLabel(2));
                while (rows.next()) ids.add(rows.getInt(1));
            }
        }
        assertEquals(List.of(1, 4), ids, result.sql());
    }

    @Test void policyAccessCannotBeBorrowedByUserPredicatesOrProjection() {
        for (String sql : List.of("SELECT secret FROM " + table, "SELECT id FROM " + table + " WHERE org_id=11",
                "SELECT id FROM " + table + " ORDER BY secret")) {
            assertThrows(IllegalArgumentException.class, () -> merge(sql, List.of("org_id=9")));
        }
    }

    private RestrictedSelectProjection merge(String sql, List<String> filters) {
        return RestrictedSelectGuard.restrictProjectionWithRowFilters(product, sql, schema, "citizen", PHYSICAL,
                Set.of("id", "name"), filters);
    }
}
