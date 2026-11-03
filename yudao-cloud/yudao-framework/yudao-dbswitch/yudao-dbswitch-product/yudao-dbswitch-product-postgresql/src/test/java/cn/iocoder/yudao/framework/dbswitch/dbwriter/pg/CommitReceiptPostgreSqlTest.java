package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SingletonObject;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class CommitReceiptPostgreSqlTest {
    HikariDataSource pool;
    Connection control;
    String schema;

    @BeforeEach void setup() throws Exception {
        pool = new HikariDataSource(); pool.setJdbcUrl(System.getProperty("datahub.test.postgres.url") + "?socketTimeout=5");
        pool.setUsername("dataforge_test"); pool.setMaximumPoolSize(3); pool.setMinimumIdle(0);
        control = pool.getConnection(); schema = "receipt_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("CREATE TABLE " + schema + ".rows(id int CHECK (id > 0))");
    }
    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();
        try {
            if (control != null) {
                assertTrue(schema.matches("receipt_[a-f0-9]{32}")); sql("DROP SCHEMA " + schema + " CASCADE");
                control.close();
            }
        } finally { if (pool != null) pool.close(); }
    }

    @Test void actualCommittedXidIsBoundToWriteButNotAutomaticallyVisibleToOldReadSnapshot() throws Exception {
        var writer = writer();
        try (var reader = pool.getConnection()) {
            var dialect = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
            String xid;
            try (var snapshot = dialect.openReadSnapshot(reader, 5)) {
                var result = writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1}));
                assertEquals(1, result.acknowledgedRows()); assertNotNull(result.receipt());
                xid = result.receipt().transactionId();
                try (var statement = control.createStatement(); var rows = statement.executeQuery(
                        "SELECT xmin::text FROM " + schema + ".rows")) {
                    assertTrue(rows.next()); assertEquals(xid, rows.getString(1));
                }
                assertEquals("committed", status(xid));
                assertFalse(visible(reader, xid), "提交成功不等于更早的读取快照可见");
            }
            try (var next = dialect.openReadSnapshot(reader, 5)) { assertTrue(visible(reader, xid)); }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void receiptIncludesActualTargetStorageIdentity(boolean insert) throws Exception {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(pool, ProductTypeEnum.POSTGRESQL, insert ? cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT : cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
        writer.prepareWrite(schema, "rows", List.of("id"));
        var receipt = writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1})).receipt();
        assertNotNull(receipt);
        assertTrue(SingletonObject.OBJECT_MAPPER.writeValueAsString(receipt)
                .contains("\"relationFingerprint\":\""), "事务回执还必须绑定实际目标表的物理身份");
    }

    @ParameterizedTest @ValueSource(strings={"batch-lost","commit-lost","commit-abort","close","restore",
            "status","mid-commit","mid-rollback","interrupt"})
    void unknownOrChangedTransactionNeverPublishesReceiptOrReplays(String fault) throws Exception {
        var writer = writer(); var batches = new AtomicInteger(); var singles = new AtomicInteger();
        try (var physical = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), "")) {
            Connection wrapped = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("commit") && fault.equals("commit-lost")) {
                            physical.commit(); throw new SQLException("fixture-private-commit");
                        }
                        if (name.equals("commit") && fault.equals("commit-abort")) { physical.rollback(); return null; }
                        if (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]) && fault.equals("restore"))
                            throw new SQLException("fixture-private-restore");
                        if (name.equals("close") && fault.equals("close")) throw new SQLException("fixture-private-close");
                        if (name.equals("prepareStatement") && ((String) args[0]).contains("pg_xact_status") && fault.equals("status"))
                            throw new SQLException("fixture-private-status");
                        Object value = invoke(method, physical, args);
                        if (name.equals("prepareStatement") && ((String) args[0]).startsWith("INSERT")) {
                            PreparedStatement statement = (PreparedStatement) value;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                        if (m.getName().equals("executeUpdate")) singles.incrementAndGet();
                                        Object result = invoke(m, statement, a);
                                        if (m.getName().equals("executeBatch")) {
                                            batches.incrementAndGet();
                                            if (fault.equals("batch-lost")) throw new SQLException("fixture-private-batch");
                                            if (fault.equals("mid-commit")) physical.commit();
                                            if (fault.equals("mid-rollback")) physical.rollback();
                                            if (fault.equals("interrupt")) Thread.currentThread().interrupt();
                                        }
                                        return result;
                                    });
                        }
                        return value;
                    });
            var source = mock(DataSource.class); when(source.getConnection()).thenReturn(wrapped);
            ReflectionTestUtils.setField(writer, "dataSource", source);
            var failure = assertThrows(UnconfirmedWriteException.class,
                    () -> writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1})));
            Thread.interrupted();
            assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
            assertFalse(failure.getMessage().contains("private"));
            assertEquals(1, batches.get()); assertEquals(0, singles.get());
            long expected = List.of("commit-lost","close","restore","status","mid-commit").contains(fault) ? 1 : 0;
            assertEquals(expected, count());
        }
    }

    @Test void failedBatchHasNoReceiptAndEvictsTheHikariSession() throws Exception {
        var writer = writer(); int pid;
        try (var before = pool.getConnection()) { pid = pid(before); }
        assertThrows(UnconfirmedWriteException.class,
                () -> writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{-1})));
        assertEquals(0, count());
        try (var after = pool.getConnection()) { assertNotEquals(pid, pid(after)); assertTrue(after.getAutoCommit()); }
    }

    @Test void emptyBatchDoesNotAllocateTransactionOrBorrowConnection() {
        var source = mock(DataSource.class);
        var writer = new PostgresInsertWriterImpl(source);
        assertNull(writer.writeWithReceipt(List.of("id"), List.of()).receipt());
        verifyNoInteractions(source);
    }

    @Test void separateBatchesHaveDistinctXidsAndPriorCommitSurvivesLaterFailure() throws Exception {
        var writer = writer();
        var first = writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1}));
        var second = writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{2}));
        assertNotEquals(first.receipt().transactionId(), second.receipt().transactionId());
        assertEquals(first.receipt().routeFingerprint(), second.receipt().routeFingerprint());
        assertThrows(UnconfirmedWriteException.class,
                () -> writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{-1})));
        assertEquals(2, count());
    }

    @Test void unverifiedVersionRetainsOrdinaryInsertWithoutReceipt() throws Exception {
        var writer = writer();
        try (var physical = pool.getConnection()) {
            var metadata = mock(DatabaseMetaData.class);
            when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL"); when(metadata.getDatabaseMajorVersion()).thenReturn(15);
            var wrapped = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (p,m,a) -> m.getName().equals("getMetaData") ? metadata : invoke(m,physical,a));
            var source = mock(DataSource.class); when(source.getConnection()).thenReturn(wrapped);
            ReflectionTestUtils.setField(writer, "dataSource", source);
            assertNull(writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1})).receipt());
            assertEquals(1, count());
        }
    }

    private cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter writer() {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(pool, ProductTypeEnum.POSTGRESQL, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite(schema, "rows", List.of("id")); return writer;
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
    private long count() throws Exception {
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private String status(String xid) throws Exception {
        try (var statement = control.prepareStatement("SELECT pg_catalog.pg_xact_status(?::xid8)")) {
            statement.setString(1, xid); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }
    private static boolean visible(Connection connection, String xid) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_catalog.pg_visible_in_snapshot(?::xid8, pg_catalog.pg_current_snapshot())")) {
            statement.setString(1, xid); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getBoolean(1); }
        }
    }
    private static int pid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
}
