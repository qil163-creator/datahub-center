package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
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

/** Real SQL proof: count and problem rows must describe the same scoped population. */
@Testcontainers(disabledWithoutDocker = true)
class UniquenessPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void singleFieldRespectsScopeAndIgnoresNullWithoutMergingNullAndEmpty() throws Exception {
        try (Connection connection = fixture()) {
            AuditRuleContext context = context(List.of("a"), Map.of());
            AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("UNIQUE_VALIDATION");
            assertCounts(connection, generator, context, 10, 6);
            assertEquals(List.of(1, 2, 5, 6, 7, 8), errorIds(connection, generator, context, 100));
            context.setConfig(Map.of("ignoreNull", false));
            assertCounts(connection, generator, context, 10, 8);
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), errorIds(connection, generator, context, 100));
            assertEquals(2, errorIds(connection, generator, context, 2).size());
            // A single null and a single empty string must not form a duplicate group together.
            context.setWhereClause("active = true AND id IN (3, 5)");
            assertCounts(connection, generator, context, 2, 0);
            assertTrue(errorIds(connection, generator, context, 100).isEmpty());
        }
    }

    @Test
    void compositeNullsAreExplicitAndEveryDuplicateMemberIsReturned() throws Exception {
        try (Connection connection = fixture()) {
            AuditRuleContext context = context(List.of("a", "b"), Map.of("ignoreNull", false));
            AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("COMPOSITE_UNIQUENESS_VALIDATION");
            assertCounts(connection, generator, context, 10, 6);
            assertEquals(List.of(1, 2, 3, 4, 5, 6), errorIds(connection, generator, context, 100));
            context.setConfig(Map.of("ignoreNull", true));
            assertCounts(connection, generator, context, 10, 4);
            assertEquals(List.of(1, 2, 5, 6), errorIds(connection, generator, context, 100));
            context.setWhereClause("active = false");
            assertCounts(connection, generator, context, 1, 0);
            assertTrue(errorIds(connection, generator, context, 100).isEmpty());
            context.setWhereClause("1 = 0");
            assertCounts(connection, generator, context, 0, 0);
            // A null in a later component obeys the same policy as a null in the first component.
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE quality_unique_fixture SET b = NULL WHERE id = 8");
            }
            context.setWhereClause("active = true");
            context.setConfig(Map.of("ignoreNull", false));
            assertCounts(connection, generator, context, 10, 8);
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), errorIds(connection, generator, context, 100));
            context.setConfig(Map.of("ignoreNull", true));
            assertCounts(connection, generator, context, 10, 4);
            assertEquals(List.of(1, 2, 5, 6), errorIds(connection, generator, context, 100));
        }
    }

    @Test
    void nullConfigAndQuotedColumnsRemainSupported() throws Exception {
        try (Connection connection = fixture()) {
            AuditRuleContext context = context(List.of("\"a\"", "\"b\""), null);
            AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("COMPOSITE_UNIQUENESS_VALIDATION");
            // Historical composite rules include null keys unless ignoreNull is explicitly enabled.
            assertCounts(connection, generator, context, 10, 6);
            assertEquals(6, errorIds(connection, generator, context, 100).size());
        }
    }

    private static Connection fixture() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE quality_unique_fixture(id int, a text, b text, active boolean)");
            statement.execute("INSERT INTO quality_unique_fixture VALUES "
                    + "(1,'x','one',true),(2,'x','one',true),(3,NULL,'n',true),(4,NULL,'n',true),"
                    + "(5,'','empty',true),(6,'','empty',true),(7,'mixed',NULL,true),(8,'mixed','value',true),"
                    + "(9,'scope','s',true),(10,'scope','s',false),(11,'distinct','d',true)");
        }
        return connection;
    }

    private static AuditRuleContext context(List<String> columns, Map<String, Object> config) {
        AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("quality_unique_fixture");
        context.setColumn(columns.getFirst());
        context.setColumns(columns.subList(1, columns.size()));
        context.setConfig(config);
        context.setWhereClause("active = true");
        return context;
    }

    private static void assertCounts(Connection connection, AuditSqlGenerator generator, AuditRuleContext context,
                                     long total, long errors) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(generator.buildCountSql(context))) {
            assertTrue(result.next());
            assertEquals(total, result.getLong("totalCount"));
            assertEquals(errors, result.getLong("errorCount"));
            assertFalse(result.next());
        }
    }

    private static List<Integer> errorIds(Connection connection, AuditSqlGenerator generator,
                                          AuditRuleContext context, int limit) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(generator.buildErrorDataSql(context, limit))) {
            assertEquals(4, result.getMetaData().getColumnCount(), "No synthetic counter may leak into problem rows");
            while (result.next()) ids.add(result.getInt("id"));
        }
        return ids.stream().sorted().toList();
    }
}
