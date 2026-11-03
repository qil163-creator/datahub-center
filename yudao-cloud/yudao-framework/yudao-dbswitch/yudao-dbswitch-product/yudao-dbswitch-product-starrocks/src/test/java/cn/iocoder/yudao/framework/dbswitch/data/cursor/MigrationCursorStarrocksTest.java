package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.StarrocksSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.StarrocksDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksUpsertWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real StarRocks 3.5.21 typed cursor: pinned non-snapshot PRIMARY-key keyset with identity recheck, and the PRIMARY-key UPSERT. */
@Timeout(300)
@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationCursorStarrocksTest {
    private static final BatchLimits LIMITS = new BatchLimits(8, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0);
    private static final String TABLE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT,d DECIMAL(38,18),big LARGEINT,note VARCHAR(50),raw VARBINARY,at DATETIME,day DATE)"
            + " PRIMARY KEY(k,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";
    private static final String SIMPLE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,note VARCHAR(50))"
            + " PRIMARY KEY(k,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";
    private static final List<String> FIELDS = List.of("k", "id", "v", "d", "big", "note", "raw", "at", "day");
    private static final List<String> SIMPLE_FIELDS = List.of("k", "id", "note");
    private static final List<String> KEYS = List.of("k", "id");
    private static final BigDecimal MAX_LARGEINT = new BigDecimal("170141183460469231731687303715884105727");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private Connection control;
    private String source, target;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> databases = new ArrayList<>();
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        control = DriverManager.getConnection(url(), "root", "");
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = database("scur_s_" + id); target = database("scur_t_" + id);
        pin = StarrocksSourceGeneration.initialize(control, source, 15);
        runtime = new DbSwitchRuntime(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover(), OPTIONS, null);
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally {
            try (var owned = control) {
                for (String database : databases) sql("DROP DATABASE " + database + " FORCE");
            }
        }
    }

    @Test void keysetPagesDeliverExactValuesAndResumeReplaysTheAppliedWatermarkGroup() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + TABLE); sql("CREATE TABLE " + table(target, "t") + TABLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,10,1,12.340000000000000000," + MAX_LARGEINT + ",'一''\\\\',to_binary('00ff','hex'),'2026-10-03 12:34:56.123456','2026-10-01'),"
                + "(1,20,2,-0.000000000000000001," + MAX_LARGEINT.negate() + ",'','','0001-01-01 00:00:00.000001','9999-12-31'),"
                + "(2,5,3,NULL,NULL,NULL,NULL,NULL,NULL),(2,6,4,0,0,'max',NULL,'2026-01-01 00:00:00','2026-02-01'),(3,1,5,1,1,'last',NULL,'9999-12-31 23:59:59.999999','2026-03-01')");
        transfer("s", "t", FIELDS, 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of("3", "1"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals(List.of("3", "1"), saved.get().fixedUpper().values().stream().map(v -> v.value()).toList());
        assertEquals(rows(source, "s"), rows(target, "t"));
        // Equal-watermark rows that became visible later are replayed by the next run; a newer watermark row follows.
        sql("INSERT INTO " + table(source, "s") + " VALUES (3,0,6,2.000000000000000000,2,'late',NULL,'2026-10-04 00:00:00','2026-10-04'),(4,9,7,3,3,'new',NULL,NULL,NULL)");
        // Older than the applied watermark: outside the cursor window by contract (needs DIFF or an explicit rewind).
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,99,8,4,4,'old',NULL,NULL,NULL)");
        transfer("s", "t", FIELDS, 2, this::save);
        var expected = rows(source, "s"); expected.removeIf(row -> row.get(0).equals("1") && row.get(1).equals("99"));
        assertEquals(expected, rows(target, "t"));
        long sequence = saved.get().sequence();
        transfer("s", "t", FIELDS, 2, this::save);
        assertEquals(expected, rows(target, "t")); assertTrue(saved.get().sequence() > sequence, "The applied watermark group is replayed idempotently");
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysAsAnIdempotentOverwrite() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(1,2,'b'),(2,1,'c'),(2,2,'d')");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("s", "t", SIMPLE_FIELDS, 1,
                value -> calls.incrementAndGet() != 3 && save(value)));
        awaitCount(target, "t", 2, 10); assertFalse(saved.get().complete()); assertEquals(1, saved.get().sequence());
        // A stale value at an applied key is overwritten by the replay, not duplicated.
        sql("INSERT INTO " + table(target, "t") + " VALUES (1,2,'stale')");
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "s"), rows(target, "t"));
    }

    @Test void lostInsertAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(2,1,'b')");
        var fault = new AtomicBoolean(true);
        assertThrows(PartialBatchWriteException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(fault), ProductTypeEnum.STARROCKS,
                ProductTypeEnum.STARROCKS, source, "s", target, "t", SIMPLE_FIELDS, SIMPLE_FIELDS, KEYS, KEYS, "k",
                LIMITS, 2, binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(fault.get()); awaitCount(target, "t", 2, 10);
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertEquals(rows(source, "s"), rows(target, "t"));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "DATABASE_RECREATE", "TABLE_RECREATE", "TRUNCATE", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationRelationOrTargetBeforeWriting(String change) throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        transfer("s", "t", SIMPLE_FIELDS, 2, this::save);
        assertEquals(1, count(target, "t"));
        switch (change) {
            case "PIN_ROTATE" -> pin = StarrocksSourceGeneration.rotate(control, pin, UUID.randomUUID().toString(), 15);
            case "DATABASE_RECREATE" -> { sql("DROP DATABASE " + source + " FORCE"); databases.remove(source); source = database(source);
                sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TABLE_RECREATE" -> { sql("DROP TABLE " + table(source, "s")); sql("CREATE TABLE " + table(source, "s") + SIMPLE);
                sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TRUNCATE" -> { sql("TRUNCATE TABLE " + table(source, "s")); sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TARGET_RECREATE" -> { sql("DROP TABLE " + table(target, "t")); sql("CREATE TABLE " + table(target, "t") + SIMPLE); }
            default -> { }
        }
        sql("INSERT INTO " + table(source, "s") + " VALUES (2,1,'new')");
        long before = count(target, "t");
        if (change.equals("NONE")) {
            transfer("s", "t", SIMPLE_FIELDS, 2, this::save);
            assertEquals(before + 1, count(target, "t")); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "t", SIMPLE_FIELDS, 2, this::save));
        assertEquals(before, count(target, "t"));
    }

    @Test void partitionPlanChangesRejectTheOldResumeBeforeAnyWrite() throws Exception {
        String shape = "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT) PRIMARY KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES [('-9223372036854775808'),('10')),PARTITION p2 VALUES [('10'),('9223372036854775807')))"
                + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";
        sql("CREATE TABLE " + table(source, "s") + shape); sql("CREATE TABLE " + table(target, "t") + shape);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,1)");
        transfer("s", "t", List.of("k", "id", "v"), 2, this::save);
        assertEquals(1, count(target, "t"));
        sql("ALTER TABLE " + table(source, "s") + " DROP PARTITION p2");
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,2,2)");
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "t", List.of("k", "id", "v"), 2, this::save));
        assertEquals(1, count(target, "t"));
    }

    @Test void unsupportedSourceShapesAndMissingGenerationFailBeforeReading() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        sql("CREATE TABLE " + table(source, "dup") + "(k INT NOT NULL,v INT) DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("CREATE TABLE " + table(source, "uni") + "(k INT NOT NULL,v INT) UNIQUE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("CREATE TABLE " + table(source, "txt") + "(w VARCHAR(20) NOT NULL,v INT) PRIMARY KEY(w) DISTRIBUTED BY HASH(w) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("INSERT INTO " + table(source, "txt") + " VALUES ('a',1)");
        SourceGenerationPin foreign = StarrocksSourceGeneration.initialize(control, target, 15);
        var operator = new StarrocksDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(2);
        var rejected = List.<CursorReadRequest>of(
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, null),
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, foreign),
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "note", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "dup", List.of("k", "v"), "k", List.of("k"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "uni", List.of("k", "v"), "k", List.of("k"), null, null, null, LIMITS, false, pin));
        for (var request : rejected) assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request), request.table());
        // Text keys replay only under the identical native contract: the same product with the same type is accepted.
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "txt", List.of("w", "v"), "w", List.of("w"), null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
        }
        sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.STARROCKS, ProductTypeEnum.STARROCKS,
                source, "s", target, "t", SIMPLE_FIELDS, SIMPLE_FIELDS, KEYS, KEYS, "k", LIMITS, 2,
                new CursorRunBinding("e".repeat(64), null, this::save, foreign), new DbSwitchTableResult(), () -> { }));
        assertEquals(0, count(target, "t")); assertNull(saved.get());
    }

    @Test void upsertWriterReplacesFullRowsAndRejectsPartialDuplicateOrUnsupportedBatches() throws Exception {
        sql("CREATE TABLE " + table(target, "t") + TABLE);
        sql("INSERT INTO " + table(target, "t") + " VALUES (1,1,9,9,9,'stale',NULL,NULL,NULL)");
        var writer = new StarrocksUpsertWriter(ds(null)); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "t", FIELDS, KEYS);
        assertTrue(writer.targetSchemaFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(ProductTypeEnum.STARROCKS, writer.keyContract().product());
        var replaced = List.<Object[]>of(new Object[]{1L, 1L, 1, new BigDecimal("12.340000000000000000"), MAX_LARGEINT, "一'\\", new byte[]{0, -1}, AT, DAY});
        assertEquals(1, writer.writeWithReceipt(FIELDS, replaced).acknowledgedRows());
        assertEquals(List.of(List.of("1", "1", "1", "12.340000000000000000", "170141183460469231731687303715884105727", "一'\\", "2026-10-03T12:34:56.123456", "2026-10-01")),
                rows(target, "t", "k,id,v,d,big,note,at,day"));
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT hex(raw) FROM " + table(target, "t"))) {
            assertTrue(result.next()); assertEquals("00FF", result.getString(1), "The native VARBINARY value round-trips exactly");
        }
        assertEquals(1, writer.writeWithReceipt(FIELDS, replaced).acknowledgedRows(), "Full-row upsert replaces the existing key and converges");
        assertEquals(1, count(target, "t"));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FIELDS, List.of(
                new Object[]{2L, 2L, 1, null, null, "a", null, null, null}, new Object[]{2L, 2L, 2, null, null, "b", null, null, null})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(List.of("k", "id", "v"), List.<Object[]>of(new Object[]{1L, 1L, 1})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FIELDS, List.<Object[]>of(new Object[]{3L, null, 1, null, null, "a", null, null, null})));
        assertEquals(1, count(target, "t"));
        sql("CREATE TABLE " + table(target, "partial") + "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT) PRIMARY KEY(k,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(target, "partial", List.of("k", "id", "v"), List.of("k")));
        sql("CREATE TABLE " + table(target, "dup") + "(k BIGINT NOT NULL,v INT) DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(target, "dup", List.of("k", "v"), List.of("k")));
    }

    private void transfer(String sourceTable, String targetTable, List<String> fields, int fetch, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.STARROCKS, ProductTypeEnum.STARROCKS, source, sourceTable, target, targetTable,
                fields, fields, KEYS, KEYS, "k", LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private String database(String name) throws Exception { sql("CREATE DATABASE " + name); databases.add(name); return name; }
    private static String table(String schema, String table) { return "`" + schema + "`.`" + table + "`"; }

    private List<List<Object>> rows(String schema, String table) throws Exception {
        return rows(schema, table, "*");
    }
    private List<List<Object>> rows(String schema, String table, String projection) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT " + projection + " FROM " + table(schema, table) + " ORDER BY k,id")) {
            var rows = new ArrayList<List<Object>>();
            var meta = result.getMetaData();
            while (result.next()) {
                var row = new ArrayList<Object>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.add(canonical(result.getObject(i)));
                rows.add(row);
            }
            return rows;
        }
    }
    private static String canonical(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof LocalDateTime time) return time.toString();
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        return String.valueOf(value);
    }
    private long count(String schema, String table) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table(schema, table))) {
            result.next(); return result.getLong(1);
        }
    }
    /** OLAP visibility is eventually consistent across connections; acknowledged rows become visible within a bounded window. */
    private void awaitCount(String schema, String table, long expected, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long actual = -1;
        while (System.currentTimeMillis() < deadline) {
            actual = count(schema, table);
            if (actual == expected) return;
            Thread.sleep(200);
        }
        fail("Target row count is " + actual + ", expected " + expected);
    }
    private static String url() { return System.getProperty("datahub.test.starrocks.url"); }
    private void sql(String text) throws Exception { try (var statement = control.createStatement()) { statement.setQueryTimeout(30); statement.execute(text); } }
    /** Owned connections that fail on JDBC transaction control; an armed fault loses one INSERT acknowledgement after the write. */
    private DataSource ds(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = DriverManager.getConnection(url(), "root", "");
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Unexpected StarRocks JDBC transaction control");
                    try {
                        Object value = method.invoke(real, args);
                        if (fault != null && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO")) {
                            var statement = (PreparedStatement) value;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    Object outcome = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && fault.compareAndSet(true, false)) throw new SQLException("injected response loss after actual write");
                                    return outcome;
                                } catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return value;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        };
    }
}
