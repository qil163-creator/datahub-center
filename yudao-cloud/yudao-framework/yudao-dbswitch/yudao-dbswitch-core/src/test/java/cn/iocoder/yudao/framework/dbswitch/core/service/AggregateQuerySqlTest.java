package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.dbswitch.core.service.AggregateQuerySql.*;
import static org.junit.jupiter.api.Assertions.*;

class AggregateQuerySqlTest {

    private static final List<String> COLUMNS = List.of("region", "pay_status", "amount", "pay_time", "user_id");

    private static Request request(ProductTypeEnum product, List<String> dimensions, Aggregation aggregation,
            TimeWindow time, List<Filter> filters, List<String> rowFilters) {
        return new Request(product, "dw", "fact_order", COLUMNS, dimensions, "amount", aggregation, time, filters,
                rowFilters, 101);
    }

    @Test
    void postgresGoldenSqlBindsRangeAndFiltersAndMergesBusinessCondition() {
        var plan = render(request(ProductTypeEnum.POSTGRESQL, List.of("region"), Aggregation.SUM,
                new TimeWindow("pay_time", TimeGrain.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)),
                List.of(new Filter("region", "east")), List.of("pay_status = 'PAID'")));
        assertEquals("SELECT \"dh_dim_0\", \"dh_time_bucket\", SUM(\"dh_value\") AS \"dh_value\" FROM ("
                + "SELECT \"dh_src\".\"region\" AS \"dh_dim_0\", "
                + "CAST(DATE_TRUNC('month', CAST(\"dh_src\".\"pay_time\" AS TIMESTAMP)) AS DATE) AS \"dh_time_bucket\", "
                + "\"dh_src\".\"amount\" AS \"dh_value\" FROM ("
                + "SELECT \"region\", \"amount\", \"pay_time\"\nFROM \"dw\".\"fact_order\"\nWHERE (\"pay_status\" = 'PAID')"
                + ") \"dh_src\" WHERE \"dh_src\".\"pay_time\" >= ? AND \"dh_src\".\"pay_time\" < ? "
                + "AND \"dh_src\".\"region\" = ?) \"dh_agg\" GROUP BY \"dh_dim_0\", \"dh_time_bucket\" "
                + "ORDER BY \"dh_time_bucket\" ASC, \"dh_dim_0\" ASC LIMIT 101", plan.sql());
        assertEquals(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "east"), plan.parameters());
        assertEquals(List.of("dh_dim_0"), plan.dimensionAliases());
        assertEquals("dh_time_bucket", plan.timeAlias());
        assertEquals("dh_value", plan.valueAlias());
        assertFalse(plan.toString().contains("east"));
    }

    @Test
    void mysqlGoldenSqlUsesBackticksAndMysqlBuckets() {
        var plan = render(request(ProductTypeEnum.MYSQL, List.of("region", "pay_status"), Aggregation.COUNT_DISTINCT,
                new TimeWindow("pay_time", TimeGrain.QUARTER, null, null), List.of(), List.of()));
        assertEquals("SELECT `dh_dim_0`, `dh_dim_1`, `dh_time_bucket`, COUNT(DISTINCT `dh_value`) AS `dh_value` FROM ("
                + "SELECT `dh_src`.`region` AS `dh_dim_0`, `dh_src`.`pay_status` AS `dh_dim_1`, "
                + "(MAKEDATE(YEAR(`dh_src`.`pay_time`), 1) + INTERVAL (QUARTER(`dh_src`.`pay_time`) - 1) QUARTER)"
                + " AS `dh_time_bucket`, `dh_src`.`amount` AS `dh_value` FROM ("
                + "SELECT `region`, `pay_status`, `amount`, `pay_time`\nFROM `dw`.`fact_order`"
                + ") `dh_src`) `dh_agg` GROUP BY `dh_dim_0`, `dh_dim_1`, `dh_time_bucket` "
                + "ORDER BY `dh_time_bucket` ASC, `dh_dim_0` ASC, `dh_dim_1` ASC LIMIT 101", plan.sql());
        assertTrue(plan.parameters().isEmpty());
    }

    @Test
    void totalWithoutDimensionsHasNoGroupBy() {
        var plan = render(request(ProductTypeEnum.POSTGRESQL, List.of(), Aggregation.AVG, null, null, null));
        assertEquals("SELECT AVG(\"dh_value\") AS \"dh_value\" FROM (SELECT \"dh_src\".\"amount\" AS \"dh_value\" "
                + "FROM (SELECT \"amount\"\nFROM \"dw\".\"fact_order\") \"dh_src\") \"dh_agg\" LIMIT 101", plan.sql());
        assertNull(plan.timeAlias());
    }

    @ParameterizedTest
    @EnumSource(value = TimeGrain.class)
    void everyGrainRendersForEverySupportedFamily(TimeGrain grain) {
        for (ProductTypeEnum product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.OPENGAUSS,
                ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB)) {
            var plan = render(request(product, List.of(), Aggregation.SUM,
                    new TimeWindow("pay_time", grain, null, null), null, null));
            assertTrue(plan.sql().contains("dh_time_bucket"), product + " " + grain);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"ORACLE", "SQLSERVER", "TIDB", "DORIS", "CLICKHOUSE",
            "KINGBASE", "DM"})
    void unverifiedProductsAreUnsupported(ProductTypeEnum product) {
        assertFalse(supports(product));
        assertThrows(UnsupportedOperationException.class, () -> render(request(product, List.of("region"),
                Aggregation.SUM, null, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pay_status = 'PAID'; DROP TABLE fact_order",
            "pay_status = 'PAID') OR (SELECT 1) = (1",
            "user_id IN (SELECT id FROM pg_user)",
            "EXISTS (SELECT 1 FROM pg_shadow)",
            "pg_sleep(10) IS NULL",
            "lower(pay_status) = 'paid'",
            "pay_status = ?",
            "password = 'x'",
            "pay_status = 'PAID' UNION SELECT usename FROM pg_user",
            "pay_status = 'PAID' ORDER BY amount",
            "pay_status = 'PAID' -- ",
            "pay_status = 'PAID' /* comment */",
            "\"pay_status\" = 'PAID' OR \"dh_src\".\"amount\" > 0"
    })
    void maliciousBusinessConditionsAreRejected(String condition) {
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of("region"), Aggregation.SUM, null, null, List.of(condition))));
    }

    @Test
    void tautologyConditionStaysInsideRestrictedSourceParentheses() {
        // 括号闭合后重开的条件仍只是一个受限谓词，被重建后整体包在源查询内部。
        var plan = render(request(ProductTypeEnum.POSTGRESQL, List.of("region"), Aggregation.SUM, null, null,
                List.of("pay_status = 'PAID') OR (1 = 1")));
        assertTrue(plan.sql().contains("FROM \"dw\".\"fact_order\"\nWHERE ((\"pay_status\" = 'PAID')\n\tOR (1 = 1))) \"dh_src\""),
                plan.sql());
        assertTrue(plan.sql().endsWith("\"dh_agg\" GROUP BY \"dh_dim_0\" ORDER BY \"dh_dim_0\" ASC LIMIT 101"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"region\" FROM pg_shadow --", "REGION", "region ", "", "dh_value", "*"})
    void columnsOutsideMetadataAreRejected(String column) {
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of(column), Aggregation.SUM, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of("region"), Aggregation.SUM, null, List.of(new Filter(column, "x")), null)));
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of(), Aggregation.SUM, new TimeWindow(column, TimeGrain.DAY, null, null), null, null)));
    }

    @Test
    void physicalNamesWithQuotesAreEscapedNotInterpreted() {
        var columns = List.of("a\"b", "amount");
        var plan = render(new Request(ProductTypeEnum.POSTGRESQL, "dw", "t\"x", columns, List.of("a\"b"), "amount",
                Aggregation.SUM, null, null, null, 10));
        assertTrue(plan.sql().contains("\"dh_src\".\"a\"\"b\" AS \"dh_dim_0\""), plan.sql());
        assertTrue(plan.sql().contains("FROM \"dw\".\"t\"\"x\""), plan.sql());
        var mysql = render(new Request(ProductTypeEnum.MYSQL, "dw", "t`x", List.of("a`b", "amount"),
                List.of("a`b"), "amount", Aggregation.SUM, null, null, null, 10));
        assertTrue(mysql.sql().contains("`dh_src`.`a``b` AS `dh_dim_0`"), mysql.sql());
        assertTrue(mysql.sql().contains("FROM `dw`.`t``x`"), mysql.sql());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "MYSQL"})
    void quoteEscapesInsideConditionLiteralsAreRejected(ProductTypeEnum product) {
        for (String condition : List.of("pay_status = 'x'' OR ''1''=''1'", "pay_status = 'x\\' OR 1=1 -- '",
                "pay_status = E'x\\'' OR 1=1")) {
            assertThrows(IllegalArgumentException.class, () -> render(request(product, List.of("region"),
                    Aggregation.SUM, null, null, List.of(condition))), condition);
        }
    }

    @Test
    void filterValuesAreAlwaysBoundNeverInlined() {
        String hostile = "east' OR '1'='1";
        var plan = render(request(ProductTypeEnum.POSTGRESQL, List.of("region"), Aggregation.SUM, null,
                List.of(new Filter("region", hostile), new Filter("amount", new BigDecimal("1.10"))), null));
        assertFalse(plan.sql().contains("east"));
        assertEquals(List.of(hostile, new BigDecimal("1.10")), plan.parameters());
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of("region", "region"), Aggregation.SUM, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of(), Aggregation.SUM, null, List.of(new Filter("region", null)), null)));
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of(), Aggregation.SUM, null, List.of(new Filter("region", new Object())), null)));
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of(), Aggregation.SUM, new TimeWindow("pay_time", null, "2026-01-01", null), null, null)));
        assertThrows(IllegalArgumentException.class, () -> render(request(ProductTypeEnum.POSTGRESQL,
                List.of(), null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> render(new Request(ProductTypeEnum.POSTGRESQL, "dw",
                "fact_order", COLUMNS, List.of(), "amount", Aggregation.SUM, null, null, null, 0)));
        assertThrows(IllegalArgumentException.class, () -> render(new Request(ProductTypeEnum.POSTGRESQL, "dw",
                "fact_order", COLUMNS, List.of(), "amount", Aggregation.SUM, null, null, null, MAX_LIMIT + 1)));
        assertThrows(IllegalArgumentException.class, () -> render(new Request(ProductTypeEnum.POSTGRESQL, "",
                "fact_order", COLUMNS, List.of(), "amount", Aggregation.SUM, null, null, null, 10)));
    }
}
