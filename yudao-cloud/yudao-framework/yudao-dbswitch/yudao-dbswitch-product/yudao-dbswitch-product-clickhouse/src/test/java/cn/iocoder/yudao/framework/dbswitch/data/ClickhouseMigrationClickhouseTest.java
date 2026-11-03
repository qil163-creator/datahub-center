package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ClickhouseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.clickhouse.ClickhouseDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse.ClickhouseWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class ClickhouseMigrationClickhouseTest {
    private static final List<String> FIELDS = List.of("id", "amount", "note");
    private static final BigDecimal AMOUNT = new BigDecimal("12345678901234567890123456789.123456789");
    private final String source = "ch_source_" + UUID.randomUUID().toString().replace("-", "");
    private final String target = "ch_target_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll static void driver() throws Exception { Class.forName("com.clickhouse.jdbc.ClickHouseDriver"); }
    @BeforeEach void create() throws Exception {
        sql("CREATE TABLE " + name(source) + " (id Int64,amount Nullable(Decimal(38,9)),note Nullable(String)) ENGINE=MergeTree ORDER BY id");
    }
    @AfterEach void cleanup() throws Exception {
        sql("DROP TABLE IF EXISTS " + name(source) + " SYNC");
        sql("DROP TABLE IF EXISTS " + name(target) + " SYNC");
    }

    @Test void nativeInsertAndReadKeepNegativeIntegerExactDecimalNullAndText() throws Exception {
        var ds = source(false, new AtomicBoolean());
        var writer = new ClickhouseWriterImpl(ds); writer.prepareWrite("default", source, FIELDS);
        assertEquals(2, writer.write(FIELDS, List.of(new Object[]{-1L, AMOUNT, "中文'\\\n"}, new Object[]{2L, null, ""})));
        var reader = new ClickhouseDatabaseOperator(ds); reader.setFetchSize(2);
        try (var result = reader.queryTableData("default", source, FIELDS, List.of("id"))) {
            var rows = result.getResultset();
            assertTrue(rows.next()); assertEquals(-1, rows.getLong(1)); assertEquals(AMOUNT, rows.getBigDecimal(2));
            assertEquals("中文'\\\n", rows.getString(3)); assertTrue(rows.next()); assertNull(rows.getBigDecimal(2));
            assertEquals("", rows.getString(3)); assertFalse(rows.next());
        }
    }

    @Test void nativeMutationWaitsForVisibleUpdateAndDeletionAndCountsConfirmedKeys() throws Exception {
        seedSource();
        var sync = sync(source, source(false, new AtomicBoolean()));
        assertEquals(1, sync.executeUpdate(List.<Object[]>of(new Object[]{1L, AMOUNT, "更新"})));
        assertEquals(1, sync.executeDelete(List.<Object[]>of(new Object[]{2L, null, "second"})));
        assertEquals(1, sync.executeInsert(List.<Object[]>of(new Object[]{4L, null, "new"})));
        assertRows(source, List.of("1|" + AMOUNT.toPlainString() + "|更新", "4|NULL|new"));
        try (var connection = open(); var statement = connection.prepareStatement(
                "SELECT count() FROM system.mutations WHERE database='default' AND table=? AND is_done=0")) {
            statement.setString(1, source);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
        }
    }

    @Test void duplicateSparseKeysAreRejectedBeforeAnyMutation() throws Exception {
        sql("INSERT INTO " + name(source) + " VALUES(1,NULL,'a'),(1,NULL,'b')");
        assertThrows(IllegalStateException.class, () -> sync(source, source(false, new AtomicBoolean())));
        var reader = new ClickhouseDatabaseOperator(source(false, new AtomicBoolean()));
        assertThrows(IllegalStateException.class, () -> reader.queryTableData("default", source, FIELDS, List.of("id")));
        assertRows(source, List.of("1|NULL|a", "1|NULL|b"));
    }

    @Test void decimalTruncationAndIntegerOverflowRejectWholeBatchBeforeAnyServerWrite() throws Exception {
        var ds = source(false, new AtomicBoolean()); var writer = new ClickhouseWriterImpl(ds);
        writer.prepareWrite("default", source, FIELDS);
        assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS, List.of(
                new Object[]{1L, AMOUNT, "must-not-write"}, new Object[]{2L, new BigDecimal("1.1234567899"), "truncated"})));
        assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS,
                List.<Object[]>of(new Object[]{new java.math.BigInteger("9223372036854775808"), null, "overflow"})));
        assertRows(source, List.of());
        seedSource(); var sync = sync(source, ds);
        assertThrows(IllegalArgumentException.class, () -> sync.executeUpdate(List.of(
                new Object[]{1L, AMOUNT, "must-not-write"}, new Object[]{2L, new BigDecimal("1.1234567899"), "truncated"})));
        assertRows(source, List.of("1|NULL|first", "2|NULL|second"));
    }

    @Test void entireMutationBatchPreflightRejectsMissingAndDuplicateKeysWithoutPrefixMutation() throws Exception {
        seedSource(); var sync = sync(source, source(false, new AtomicBoolean()));
        assertThrows(IllegalStateException.class, () -> sync.executeUpdate(List.of(
                new Object[]{1L, null, "must-not-write"}, new Object[]{99L, null, "missing"})));
        assertThrows(IllegalArgumentException.class, () -> sync.executeDelete(List.of(
                new Object[]{1L, null, "a"}, new Object[]{1L, null, "b"})));
        assertRows(source, List.of("1|NULL|first", "2|NULL|second"));
    }

    @Test void successfulServerMutationWithLostResponseRemainsUnknownAndIsNotRetried() throws Exception {
        seedSource(); var failure = new AtomicBoolean(); var ds = source(true, failure);
        var sync = sync(source, ds); failure.set(true);
        var exception = assertThrows(UnconfirmedWriteException.class,
                () -> sync.executeUpdate(List.<Object[]>of(new Object[]{1L, null, "server-committed"})));
        assertNull(exception.getCause()); assertFalse(failure.get());
        assertRows(source, List.of("1|NULL|server-committed", "2|NULL|second"));
    }

    @Test void publicMigrationAutoCreateFullThenDifferenceAndNoChangeReplay() throws Exception {
        seedSource();
        try (var runtime = DbSwitchRuntime.standalone()) {
            var full = new MigrationService(request(false), ignored -> { }, null, runtime).run();
            assertTrue(full.getIfAllSuccess().get(), () -> full.getTableResultList().toString());
            assertRows(target, List.of("1|NULL|first", "2|NULL|second"));
            sql("ALTER TABLE " + name(source) + " DELETE WHERE id=1 SETTINGS mutations_sync=2");
            sql("ALTER TABLE " + name(source) + " UPDATE amount=CAST('" + AMOUNT + "' AS Decimal(38,9)),note='更新' WHERE id=2 SETTINGS mutations_sync=2");
            sql("INSERT INTO " + name(source) + " VALUES(3,NULL,'new')");
            var diff = new MigrationService(request(true), ignored -> { }, null, runtime).run();
            assertTrue(diff.getIfAllSuccess().get(), () -> diff.getTableResultList().toString());
            assertRows(target, List.of("2|" + AMOUNT.toPlainString() + "|更新", "3|NULL|new"));
            var replay = new MigrationService(request(true), ignored -> { }, null, runtime).run();
            assertTrue(replay.getIfAllSuccess().get());
            assertRows(target, List.of("2|" + AMOUNT.toPlainString() + "|更新", "3|NULL|new"));
        }
    }

    @Test void publicDifferenceDuplicateSourceFailsWithoutChangingTarget() throws Exception {
        sql("CREATE TABLE " + name(target) + " AS " + name(source));
        sql("INSERT INTO " + name(target) + " VALUES(1,NULL,'keep')");
        sql("INSERT INTO " + name(source) + " VALUES(1,NULL,'a'),(1,NULL,'b')");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request(true), ignored -> { }, null, runtime).run();
            assertFalse(result.getIfAllSuccess().get());
        }
        assertRows(target, List.of("1|NULL|keep"));
    }

    @Test void nativeQuotedColumnNamesWorkForInsertAndMutation() throws Exception {
        sql("CREATE TABLE " + name(target) + " (id Int64," + ClickhouseSql.quote("说明`\\字段")
                + " Nullable(String)) ENGINE=MergeTree ORDER BY id");
        var fields = List.of("id", "说明`\\字段"); var ds = source(false, new AtomicBoolean());
        var writer = new ClickhouseWriterImpl(ds); writer.prepareWrite("default", target, fields);
        assertEquals(1, writer.write(fields, List.<Object[]>of(new Object[]{1L, "before"})));
        var sync = new ClickhouseDatabaseSyncImpl(ds); sync.prepare("default", target, fields, List.of("id"));
        assertEquals(1, sync.executeUpdate(List.<Object[]>of(new Object[]{1L, "after"})));
        try (var result = new ClickhouseDatabaseOperator(ds).queryTableData("default", target, fields)) {
            assertTrue(result.getResultset().next()); assertEquals("after", result.getResultset().getString(2));
        }
    }

    @Test void highPrecisionDecimalMatchKeyIsBoundAsExactTypedText() throws Exception {
        sql("CREATE TABLE " + name(target) + " (id Decimal(38,9),note Nullable(String)) ENGINE=MergeTree ORDER BY id");
        var ds = source(false, new AtomicBoolean()); var fields = List.of("id", "note");
        var writer = new ClickhouseWriterImpl(ds); writer.prepareWrite("default", target, fields);
        assertEquals(1, writer.write(fields, List.<Object[]>of(new Object[]{AMOUNT, "before"})));
        var sync = new ClickhouseDatabaseSyncImpl(ds); sync.prepare("default", target, fields, List.of("id"));
        assertEquals(1, sync.executeUpdate(List.<Object[]>of(new Object[]{AMOUNT, "after"})));
        assertEquals(1, sync.executeDelete(List.<Object[]>of(new Object[]{AMOUNT, "after"})));
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count() FROM " + name(target))) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
    }

    private void seedSource() throws Exception { sql("INSERT INTO " + name(source) + " VALUES(1,NULL,'first'),(2,NULL,'second')"); }
    private static ClickhouseDatabaseSyncImpl sync(String table, DataSource ds) {
        var sync = new ClickhouseDatabaseSyncImpl(ds); sync.prepare("default", table, FIELDS, List.of("id")); return sync;
    }
    private MigrationRequest request(boolean difference) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.CLICKHOUSE); input.setUrl(url()); input.setUsername("default"); input.setPassword("");
        input.setDriverClassName(ProductTypeEnum.CLICKHOUSE.getDriveClassName()); input.setSourceSchema("default");
        input.setSourceIncludes(source); input.setPoolSize(2); input.setFetchSize(2);
        input.setRegexTableMapper(List.of(new PatternMapper("^" + source + "$", target))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.CLICKHOUSE);
        output.setUrl(url()); output.setUsername("default"); output.setPassword("");
        output.setDriverClassName(ProductTypeEnum.CLICKHOUSE.getDriveClassName()); output.setTargetSchema("default");
        output.setTargetTable(target); output.setPoolSize(4); output.setWriterEngineInsert(true); output.setChangeDataSync(difference);
        output.setBatchLimits(new BatchLimits(10, 4096, 1024, 100, 1000));
        return request;
    }
    private static String url() { return System.getProperty("datahub.test.clickhouse.url"); }
    private static Connection open() throws SQLException { return DriverManager.getConnection(url(), "default", ""); }
    private static String name(String table) { return ClickhouseSql.table("default", table); }
    private static void sql(String sql) throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); }
    }
    private static void assertRows(String table, List<String> expected) throws Exception {
        var actual = new java.util.ArrayList<String>();
        try (var connection = open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,amount,note FROM " + name(table) + " ORDER BY id,note")) {
            while (rows.next()) actual.add(rows.getLong(1) + "|" + (rows.getBigDecimal(2) == null ? "NULL" : rows.getBigDecimal(2).toPlainString())
                    + "|" + rows.getString(3));
        }
        assertEquals(expected, actual);
    }
    /** Real driver/connection with transaction calls forbidden; optional response loss after real ALTER. */
    private static DataSource source(boolean inject, AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection real = open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("getAutoCommit", "setAutoCommit", "commit", "rollback").contains(method.getName()))
                        throw new AssertionError("ClickHouse must not emulate a JDBC transaction");
                    try {
                        Object result = method.invoke(real, args);
                        if (inject && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("ALTER TABLE")) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    Object value = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && fault.compareAndSet(true, false))
                                        throw new SQLException("injected lost response after real mutation");
                                    return value;
                                } catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
}
