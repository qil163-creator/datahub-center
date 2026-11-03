package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 PostgreSQL 验证八种稽查算法的分母、问题行和正常行保持同一范围。 */
@Testcontainers(disabledWithoutDocker = true)
class QdataAuditRulePostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void characterValidationHonorsAnchorsCaseNullAndTypedScope() throws Exception {
        try (Connection connection = fixture("value text", """
                (1,true,'inside','AB12'), (2,true,'inside','ab12'),
                (3,true,'inside',''), (4,true,'inside',NULL), (5,true,'inside',E'AB\\n12'),
                (80,false,'inside','bad'), (90,true,'outside',NULL)
                """)) {
            AuditRuleContext context = context("value", Map.of("regex", "^[A-Z0-9]+$"));
            assertPopulation(connection, "CHARACTER_VALIDATION", context,
                    List.of(1, 2, 3, 4, 5), List.of(2, 3, 4, 5));
            context.setConfig(Map.of("regex", "^[A-Z0-9]+$", "ignoreNullValue", true));
            assertPopulation(connection, "CHARACTER_VALIDATION", context,
                    List.of(1, 2, 3, 4, 5), List.of(2, 3, 5));
        }
    }

    @Test
    void lengthValidationUsesInclusiveCharacterBoundsAndPreservesZeroLength() throws Exception {
        try (Connection connection = fixture("value text", """
                (1,true,'inside','中😀'), (2,true,'inside','abc'),
                (3,true,'inside','a'), (4,true,'inside','abcd'),
                (5,true,'inside',NULL), (6,true,'inside',''), (7,true,'inside','ab '),
                (80,false,'inside',NULL), (90,true,'outside','outside')
                """)) {
            AuditRuleContext context = context("value", Map.of("minLength", 2, "maxLength", 3));
            List<Integer> population = List.of(1, 2, 3, 4, 5, 6, 7);
            assertPopulation(connection, "LENGTH_VALIDATION", context, population, List.of(3, 4, 5, 6));
            context.setConfig(Map.of("minLength", 2, "maxLength", 3, "ignoreNullValue", true));
            assertPopulation(connection, "LENGTH_VALIDATION", context, population, List.of(3, 4, 6));
            context.setConfig(Map.of("minLength", 0, "maxLength", 0));
            assertPopulation(connection, "LENGTH_VALIDATION", context, population, List.of(1, 2, 3, 4, 5, 7));
        }
    }

    @Test
    void decimalValidationChecksMaximumScaleAndPrecisionWithoutLosingFractionalZero() throws Exception {
        try (Connection connection = fixture("value numeric", """
                (1,true,'inside',1.2), (2,true,'inside',1.20),
                (3,true,'inside',1.234), (4,true,'inside',12),
                (5,true,'inside',NULL), (6,true,'inside',-0.12),
                (7,true,'inside',1234.56), (8,true,'inside',0.001), (9,true,'inside',0.00),
                (80,false,'inside',123456), (90,true,'outside',123456)
                """)) {
            AuditRuleContext context = context("value",
                    Map.of("precision", 4, "scale", 2, "skipInteger", true));
            List<Integer> population = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
            assertPopulation(connection, "DECIMAL_PRECISION_VALIDATION", context,
                    population, List.of(3, 5, 7, 8));
            context.setConfig(Map.of("precision", 4, "scale", 2, "skipInteger", true, "ignoreNullValue", true));
            assertPopulation(connection, "DECIMAL_PRECISION_VALIDATION", context,
                    population, List.of(3, 7, 8));
            context.setConfig(Map.of("precision", 4, "scale", 2, "skipInteger", false));
            assertPopulation(connection, "DECIMAL_PRECISION_VALIDATION", context,
                    population, List.of(3, 4, 5, 7, 8));
            context.setWhereClause("id IN (6,9)");
            context.setConfig(Map.of("precision", 2, "scale", 2, "skipInteger", true));
            assertPopulation(connection, "DECIMAL_PRECISION_VALIDATION", context, List.of(6, 9), List.of());
        }
    }

    @Test
    void enumValidationPreservesQuotesAndSeparatesCaseNullAndEmpty() throws Exception {
        try (Connection connection = fixture("value text", """
                (1,true,'inside','OK'), (2,true,'inside','ok'),
                (3,true,'inside','O''Reilly'), (4,true,'inside','missing'),
                (5,true,'inside',NULL), (6,true,'inside',''),
                (80,false,'inside','missing'), (90,true,'outside',NULL)
                """)) {
            List<String> allowed = List.of("OK", "O'Reilly", "OK");
            AuditRuleContext context = context("value", Map.of("validValues", allowed));
            List<Integer> population = List.of(1, 2, 3, 4, 5, 6);
            assertPopulation(connection, "ENUM_VALIDATION", context, population, List.of(2, 4, 5, 6));
            context.setConfig(Map.of("validValues", allowed, "ignoreCase", true, "ignoreNullValue", true));
            assertPopulation(connection, "ENUM_VALIDATION", context, population, List.of(4, 6));
        }
    }

    @Test
    void numericRangeRetainsExactDecimalBoundariesAboveJavascriptSafeInteger() throws Exception {
        String min = "9007199254740993.0000000000000000001";
        String max = "9007199254740993.0000000000000000003";
        try (Connection connection = fixture("value numeric", """
                (1,true,'inside',9007199254740993.0000000000000000001),
                (2,true,'inside',9007199254740993.0000000000000000002),
                (3,true,'inside',9007199254740993.0000000000000000003),
                (4,true,'inside',9007199254740993.0000000000000000000),
                (5,true,'inside',9007199254740993.0000000000000000004),
                (6,true,'inside',NULL), (80,false,'inside',0), (90,true,'outside',0)
                """)) {
            AuditRuleContext context = context("value", Map.of("minValue", min, "maxValue", max));
            List<Integer> population = List.of(1, 2, 3, 4, 5, 6);
            assertPopulation(connection, "NUMERIC_RANGE_VALIDATION", context, population, List.of(4, 5, 6));
            context.setConfig(Map.of("minValue", min, "maxValue", max, "includeBoundary", false));
            assertPopulation(connection, "NUMERIC_RANGE_VALIDATION", context, population, List.of(1, 3, 4, 5, 6));
            context.setConfig(Map.of("minValue", min, "maxValue", max,
                    "includeMin", true, "includeMax", false, "ignoreNullValue", true));
            assertPopulation(connection, "NUMERIC_RANGE_VALIDATION", context, population, List.of(3, 4, 5));
            context.setConfig(Map.of("minValue", min));
            assertPopulation(connection, "NUMERIC_RANGE_VALIDATION", context, population, List.of(4, 6));
            context.setConfig(Map.of("maxValue", max));
            assertPopulation(connection, "NUMERIC_RANGE_VALIDATION", context, population, List.of(5, 6));
        }
    }

    @Test
    void timeOrderCombinesConditionsAndStillReportsKnownFailureBesideIgnoredNull() throws Exception {
        try (Connection connection = fixture("began timestamp, middle timestamp, finished timestamp", """
                (1,true,'inside','2026-01-01','2026-01-02','2026-01-03'),
                (2,true,'inside','2026-01-02','2026-01-02','2026-01-03'),
                (3,true,'inside','2026-01-01','2026-01-03','2026-01-03'),
                (4,true,'inside','2026-01-03','2026-01-02','2026-01-04'),
                (5,true,'inside','2026-01-01',NULL,'2026-01-03'),
                (6,true,'inside',NULL,'2026-01-03','2026-01-02'),
                (7,true,'inside',NULL,NULL,NULL),
                (80,false,'inside','2026-01-03','2026-01-02','2026-01-01'),
                (90,true,'outside',NULL,NULL,NULL)
                """)) {
            List<Map<String, String>> comparisons = List.of(
                    Map.of("leftField", "began", "operator", "<=", "rightField", "middle"),
                    Map.of("leftField", "middle", "operator", "<", "rightField", "finished"));
            AuditRuleContext context = context("began", Map.of("conditions", comparisons));
            context.setColumns(List.of("middle", "finished"));
            List<Integer> population = List.of(1, 2, 3, 4, 5, 6, 7);
            assertPopulation(connection, "TIME_ORDER_VALIDATION", context,
                    population, List.of(3, 4, 5, 6, 7));
            context.setConfig(Map.of("conditions", comparisons, "ignoreNullValue", true));
            assertPopulation(connection, "TIME_ORDER_VALIDATION", context, population, List.of(3, 4, 6));
        }
    }

    @Test
    void groupCompletenessDistinguishesAllNullPartialAndFalsyValues() throws Exception {
        try (Connection connection = fixture("a text, b text, c text", """
                (1,true,'inside','a','b','c'), (2,true,'inside',NULL,'b','c'),
                (3,true,'inside',NULL,NULL,NULL), (4,true,'inside','','0','false'),
                (5,true,'inside',NULL,NULL,'c'), (80,false,'inside',NULL,NULL,NULL),
                (90,true,'outside',NULL,'b',NULL)
                """)) {
            AuditRuleContext context = context("a", Map.of("fillStrategy", 1, "ignoreNullValue", true));
            context.setColumns(List.of("b", "c"));
            List<Integer> population = List.of(1, 2, 3, 4, 5);
            assertPopulation(connection, "GROUP_FIELD_COMPLETENESS", context, population, List.of(2, 3, 5));
            context.setConfig(Map.of("fillStrategy", 2, "ignoreNullValue", true));
            assertPopulation(connection, "GROUP_FIELD_COMPLETENESS", context, population, List.of(2, 5));
        }
    }

    @Test
    void compositeUniquenessReturnsEveryMemberAndAppliesTypedScopeBeforeGrouping() throws Exception {
        try (Connection connection = fixture("a text, b text", """
                (1,true,'inside','dup','a'), (2,true,'inside','dup','a'), (3,true,'inside','dup','a'),
                (4,true,'inside',NULL,'n'), (5,true,'inside',NULL,'n'),
                (6,true,'inside','','blank'), (7,true,'inside','','blank'),
                (8,true,'inside','unique','x'), (9,true,'inside','scope','u'),
                (80,true,'outside','scope','u'), (81,false,'inside','scope','u'),
                (90,true,'outside','dup','a')
                """)) {
            AuditRuleContext context = context("a", Map.of("ignoreNull", false));
            context.setColumns(List.of("b"));
            List<Integer> population = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
            assertPopulation(connection, "COMPOSITE_UNIQUENESS_VALIDATION", context,
                    population, List.of(1, 2, 3, 4, 5, 6, 7));
            context.setConfig(Map.of("ignoreNull", true));
            assertPopulation(connection, "COMPOSITE_UNIQUENESS_VALIDATION", context,
                    population, List.of(1, 2, 3, 6, 7));
        }
    }

    private static Connection fixture(String columns, String rows) throws Exception {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE qdata_audit_fixture "
                    + "(id integer, active boolean, population text, " + columns + ")");
            statement.execute("INSERT INTO qdata_audit_fixture VALUES " + rows);
        } catch (Exception ex) {
            connection.close();
            throw ex;
        }
        return connection;
    }

    private static AuditRuleContext context(String column, Map<String, Object> config) {
        AuditRuleContext context = new AuditRuleContext(DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("qdata_audit_fixture");
        context.setColumn(column);
        context.setConfig(config);
        context.setWhereClause("active = true OR id = 90");
        context.addTextEqualsScope("population", "inside");
        return context;
    }

    private static void assertPopulation(Connection connection, String strategy, AuditRuleContext context,
                                         List<Integer> population, List<Integer> errors) throws Exception {
        AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator(strategy);
        String sql = generator.buildCountSql(context);
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            assertEquals(population.size(), result.getLong("totalCount"), sql);
            assertEquals(errors.size(), result.getLong("errorCount"), sql);
            assertFalse(result.next(), sql);
        }
        assertEquals(errors, rowIds(connection, generator.buildErrorDataSql(context, 100)), strategy);
        List<Integer> normal = population.stream().filter(id -> !errors.contains(id)).toList();
        assertEquals(normal, rowIds(connection, generator.buildNormalDataSql(context, 100)), strategy);
    }

    private static List<Integer> rowIds(Connection connection, String sql) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) ids.add(result.getInt("id"));
        }
        return ids.stream().sorted().toList();
    }
}

