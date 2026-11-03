package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.TdengineDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine.TdengineDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.tdengine.ws.url", matches="jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfEnvironmentVariable(named="DBSWITCH_TEST_TDENGINE_PASSWORD", matches=".+")
class TdengineMigrationTdengineTest {
    private static final List<String> FIELDS = List.of("ts", "amount", "n", "note", "raw");
    private static final BigDecimal AMOUNT = new BigDecimal("12345678901234567890123456789.123456789");
    private static final String TEXT = "中文'\\\n😀";
    private static final BatchLimits LIMITS = new BatchLimits(10, 8192, 2048, 10, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(8192, false, false, 20, 0, 0, 250, 0);
    private String schema;
    @BeforeEach void setup() throws Exception {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        schema = "native_td_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + schema + " VGROUPS 1 BUFFER 8 PRECISION 'ns'"); create("source");
    }
    @AfterEach void cleanup() throws Exception { if (schema != null) sql("DROP DATABASE IF EXISTS " + schema); }
    @Test void webSocketInsertAndSourceReaderPreserveNanosecondsDecimalNullBinaryAndSpecialText() throws Exception {
        var records = List.of(row(1, AMOUNT, TEXT), row(2, null, null));
        assertEquals(2, writer("source", source(null)).write(FIELDS, records));
        var reader = new TdengineDatabaseOperator(source(null)); reader.configure(OPTIONS); reader.setFetchSize(2);
        try (var result = reader.queryTableData(schema, "source", FIELDS)) {
            var rows = result.getResultset(); assertTrue(rows.next());
            assertEquals(time(1).toInstant(), rows.getTimestamp(1).toInstant()); assertEquals(AMOUNT, rows.getBigDecimal(2));
            assertEquals(9007199254740993L, rows.getLong(3)); assertEquals(TEXT, rows.getString(4)); assertArrayEquals(new byte[]{0,1,-1}, rows.getBytes(5));
            assertTrue(rows.next()); assertNull(rows.getObject(2)); assertNull(rows.getObject(4)); assertFalse(rows.next());
        }
        assertEquals(time(1), records.getFirst()[0], "Conversion must not replace caller timestamps with numeric epochs");
    }
    @Test void nativeOverwriteDeleteAndInsertAreVisibleAndKeepStableTimestampIdentity() throws Exception {
        writer("source", source(null)).write(FIELDS, List.of(row(1, null, "first"), row(2, null, "second")));
        var sync = sync("source", source(null));
        assertEquals(1, sync.executeUpdate(Collections.singletonList(row(2, AMOUNT, TEXT))));
        assertEquals(1, sync.executeDelete(Collections.singletonList(row(1, null, "first"))));
        assertEquals(1, sync.executeInsert(Collections.singletonList(row(3, null, "new"))));
        assertContents("source", List.of(row(2, AMOUNT, TEXT), row(3, null, "new")));
    }
    @Test void laterPrecisionLossRejectsBatchWithoutWritingPrefix() throws Exception {
        var writer = writer("source", source(null));
        assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS, List.of(row(1, AMOUNT, "valid"),
                row(2, new BigDecimal("0.0000000001"), "invalid"))));
        assertContents("source", List.of());
    }
    @Test void targetMillisecondPrecisionCannotSilentlyCollapseDistinctNanosecondKeys() throws Exception {
        String ms = schema + "_ms";
        sql("CREATE DATABASE " + ms + " VGROUPS 1 BUFFER 8 PRECISION 'ms'");
        try {
            sql("CREATE TABLE " + ms + ".sample(ts TIMESTAMP,n BIGINT)");
            var writer = new TdengineWriterImpl(source(null)); writer.prepareWrite(ms, "sample", List.of("ts", "n"));
            assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("ts", "n"), List.<Object[]>of(new Object[]{time(1), 1L})));
            try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + ms + ".sample")) {
                assertTrue(r.next()); assertEquals(0, r.getLong(1));
            }
        } finally { sql("DROP DATABASE " + ms); }
    }
    @Test void compositeTimestampSiblingRowsAreNeverDeletedByTimestampOnlyFallback() throws Exception {
        sql("CREATE TABLE " + name("composite_rows") + "(ts TIMESTAMP,id BIGINT COMPOSITE KEY,n BIGINT)");
        sql("INSERT INTO " + name("composite_rows") + " VALUES(" + epoch(1) + ",1,1)(" + epoch(1) + ",2,2)");
        var sync = new TdengineDatabaseSyncImpl(source(null));
        assertThrows(IllegalStateException.class, () -> sync.prepare(schema, "composite_rows", List.of("ts", "id", "n"), List.of("ts", "id")));
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + name("composite_rows"))) {
            assertTrue(r.next()); assertEquals(2, r.getLong(1));
        }
    }
    @Test void publicMigrationCreatesStructureTransfersFullDataThenDifferenceAndReplays() throws Exception {
        writer("source", source(null)).write(FIELDS, List.of(row(1, null, "first"), row(2, null, "second")));
        try (var runtime = DbSwitchRuntime.standalone()) {
            var full = new MigrationService(request(false), ignored -> {}, null, runtime).run();
            assertTrue(full.getIfAllSuccess().get(), () -> full.getTableResultList().toString());
            assertContents("target", List.of(row(1, null, "first"), row(2, null, "second")));
            var change = sync("source", source(null)); change.executeDelete(Collections.singletonList(row(1, null, "first")));
            change.executeUpdate(Collections.singletonList(row(2, AMOUNT, TEXT))); change.executeInsert(Collections.singletonList(row(3, null, "new")));
            var diff = new MigrationService(request(true), ignored -> {}, null, runtime).run();
            assertTrue(diff.getIfAllSuccess().get(), () -> diff.getTableResultList().toString());
            assertContents("target", List.of(row(2, AMOUNT, TEXT), row(3, null, "new")));
            assertTrue(new MigrationService(request(true), ignored -> {}, null, runtime).run().getIfAllSuccess().get());
            assertContents("target", List.of(row(2, AMOUNT, TEXT), row(3, null, "new")));
        }
    }
    @Test void committedOverwriteWithLostResponseIsUnknownWithoutRetry() throws Exception {
        writer("source", source(null)).write(FIELDS, Collections.singletonList(row(1, null, "before")));
        var armed = new AtomicBoolean(); var sync = sync("source", source(armed)); armed.set(true);
        assertThrows(UnconfirmedWriteException.class, () -> sync.executeUpdate(Collections.singletonList(row(1, AMOUNT, TEXT))));
        assertFalse(armed.get()); assertContents("source", Collections.singletonList(row(1, AMOUNT, TEXT)));
    }
    @Test void nativeClearAndDropCompleteWithoutTransactionEmulation() throws Exception {
        writer("source", source(null)).write(FIELDS, Collections.singletonList(row(1, AMOUNT, TEXT)));
        var reader = new TdengineDatabaseOperator(source(null)); reader.truncateTableData(schema, "source");
        assertContents("source", List.of()); reader.dropTable(schema, "source");
        try (var c = open(); var s = c.createStatement()) { assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM " + name("source"))); }
    }
    @Test void nativeUnsignedExtremesAndByteStringsRoundTripThroughWriterAndDifference() throws Exception {
        String table = "unsigned_rows";
        sql("CREATE TABLE " + name(table) + "(ts TIMESTAMP,u8 TINYINT UNSIGNED,u16 SMALLINT UNSIGNED,u32 INT UNSIGNED,"
                + "u64 BIGINT UNSIGNED,chars NCHAR(2),bytes VARCHAR(8),raw VARBINARY(8))");
        var fields = List.of("ts", "u8", "u16", "u32", "u64", "chars", "bytes", "raw");
        var maximum = new java.math.BigInteger("18446744073709551615");
        byte[] binary = new byte[]{0,-1,1};
        Object[] row = {time(1), 255, 65535, 4294967295L, maximum, "中😀", "中文", binary};
        var writer = new TdengineWriterImpl(source(null)); writer.configureBatchLimits(LIMITS); writer.prepareWrite(schema, table, fields);
        assertEquals(1, writer.write(fields, Collections.singletonList(row)));
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + name(table))) {
            assertTrue(r.next()); assertEquals((short)255, r.getObject(2)); assertEquals(65535, r.getObject(3));
            assertEquals(4294967295L, r.getObject(4)); assertEquals(maximum, r.getObject(5)); assertEquals("中😀", r.getString(6));
            assertArrayEquals("中文".getBytes(java.nio.charset.StandardCharsets.UTF_8), r.getBytes(7)); assertArrayEquals(binary, r.getBytes(8));
        }
        var sync = new TdengineDatabaseSyncImpl(source(null)); sync.configureBatchLimits(LIMITS); sync.prepare(schema, table, fields, List.of("ts"));
        Object[] changed = {time(1), 0, 0, 0L, java.math.BigInteger.ZERO, "😀", new byte[]{0,-1}, new byte[0]};
        assertEquals(1, sync.executeUpdate(Collections.singletonList(changed)));
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + name(table))) {
            assertTrue(r.next()); assertEquals(java.math.BigInteger.ZERO, r.getObject(5));
            assertArrayEquals(new byte[]{0,-1}, r.getBytes(7)); assertArrayEquals(new byte[0], r.getBytes(8));
        }
        assertEquals(1, sync.executeDelete(Collections.singletonList(changed)));
    }
    @Test void unsignedOverflowAndVarcharByteOverflowRejectEntireBatchBeforePrefixWrite() throws Exception {
        sql("CREATE TABLE " + name("bounded_rows") + "(ts TIMESTAMP,u BIGINT UNSIGNED,bytes VARCHAR(4))");
        var fields = List.of("ts", "u", "bytes");
        var writer = new TdengineWriterImpl(source(null)); writer.prepareWrite(schema, "bounded_rows", fields);
        Object[] first = {time(1), java.math.BigInteger.ONE, "中"};
        assertThrows(IllegalArgumentException.class, () -> writer.write(fields, List.of(first,
                new Object[]{time(2), new java.math.BigInteger("18446744073709551616"), "ok"})));
        assertThrows(IllegalArgumentException.class, () -> writer.write(fields, List.of(first,
                new Object[]{time(2), java.math.BigInteger.ONE, "中文"})));
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + name("bounded_rows"))) {
            assertTrue(r.next()); assertEquals(0, r.getLong(1));
        }
    }
    private void create(String table) throws Exception {
        sql("CREATE TABLE " + name(table) + "(ts TIMESTAMP,amount DECIMAL(38,9),n BIGINT,note NCHAR(64),raw VARBINARY(64))");
    }
    private static Timestamp time(int second) { return Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456789Z").plusSeconds(second)); }
    private static long epoch(int second) { return TdengineJdbcSupport.timestamp(time(second), 9); }
    private static Object[] row(int second, BigDecimal amount, String text) { return new Object[]{time(second), amount, 9007199254740993L, text, new byte[]{0,1,-1}}; }
    private TdengineWriterImpl writer(String table, DataSource source) {
        var writer = new TdengineWriterImpl(source); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS); writer.prepareWrite(schema, table, FIELDS); return writer;
    }
    private TdengineDatabaseSyncImpl sync(String table, DataSource source) {
        var sync = new TdengineDatabaseSyncImpl(source); sync.configure(OPTIONS); sync.configureBatchLimits(LIMITS); sync.prepare(schema, table, FIELDS, List.of("ts")); return sync;
    }
    private MigrationRequest request(boolean difference) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.TDENGINE); input.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        input.setUrl(url()); input.setUsername("root"); input.setPassword(password()); input.setSourceSchema(schema); input.setSourceIncludes("source");
        input.setPoolSize(2); input.setFetchSize(2); input.setRegexTableMapper(List.of(new PatternMapper("^source$", "target"))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.TDENGINE); output.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        output.setUrl(url()); output.setUsername("root"); output.setPassword(password()); output.setTargetSchema(schema); output.setTargetTable("target");
        output.setPoolSize(3); output.setWriterEngineInsert(true); output.setChangeDataSync(difference); output.setBatchLimits(LIMITS);
        return request;
    }
    private static String url() { return System.getProperty("datahub.test.tdengine.ws.url"); }
    private static String password() { return System.getenv("DBSWITCH_TEST_TDENGINE_PASSWORD"); }
    private static Connection open() throws SQLException { return DriverManager.getConnection(url(), "root", password()); }
    private String name(String table) { return TdengineJdbcSupport.table(schema, table); }
    private static void sql(String sql) throws Exception { try (var c = open(); var s = c.createStatement()) { s.setQueryTimeout(20); s.execute(sql); } }
    private void assertContents(String table, List<Object[]> expected) throws Exception {
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + name(table) + " ORDER BY ts")) {
            for (var row : expected) {
                assertTrue(r.next()); assertEquals(((Timestamp) row[0]).toInstant(), r.getTimestamp(1).toInstant());
                assertEquals(row[1], r.getBigDecimal(2)); assertEquals(row[2], r.getLong(3)); assertEquals(row[3], r.getString(4));
                assertArrayEquals((byte[]) row[4], r.getBytes(5));
            }
            assertFalse(r.next());
        }
    }
    private static DataSource source(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Unexpected TDengine JDBC transaction control");
                    try {
                        Object value = method.invoke(real, args);
                        if (fault != null && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO")) {
                            var statement = (PreparedStatement) value;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p,m,a) -> {
                                try { Object result = m.invoke(statement, a); if (m.getName().equals("executeUpdate") && fault.compareAndSet(true, false))
                                    throw new SQLException("injected response loss after actual write"); return result;
                                } catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return value;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String pass) throws SQLException { return getConnection(); }
        };
    }
}
