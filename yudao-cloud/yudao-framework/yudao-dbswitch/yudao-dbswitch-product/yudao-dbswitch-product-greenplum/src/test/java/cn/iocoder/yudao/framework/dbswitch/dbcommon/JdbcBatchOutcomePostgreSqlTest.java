package cn.iocoder.yudao.framework.dbswitch.dbcommon;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 调用真实 PostgreSQL 后在 JDBC 返回边界丢失响应，不用 mock 数据证明事务效果。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class JdbcBatchOutcomePostgreSqlTest {
    @Test
    void successfulIncrementalCrudKeepsParameterOrderAndCommittedVisibility() throws Exception {
        String schema = "txoutcome_" + UUID.randomUUID().toString().replace("-", "");
        try (var pool = pool(); var control = pool.getConnection(); var sql = control.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".rows(id bigint PRIMARY KEY, note text)");
                var synchronizer = new PostgresqlDatabaseSyncImpl(pool);
                synchronizer.prepare(schema, "rows", List.of("id", "note"), List.of("id"));
                assertEquals(2, synchronizer.executeInsert(List.of(new Object[]{1L, "one"}, new Object[]{2L, "two"})));
                assertEquals(1, synchronizer.executeUpdate(List.<Object[]>of(new Object[]{1L, "updated"})));
                assertEquals(1, synchronizer.executeDelete(List.<Object[]>of(new Object[]{2L, "ignored"})));
                // 另一个自动提交连接读取，不能靠写连接自己可见来证明正常提交。
                try (var rs = sql.executeQuery("SELECT id, note FROM " + schema + ".rows ORDER BY id")) {
                    assertTrue(rs.next()); assertEquals(1L, rs.getLong(1)); assertEquals("updated", rs.getString(2));
                    assertFalse(rs.next());
                }
                try (var clean = pool.getConnection()) { assertTrue(clean.getAutoCommit()); }
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"writer", "synchronizer"})
    void failedRealBatchEvictsThePhysicalHikariSessionBeforeReuse(String path) throws Exception {
        String schema = "txoutcome_" + UUID.randomUUID().toString().replace("-", "");
        try (var pool = pool(); var control = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), "");
                var sql = control.createStatement()) {
            pool.setMaximumPoolSize(1);
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY CHECK (id <> 2))");
                int before;
                try (var borrowed = pool.getConnection()) { before = backendPid(borrowed); }
                assertThrows(UnconfirmedWriteException.class,
                        () -> JdbcBatchOutcomeTest.execute(path, pool, "INSERT INTO " + schema + ".rows VALUES (?)"));
                try (var rs = sql.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
                    assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
                }
                try (var replacement = pool.getConnection(); var next = replacement.createStatement()) {
                    assertNotEquals(before, backendPid(replacement), "异常物理会话不能重新回到池中");
                    assertTrue(replacement.getAutoCommit());
                    assertEquals(1, next.executeUpdate("INSERT INTO " + schema + ".rows VALUES (3)"));
                }
                try (var rs = sql.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = " + before)) {
                    assertTrue(rs.next()); assertEquals(0, rs.getInt(1), "旧数据库会话必须退出");
                }
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    private static HikariDataSource pool() {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        var pool = new HikariDataSource(); pool.setJdbcUrl(url + "?socketTimeout=5");
        pool.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        return pool;
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"writer,rollback", "synchronizer,rollback", "writer,commit", "synchronizer,commit"})
    void lostRepliesCannotTriggerReplayOrImplicitCommit(String path, String fault) throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        String schema = "txoutcome_" + UUID.randomUUID().toString().replace("-", "");
        var batchCalls = new AtomicInteger(); var rowCalls = new AtomicInteger(); var resets = new AtomicInteger();
        try (var control = DriverManager.getConnection(url, user, ""); var sql = control.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".rows(id integer)");
            try (var physical = DriverManager.getConnection(url + "?socketTimeout=5", user, "")) {
                var wrapped = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) resets.incrementAndGet();
                            if (method.getName().equals("rollback") && fault.equals("rollback")) {
                                throw new SQLException("synthetic lost rollback request");
                            }
                            if (method.getName().equals("commit") && fault.equals("commit")) {
                                physical.commit(); // 已实际提交，但应用未收到确认；再 rollback 也无法撤销。
                                throw new SQLException("synthetic lost commit response");
                            }
                            Object result = invoke(method, physical, args);
                            if (method.getName().equals("prepareStatement")) {
                                var statement = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                            if (m.getName().equals("executeUpdate")) rowCalls.incrementAndGet();
                                            if (m.getName().equals("executeBatch")) {
                                                batchCalls.incrementAndGet();
                                                var counts = statement.executeBatch();
                                                if (fault.equals("rollback")) throw new BatchUpdateException(
                                                        "synthetic lost batch response", counts);
                                                return counts;
                                            }
                                            return invoke(m, statement, a);
                                        });
                            }
                            return result;
                        });
                var source = mock(DataSource.class); when(source.getConnection()).thenReturn(wrapped);
                RuntimeException failure = null;
                try { JdbcBatchOutcomeTest.execute(path, source, "INSERT INTO " + schema + ".rows VALUES (?)"); }
                catch (RuntimeException expected) { failure = expected; }
                long actual;
                try (var rs = sql.executeQuery("SELECT count(*) FROM " + schema + ".rows")) { rs.next(); actual = rs.getLong(1); }
                var observedFailure = failure;
                assertAll(() -> assertInstanceOf(UnconfirmedWriteException.class, observedFailure),
                        () -> assertEquals(fault.equals("commit") ? 2 : 0, actual, "原失败不得被重投或恢复 autoCommit 意外提交"),
                        () -> assertEquals(1, batchCalls.get()), () -> assertEquals(0, rowCalls.get()),
                        () -> assertEquals(0, resets.get()));
            } finally {
                assertTrue(schema.matches("txoutcome_[a-f0-9]{32}"));
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
