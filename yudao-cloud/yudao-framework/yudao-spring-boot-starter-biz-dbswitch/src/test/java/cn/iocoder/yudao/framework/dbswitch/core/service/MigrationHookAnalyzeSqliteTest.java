package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Native SQLite statistics and SQLITE_INTERRUPT; no PostgreSQL/H2 substitute. */
@Timeout(30)
class MigrationHookAnalyzeSqliteTest {
    @TempDir Path directory;

    @Test void namedAnalyzePublishesOnlyTheTargetsStatisticsAndKeepsTheBorrowedTransaction() throws Exception {
        try (var drivers = fixture(); var connection = open(drivers)) {
            prepare(connection);
            execute(connection, "CREATE TABLE untouched(id INTEGER)");
            execute(connection, "CREATE INDEX untouched_ix ON untouched(id)");
            analyze(connection);
            assertEquals("1000 1", statistics(connection));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM sqlite_stat1 WHERE tbl='untouched'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            addRows(connection, 1001, 20000);
            connection.setAutoCommit(false);
            analyze(connection);
            assertEquals("20000 1", statistics(connection));
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals("1000 1", statistics(connection));
            assertFalse(connection.isClosed());
        }
    }

    @Test void nativeProgressInterruptRollsBackAnalyzeStatisticsAndTheConnectionCanRetry() throws Exception {
        try (var drivers = fixture(); var connection = open(drivers)) {
            prepare(connection);
            analyze(connection);
            addRows(connection, 1001, 20000);
            Object physical = DriverNativeAccess.unwrap(connection, "org.sqlite.SQLiteConnection");
            Class<?> progressType = Class.forName("org.sqlite.ProgressHandler", true,
                    physical.getClass().getClassLoader());
            var callbacks = new AtomicInteger();
            // Only the progress callback is supplied by Mockito. SQLite performs real ANALYZE
            // and its native VM raises SQLITE_INTERRUPT when this callback returns nonzero.
            Object progress = Mockito.mock(progressType, invocation -> {
                if (invocation.getMethod().getName().equals("progress"))
                    return callbacks.incrementAndGet() >= 30 ? 1 : 0;
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            progressType.getMethod("setHandler", Connection.class, int.class, progressType)
                    .invoke(null, physical, 100, progress);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                var plan = SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3, "ANALYZE main.fixture");
                SQLException failure = assertThrows(SQLException.class,
                        () -> statement.execute(plan.steps().getFirst().sql()));
                assertEquals(9, failure.getErrorCode());
                assertTrue(callbacks.get() >= 30);
            } finally {
                progressType.getMethod("clearHandler", Connection.class).invoke(null, physical);
            }
            assertTrue(connection.getAutoCommit());
            assertTrue(connection.isValid(1));
            assertEquals("1000 1", statistics(connection));
            analyze(connection);
            assertEquals("20000 1", statistics(connection));
        }
    }

    private void prepare(Connection connection) throws Exception {
        execute(connection, "CREATE TABLE fixture(id INTEGER)");
        execute(connection, "CREATE INDEX fixture_ix ON fixture(id)");
        addRows(connection, 1, 1000);
    }

    private void addRows(Connection connection, int first, int last) throws Exception {
        execute(connection, "WITH RECURSIVE seed(x) AS (SELECT " + first + " UNION ALL SELECT x+1 FROM seed WHERE x<"
                + last + ") INSERT INTO fixture SELECT x FROM seed");
    }

    private String statistics(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT stat FROM sqlite_stat1 WHERE tbl='fixture' AND idx='fixture_ix'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void analyze(Connection connection) {
        var result = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLITE3).executePlan(connection,
                SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3, "ANALYZE main.fixture"),
                List.of(), 1, null, ignored -> { });
        assertTrue(result.getSuccess(), result::getErrorMsg);
        assertFalse(result.getResults().getFirst().getIfQuery());
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private VerifiedDriverTestHandle fixture() {
        String supplied = System.getProperty("datahub.test.sqlite.driver");
        return supplied == null ? null : new VerifiedDriverTestHandle(Path.of(supplied), ProductTypeEnum.SQLITE3);
    }

    private Connection open(VerifiedDriverTestHandle drivers) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("hook-analyze.db");
        return drivers == null ? DriverManager.getConnection(url)
                : drivers.dataSource(url, "org.sqlite.JDBC", null, null, new Properties()).getConnection();
    }
}
