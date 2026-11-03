package cn.iocoder.yudao.framework.dbswitch.dbcommon;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ClickhouseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseReadValues;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC 0.5 read semantics and same-row server validation on the isolated native server. */
@Timeout(90)
@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class ClickhouseReadValuesClickhouseTest {
    private final String name = "values70_" + UUID.randomUUID().toString().replace("-", "");
    private final String table = ClickhouseSql.table("default", name);
    @BeforeAll static void driver() throws Exception { Class.forName("com.clickhouse.jdbc.ClickHouseDriver"); }
    @AfterEach void cleanup() throws Exception { sql("DROP TABLE IF EXISTS " + table + " SYNC"); }

    @Test void nativeUnsignedBoxesBecomeExactDetachedDecimalsIncludingNullAndLabels() throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,u Nullable(UInt64)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + table + " VALUES(1,18446744073709551615),(2,0),(3,NULL)");
        var source = new TrackingSource(); var reader = new ClickhouseDatabaseOperator(source);
        try (var result = reader.queryTableData("default", name, List.of("id", "u"), List.of("id"))) {
            var rows = result.getResultset(); assertEquals(BigDecimal.class.getName(), rows.getMetaData().getColumnClassName(2));
            assertTrue(rows.next()); assertEquals(new BigDecimal("18446744073709551615"), rows.getObject(2));
            assertEquals(new BigDecimal("18446744073709551615"), rows.getObject("u"));
            assertTrue(rows.next()); assertEquals(BigDecimal.ZERO, rows.getObject(2));
            assertTrue(rows.next()); assertNull(rows.getObject("u")); assertTrue(rows.wasNull()); assertFalse(rows.next());
        }
        source.assertClosed();
    }

    @Test void stringAndNullableSpecialPhysicalNamesKeepUnicodeNulEscapesAndNull() throws Exception {
        String label = "中文`\\q"; String value = "中文\0\n\\'😃";
        sql("CREATE TABLE " + table + "(id Int64," + ClickhouseSql.quote(label) + " Nullable(String),plain String) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + table + " VALUES(1,unhex('" + hex(value) + "'),unhex('" + hex(value) + "')),(2,NULL,'')");
        var source = new TrackingSource(); var reader = new ClickhouseDatabaseOperator(source);
        try (var result = reader.queryTableData("default", name, List.of("id", label, "plain"), List.of("id"))) {
            var rows = result.getResultset(); assertTrue(rows.next()); assertEquals(value, rows.getObject(label)); assertEquals(value, rows.getString(3));
            assertTrue(rows.next()); assertNull(rows.getObject(label)); assertEquals("", rows.getString(3)); assertFalse(rows.next());
        }
        source.assertClosed();
    }

    @Test void emptyStringResultHasMetadataAndClosesHeaderAndDataQueries() throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,note Nullable(String)) ENGINE=MergeTree ORDER BY id");
        var source = new TrackingSource();
        try (var result = new ClickhouseDatabaseOperator(source).queryTableData("default", name, List.of("id", "note"))) {
            assertEquals(2, result.getResultset().getMetaData().getColumnCount()); assertFalse(result.getResultset().next());
        }
        source.assertClosed(); assertEquals(2, source.statementsOpened.get()); assertEquals(2, source.resultsOpened.get());
    }

    @Test void malformedStringInsertedAfterCleanPreflightStillFailsTheActualRead() throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,note Nullable(String)) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1,'valid')");
        var source = new TrackingSource(); var reader = new ClickhouseDatabaseOperator(source);
        assertEquals(2, reader.nativeMigrationColumns("default", name).size()); source.assertClosed();
        sql("INSERT INTO " + table + " VALUES(2,unhex('FF'))");
        assertThrows(Exception.class, () -> {
            try (var result = reader.queryTableData("default", name, List.of("id", "note"))) {
                while (result.getResultset().next()) result.getResultset().getString(2);
            }
        });
        source.assertClosed(); assertEquals("FF", single("SELECT hex(note) FROM " + table + " WHERE id=2"));
    }

    @Test void nativeTextPreflightRejectsAlreadyMalformedBytesAndReleasesCatalogResources() throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,note String) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1,unhex('FF'))");
        var source = new TrackingSource(); var reader = new ClickhouseDatabaseOperator(source);
        var failure = assertThrows(IllegalStateException.class, () -> reader.nativeMigrationColumns("default", name));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(table)); source.assertClosed();
    }

    @ParameterizedTest @ValueSource(strings = {"nan", "inf", "-inf"})
    void nonfiniteFloatInsertedAfterCleanPreflightStillFailsTheActualRead(String special) throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,value Nullable(Float64)) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1,0.5),(2,NULL)");
        var source = new TrackingSource(); var reader = new ClickhouseDatabaseOperator(source);
        assertEquals(2, reader.nativeMigrationColumns("default", name).size()); source.assertClosed();
        sql("INSERT INTO " + table + " VALUES(3,toFloat64('" + special + "'))");
        assertThrows(Exception.class, () -> {
            try (var result = reader.queryTableData("default", name, List.of("id", "value"))) {
                while (result.getResultset().next()) result.getResultset().getObject(2);
            }
        }); source.assertClosed();
    }

    @Test void preparedRangeBindsBothProbesAndRejectsMalformedSelectedRows() throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,note Nullable(String)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + table + " VALUES(1,'one'),(2,NULL),(3,'three')");
        var source = new TrackingSource(); var reader = new ClickhouseDatabaseOperator(source);
        var key = new ColumnDescription(); key.setFieldName("id"); key.setFieldType(Types.BIGINT);
        try (var result = reader.queryIncreaseTableData("default", name, List.of("id", "note"), key, "0", "2")) {
            var rows = result.getResultset(); assertTrue(rows.next()); assertEquals("one", rows.getString(2));
            assertTrue(rows.next()); assertNull(rows.getString(2)); assertFalse(rows.next());
        }
        source.assertClosed();
        sql("ALTER TABLE " + table + " UPDATE note=unhex('FF') WHERE id=3 SETTINGS mutations_sync=2");
        assertThrows(Exception.class, () -> {
            try (var result = reader.queryIncreaseTableData("default", name, List.of("id", "note"), key, "2", "3")) {
                while (result.getResultset().next()) result.getResultset().getObject(2);
            }
        }); source.assertClosed();
    }

    @Test void duplicateLabelsAreRejectedAndFailedHeaderNeverLeaksItsBorrowedConnection() throws Exception {
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT toString(1) AS x,toString(1) AS x")) {
            assertThrows(SQLException.class, () -> ClickhouseReadValues.guardedSql("SELECT toString(1) AS x,toString(1) AS x", rows.getMetaData()));
        }
        var source = new TrackingSource();
        assertThrows(IllegalStateException.class, () -> new ClickhouseDatabaseOperator(source).queryTableData("SELECT * FROM " + table));
        source.assertClosed(); assertEquals(1, source.statementsOpened.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void schemaChangedAfterNumericHeaderCannotReturnReplacementDecodedStringAndClosesEveryOwnedResource(boolean prepared) throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,payload Int64) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1,1)");
        var source = new TrackingSource(); source.afterHeader = () -> sql(
                "ALTER TABLE " + table + " MODIFY COLUMN payload String SETTINGS mutations_sync=2",
                "ALTER TABLE " + table + " UPDATE payload=unhex('FF') WHERE id=1 SETTINGS mutations_sync=2");
        var reader = new ClickhouseDatabaseOperator(source);
        var key = new ColumnDescription(); key.setFieldName("id"); key.setFieldType(Types.BIGINT);
        assertThrows(IllegalStateException.class, () -> {
            if (prepared) {
                try (var result = reader.queryIncreaseTableData("default", name, List.of("id", "payload"), key, "0", "2")) {
                    while (result.getResultset().next()) result.getResultset().getObject(2);
                }
            } else {
                try (var result = reader.queryTableData("default", name, List.of("id", "payload"))) {
                    while (result.getResultset().next()) result.getResultset().getObject(2);
                }
            }
        });
        assertTrue(source.changed.get(), "a real ALTER ran after the zero-row header and before the actual query");
        assertEquals("FF", single("SELECT hex(payload) FROM " + table)); source.assertClosed();
        assertEquals(2, source.statementsOpened.get()); assertEquals(2, source.resultsOpened.get());
    }

    @Test void nullCursorRequestIsRejectedBeforeBorrowingAnOwnedConnection() {
        var source = new TrackingSource();
        assertThrows(IllegalArgumentException.class, () -> new ClickhouseDatabaseOperator(source).openCursor(null));
        assertEquals(0, source.connectionsOpened.get()); source.assertClosed();
    }

    @Test void columnAddedAfterNumericStarHeaderCannotBypassItsTextGuardOrReturnAnUnplannedProjection() throws Exception {
        sql("CREATE TABLE " + table + "(id Int64,payload Int64) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1,1)");
        var source = new TrackingSource(); source.afterHeader = () -> sql("ALTER TABLE " + table + " ADD COLUMN late String DEFAULT unhex('FF')");
        assertThrows(IllegalStateException.class, () -> {
            try (var result = new ClickhouseDatabaseOperator(source).queryTableData("SELECT * FROM " + table)) {
                while (result.getResultset().next()) result.getResultset().getObject(3);
            }
        });
        assertTrue(source.changed.get()); assertEquals("FF", single("SELECT hex(late) FROM " + table));
        source.assertClosed(); assertEquals(2, source.resultsOpened.get());
    }

    private static String hex(String value) { return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)); }
    private static Connection open() throws SQLException { return DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", ""); }
    private static void sql(String... commands) throws SQLException {
        try (var connection = open(); var statement = connection.createStatement()) { statement.setQueryTimeout(30); for (var sql : commands) statement.execute(sql); }
    }
    private static String single(String sql) throws SQLException {
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); var value = rows.getString(1); assertFalse(rows.next()); return value;
        }
    }
    private static final class TrackingSource extends AbstractDataSource {
        @FunctionalInterface interface Mutation { void run() throws SQLException; }
        Mutation afterHeader;
        final java.util.concurrent.atomic.AtomicBoolean headerSeen = new java.util.concurrent.atomic.AtomicBoolean(), changed = new java.util.concurrent.atomic.AtomicBoolean();
        final AtomicInteger connectionsOpened = new AtomicInteger(), connectionsClosed = new AtomicInteger();
        final AtomicInteger statementsOpened = new AtomicInteger(), statementsClosed = new AtomicInteger();
        final AtomicInteger resultsOpened = new AtomicInteger(), resultsClosed = new AtomicInteger();
        @Override public Connection getConnection() throws SQLException { connectionsOpened.incrementAndGet(); return tracked(open(), Connection.class, connectionsClosed); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        private <T> T tracked(T delegate, Class<T> contract, AtomicInteger closedCount) {
            return tracked(delegate, contract, closedCount, null);
        }
        private <T> T tracked(T delegate, Class<T> contract, AtomicInteger closedCount, String preparedSql) {
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
                if (method.getName().equals("close") && closed.compareAndSet(false, true)) closedCount.incrementAndGet();
                String sql = method.getName().equals("executeQuery") ? preparedSql != null ? preparedSql
                        : args != null && args.length == 1 && args[0] instanceof String command ? command : null : null;
                boolean header = sql != null && sql.startsWith("SELECT * FROM (") && sql.contains(" AS dbswitch_value_source WHERE 1=0");
                if (sql != null && !header && afterHeader != null && headerSeen.get() && changed.compareAndSet(false, true)) afterHeader.run();
                Object result;
                try { result = method.invoke(delegate, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                if (header) headerSeen.set(true);
                if (result instanceof PreparedStatement statement && (method.getName().equals("prepareStatement"))) {
                    statementsOpened.incrementAndGet(); return tracked(statement, PreparedStatement.class, statementsClosed, (String) args[0]);
                }
                if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                    statementsOpened.incrementAndGet(); return tracked(statement, Statement.class, statementsClosed);
                }
                if (result instanceof ResultSet rows && method.getName().equals("executeQuery")) {
                    resultsOpened.incrementAndGet(); return tracked(rows, ResultSet.class, resultsClosed);
                }
                return result;
            }));
        }
        void assertClosed() {
            assertEquals(connectionsOpened.get(), connectionsClosed.get(), "owned connections");
            assertEquals(statementsOpened.get(), statementsClosed.get(), "header/data statements");
            assertEquals(resultsOpened.get(), resultsClosed.get(), "header/data results");
        }
    }
}
