package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationHookAnalyzePostgreSqlTest {
    @Test void namedAnalyzeUpdatesOnlyTheTargetsStatisticsAndRespectsTheBorrowedTransaction() throws Exception {
        String table = tableName();
        String untouched = table + "_other";
        try (var connection = open()) {
            create(connection, table, 1000);
            create(connection, untouched, 1);
            try {
                assertEquals(-1, tuples(connection, table));
                analyze(connection, table);
                assertEquals(1000, tuples(connection, table));
                String originalStatistics = histogram(connection, table);
                assertEquals(-1, tuples(connection, untouched));
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT count(*) FROM pg_stats WHERE schemaname='public' AND tablename='" + table + "'")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
                execute(connection, "INSERT INTO public." + table + " SELECT generate_series(1001,1500)");
                connection.setAutoCommit(false);
                analyze(connection, table);
                assertEquals(1500, tuples(connection, table));
                assertNotEquals(originalStatistics, histogram(connection, table));
                connection.rollback();
                connection.setAutoCommit(true);
                // pg_statistic is transactional. pg_class.reltuples is an optimizer hint
                // updated in place by PostgreSQL, and need not revert when ANALYZE rolls back.
                assertEquals(originalStatistics, histogram(connection, table));
            } finally {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true);
                execute(connection, "DROP TABLE IF EXISTS public." + table + ",public." + untouched);
            }
        }
    }

    @Test void nativeStatementTimeoutCancelsBlockedAnalyzeWithoutPublishingStatistics() throws Exception {
        String table = tableName();
        try (var connection = open()) {
            create(connection, table, 100);
            try {
                analyze(connection, table);
                execute(connection, "INSERT INTO public." + table + " SELECT generate_series(101,200)");
                try (var lock = open(); var canceled = open()) {
                    lock.setAutoCommit(false);
                    execute(lock, "LOCK TABLE public." + table + " IN ACCESS EXCLUSIVE MODE");
                    try (var statement = canceled.createStatement()) {
                        statement.setQueryTimeout(1);
                        var plan = SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL, "ANALYZE public." + table);
                        SQLException failure = assertThrows(SQLException.class,
                                () -> statement.execute(plan.steps().getFirst().sql()));
                        assertEquals("57014", failure.getSQLState());
                        assertTrue(canceled.getAutoCommit());
                        assertTrue(canceled.isValid(1));
                    } finally { lock.rollback(); }
                }
                assertEquals(100, tuples(connection, table));
                analyze(connection, table);
                assertEquals(200, tuples(connection, table));
            } finally { execute(connection, "DROP TABLE IF EXISTS public." + table); }
        }
    }

    private void analyze(Connection connection, String table) {
        var result = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).executePlan(connection,
                SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL, "ANALYZE public." + table),
                List.of(), 1, null, ignored -> { });
        assertTrue(result.getSuccess(), result::getErrorMsg);
        assertFalse(result.getResults().getFirst().getIfQuery());
    }

    private void create(Connection connection, String table, int count) throws Exception {
        execute(connection, "CREATE TABLE public." + table + "(id integer) WITH (autovacuum_enabled=false)");
        execute(connection, "INSERT INTO public." + table + " SELECT generate_series(1," + count + ")");
    }

    private long tuples(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT reltuples::bigint FROM pg_class WHERE oid='public." + table + "'::regclass")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private String histogram(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT histogram_bounds::text FROM pg_stats WHERE schemaname='public' AND tablename='"
                        + table + "' AND attname='id'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String tableName() { return "hook_analyze_" + UUID.randomUUID().toString().replace("-", ""); }
    private Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dbswitch_test"), "");
    }
}
