package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCopyWriterImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 独立连接观察真实 COPY 副作用；不使用 mock 行数代替数据库提交行为。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class CopyOutcomePostgreSqlTest {
    String schema;
    HikariDataSource pool;
    Connection control;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        pool = new HikariDataSource(); pool.setJdbcUrl(url + "?socketTimeout=5"); pool.setUsername(user);
        pool.setMaximumPoolSize(1); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        control = DriverManager.getConnection(url, user, "");
        schema = "copyoutcome_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("CREATE TABLE " + schema + ".rows(id integer)");
    }

    @AfterEach void cleanup() throws Exception {
        if (pool != null) pool.close();
        if (control != null) {
            try {
                assertTrue(schema.matches("copyoutcome_[a-f0-9]{32}"));
                sql("DROP SCHEMA " + schema + " CASCADE");
            } finally { control.close(); }
        }
    }

    @Test void malformedLaterRowCannotCommitTheEarlierPrefix() throws Exception {
        var writer = writer();
        RuntimeException failure = null;
        try { writer.write(List.of("id"), List.of(new Object[]{1}, new Object[]{2, 3})); }
        catch (RuntimeException caught) { failure = caught; }
        var observed = failure;
        long stored = count();
        assertAll(() -> assertInstanceOf(IllegalArgumentException.class, observed),
                () -> assertEquals(0, stored, "参数不合法时不能由关闭 COPY 提交前面合法的行"));
    }

    @Test void databaseReportedShortCopyCannotClaimTheInputCount() throws Exception {
        sql("CREATE FUNCTION " + schema + ".skip_second() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.id = 2 THEN RETURN NULL; END IF; RETURN NEW; END'");
        sql("CREATE TRIGGER skip_second BEFORE INSERT ON " + schema + ".rows FOR EACH ROW EXECUTE FUNCTION " + schema + ".skip_second()");
        var writer = writer();
        RuntimeException failure = null;
        try { writer.write(List.of("id"), List.of(new Object[]{1}, new Object[]{2})); }
        catch (RuntimeException caught) { failure = caught; }
        var observed = failure;
        long stored = count();
        assertAll(() -> assertInstanceOf(UnconfirmedWriteException.class, observed),
                () -> assertEquals(0, stored, "COPY 真实回执不匹配时不得提交部分数据"));
    }

    @Test void successfulBatchesCommitExactContentsAndReturnReusableConnections() throws Exception {
        var writer = writer();
        assertEquals(2, writer.write(List.of("id"), List.of(new Object[]{1}, new Object[]{2})));
        assertEquals(1, writer.write(List.of("id"), List.<Object[]>of(new Object[]{3})));
        assertEquals(0, writer.write(List.of("id"), List.of()));
        try (var statement = control.createStatement(); var result = statement.executeQuery(
                "SELECT array_agg(id ORDER BY id)::text FROM " + schema + ".rows")) {
            assertTrue(result.next()); assertEquals("{1,2,3}", result.getString(1));
        }
        try (var connection = pool.getConnection()) { assertTrue(connection.getAutoCommit()); }
    }

    @Test void conversionFailureBeyondCopyBufferCapacityIsRejectedBeforeSending() throws Exception {
        var writer = writer();
        int oldPid = backendPid();
        var rows = new ArrayList<Object[]>();
        // 超过 PgBinaryWriter 的 64KiB 缓冲，仍需全批预转换后才开始 COPY。
        for (int i = 0; i < 20000; i++) rows.add(new Object[]{i});
        rows.add(new Object[]{"not-an-integer"});
        var failure = assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("id"), rows));
        assertNull(failure.getCause()); assertEquals(0, count());
        assertEquals(oldPid, backendPid(), "预转换失败不应借出或淘汰数据库会话");
        assertEquals(1, writer.write(List.of("id"), List.<Object[]>of(new Object[]{7})));
        assertEquals(1, count());
    }

    @Test void serverConstraintFailureRollsBackAndDoesNotReplay() throws Exception {
        sql("ALTER TABLE " + schema + ".rows ADD CONSTRAINT positive_id CHECK(id > 0)");
        var writer = writer();
        int oldPid = backendPid();
        assertThrows(UnconfirmedWriteException.class, () -> writer.write(List.of("id"),
                List.of(new Object[]{1}, new Object[]{-1})));
        assertEquals(0, count()); assertNotEquals(oldPid, backendPid());
    }

    @ParameterizedTest
    @CsvSource({"commit-before,0", "commit-after,2", "copy-end-before,0", "copy-end-after,0",
            "restore-after,2", "close-after,2", "copy-cancel,0"})
    void lostRepliesStayUnknownWithoutReplayingTheBatch(String fault, long expectedStored) throws Exception {
        var commits = new AtomicInteger(); var copies = new AtomicInteger();
        var injected = mock(DataSource.class);
        when(injected.getConnection()).thenAnswer(invocation -> {
            Connection actual = pool.getConnection();
            PGConnection pg = actual.unwrap(PGConnection.class);
            var copyManager = mock(CopyManager.class);
            when(copyManager.copyIn(anyString())).thenAnswer(copyInvocation -> {
                copies.incrementAndGet();
                CopyIn operation = pg.getCopyAPI().copyIn(copyInvocation.getArgument(0));
                return Proxy.newProxyInstance(CopyIn.class.getClassLoader(), new Class<?>[]{CopyIn.class},
                        (proxy, method, args) -> {
                            if ((method.getName().equals("endCopy") && (fault.equals("copy-end-before") || fault.equals("copy-cancel")))
                                    || (method.getName().equals("cancelCopy") && fault.equals("copy-cancel"))) throw lostReply();
                            Object value = invoke(operation, method, args);
                            if (method.getName().equals("endCopy") && fault.equals("copy-end-after")) throw lostReply();
                            return value;
                        });
            });
            PGConnection wrappedPg = (PGConnection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(),
                    new Class<?>[]{PGConnection.class}, (proxy, method, args) ->
                            method.getName().equals("getCopyAPI") ? copyManager : invoke(pg, method, args));
            var committed = new AtomicBoolean();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("unwrap") && args[0] == PGConnection.class) return wrappedPg;
                        if (name.equals("isWrapperFor") && args[0] == PGConnection.class) return true;
                        if (name.equals("commit")) {
                            commits.incrementAndGet();
                            if (fault.equals("commit-before")) throw lostReply();
                        }
                        Object value = invoke(actual, method, args);
                        if (name.equals("commit")) {
                            committed.set(true);
                            if (fault.equals("commit-after")) throw lostReply();
                        }
                        if (committed.get() && ((name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])
                                && fault.equals("restore-after")) || (name.equals("close") && fault.equals("close-after")))) {
                            throw lostReply();
                        }
                        return value;
                    });
        });
        var writer = new PostgresCopyWriterImpl(injected);
        writer.prepareWrite(schema, "rows", List.of("id"));
        var failure = assertThrows(UnconfirmedWriteException.class, () -> writer.write(List.of("id"),
                List.of(new Object[]{1}, new Object[]{2})));
        assertAll(() -> assertEquals(expectedStored, count()), () -> assertEquals(1, copies.get()),
                () -> assertEquals(fault.startsWith("copy-") ? 0 : 1, commits.get()),
                () -> assertNull(failure.getCause()), () -> assertEquals(0, failure.getSuppressed().length));
    }

    private static SQLException lostReply() { return new SQLException("private injected reply loss"); }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private int backendPid() throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }

    private PostgresCopyWriterImpl writer() {
        var writer = new PostgresCopyWriterImpl(pool);
        writer.prepareWrite(schema, "rows", List.of("id")); return writer;
    }

    private long count() throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }

    private void sql(String sql) throws Exception {
        try (var statement = control.createStatement()) { statement.execute(sql); }
    }
}
