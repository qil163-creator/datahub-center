package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.ClickhouseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ClickhouseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse.ClickhouseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real ClickHouse 25.8 typed cursor: pinned non-snapshot sorting-key keyset with identity recheck, and the
 *  mutation read-back UPSERT. Proves keyset == full ORDER BY across every key domain with real data. */
@Timeout(300)
@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class MigrationCursorClickhouseTest {
    private static final BatchLimits LIMITS = new BatchLimits(8, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0);
    private static final BigDecimal AMOUNT = new BigDecimal("12345678901234567890123456789.123456789");
    private static final String FAMILY = "(k Int64,id Int64,v Nullable(Int64),amount Nullable(Decimal(38,9)),note Nullable(String),"
            + "dt Nullable(DateTime64(6)),day Nullable(Date32),s String,u UInt64) ENGINE=MergeTree ORDER BY (k,id)";
    private static final List<String> FIELDS = List.of("k", "id", "v", "amount", "note", "dt", "day", "s", "u");
    private static final List<String> KEYS = List.of("k", "id");
    private static final String PROJECTION = "k,id,v,amount,note,dt,day,s,u";
    private String source, target;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = "chcur_s_" + id; target = "chcur_t_" + id;
        sql("CREATE DATABASE " + source + " ENGINE=Atomic"); sql("CREATE DATABASE " + target + " ENGINE=Atomic");
        try (var c = open()) { pin = ClickhouseSourceGeneration.initialize(c, source, 15); }
        runtime = DbSwitchRuntime.standalone();
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally { sql("DROP DATABASE IF EXISTS " + source + " SYNC"); sql("DROP DATABASE IF EXISTS " + target + " SYNC"); }
    }

    @Test void keysetPagesEqualFullOrderByAndResumeReplaysTheAppliedWatermarkGroup() throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + table(source) + " VALUES (1,10,1," + AMOUNT + ",'first','2026-10-03 12:34:56.123456','2026-10-01','a',0),"
                + "(1,20,2,NULL,NULL,NULL,NULL,'b',1),(2,1,3,-0.000000001,'', '2026-01-01 00:00:00.000001','2026-02-01','中文',2),"
                + "(2,2,4,0,'last','2299-12-31 23:59:59.999999','2299-12-31','x',18446744073709551615),(3,1,5,1,'z','1900-01-01 00:00:00','1900-01-01','y',9223372036854775808)");
        // Page size 2 splits the (2,*) watermark group across pages and proves the keyset equals the full ORDER BY.
        transfer("s", "s", FIELDS, KEYS, "k", 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of("3", "1"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals(List.of("3", "1"), saved.get().fixedUpper().values().stream().map(v -> v.value()).toList());
        assertEquals(rows(source, "s", PROJECTION, "k,id"), rows(target, "s", PROJECTION, "k,id"));
        // Equal-watermark rows that became visible later are replayed by the next run; a newer watermark row follows.
        sql("INSERT INTO " + table(source) + " VALUES (3,0,6,2,'late','2026-10-04 00:00:00','2026-10-04','n',3),(4,9,7,3,'new',NULL,NULL,'m',4)");
        // Older than the applied watermark: outside the cursor window by contract (needs DIFF or an explicit rewind).
        sql("INSERT INTO " + table(source) + " VALUES (1,99,8,4,'old',NULL,NULL,'o',5)");
        transfer("s", "s", FIELDS, KEYS, "k", 2, this::save);
        var expected = rows(source, "s", PROJECTION, "k,id");
        expected.removeIf(row -> row.get(0).equals("1") && row.get(1).equals("99"));
        assertEquals(expected, rows(target, "s", PROJECTION, "k,id"));
        long sequence = saved.get().sequence();
        transfer("s", "s", FIELDS, KEYS, "k", 2, this::save);
        assertEquals(expected, rows(target, "s", PROJECTION, "k,id"));
        assertTrue(saved.get().sequence() > sequence, "The applied watermark group is replayed idempotently");
    }

    @Test void everyKeyDomainOrdersExactAndKeysetEqualsFullOrderBy() throws Exception {
        // Page size 1 forces every adjacent pair to be a page boundary; any compare or bound error surfaces.
        keyFamily("str_s", "(s String,v Int64) ENGINE=MergeTree ORDER BY s");
        sql("INSERT INTO " + name(source, "str_s") + " VALUES ('B',1),('a',2),('\u007f',3),('中文',4),('',5)");
        transfer("str_s", "str_t", List.of("s", "v"), List.of("s"), "s", 1, this::save);
        assertEquals(rows(source, "str_s", "s,v", "s"), rows(target, "str_t", "s,v", "s"));
        assertEquals(List.of("中文"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals("String", saved.get().applied().values().getFirst().typeName());
        saved.set(null); // One table's applied tuple must never leak into another key domain's checkpoint.
        keyFamily("dec_s", "(amount Decimal(38,9),v Int64) ENGINE=MergeTree ORDER BY amount");
        sql("INSERT INTO " + name(source, "dec_s") + " VALUES (-0.000000001,1),(0,2),(1.000000000,3),(" + AMOUNT + ",4)");
        transfer("dec_s", "dec_t", List.of("amount", "v"), List.of("amount"), "amount", 1, this::save);
        assertEquals(rows(source, "dec_s", "amount,v", "amount"), rows(target, "dec_t", "amount,v", "amount"));
        assertEquals(AMOUNT.toPlainString(), saved.get().applied().values().getFirst().value());
        assertEquals("Decimal(38,9)", saved.get().applied().values().getFirst().typeName());
        saved.set(null);
        keyFamily("uint_s", "(u UInt64,v Int64) ENGINE=MergeTree ORDER BY u");
        sql("INSERT INTO " + name(source, "uint_s") + " VALUES (0,1),(1,2),(9223372036854775808,3),(18446744073709551615,4)");
        transfer("uint_s", "uint_t", List.of("u", "v"), List.of("u"), "u", 1, this::save);
        assertEquals(rows(source, "uint_s", "u,v", "u"), rows(target, "uint_t", "u,v", "u"));
        assertEquals("18446744073709551615", saved.get().applied().values().getFirst().value());
        assertEquals("UInt64", saved.get().applied().values().getFirst().typeName());
        saved.set(null);
        keyFamily("dt_s", "(ts DateTime64(6),v Int64) ENGINE=MergeTree ORDER BY ts");
        sql("INSERT INTO " + name(source, "dt_s") + " VALUES ('2026-10-03 12:34:56.123456',1),('2026-10-03 12:34:56.123457',2),"
                + "('2026-10-03 12:34:57.000000',3),('2299-12-31 23:59:59.999999',4)");
        transfer("dt_s", "dt_t", List.of("ts", "v"), List.of("ts"), "ts", 1, this::save);
        assertEquals(rows(source, "dt_s", "ts,v", "ts"), rows(target, "dt_t", "ts,v", "ts"));
        assertEquals("DateTime64(6)", saved.get().applied().values().getFirst().typeName());
        saved.set(null);
        keyFamily("day_s", "(day Date32,v Int64) ENGINE=MergeTree ORDER BY day");
        sql("INSERT INTO " + name(source, "day_s") + " VALUES ('1900-01-01',1),('1970-01-01',2),('2026-10-01',3),('2299-12-31',4)");
        transfer("day_s", "day_t", List.of("day", "v"), List.of("day"), "day", 1, this::save);
        assertEquals(rows(source, "day_s", "day,v", "day"), rows(target, "day_t", "day,v", "day"));
        assertEquals("Date32", saved.get().applied().values().getFirst().typeName());
    }

    @Test void fixedUpperBoundIgnoresRowsBeyondItUntilTheNextRun() throws Exception {
        family(source);
        sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0),(2,1,2,NULL,'b',NULL,NULL,'y',1)");
        var operator = new ClickhouseDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(1);
        var read = new ArrayList<String>();
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "s", FIELDS, "k", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals(List.of("2", "1"), cursor.upperBound().values().stream().map(v -> v.value()).toList());
            while (cursor.next()) {
                read.add(cursor.row()[0] + "@" + cursor.row()[1]);
                if (read.size() == 1) {
                    // (1,2) sorts inside the frozen bound and became visible before its page: it is still delivered.
                    sql("INSERT INTO " + table(source) + " VALUES (1,2,3,NULL,'mid',NULL,NULL,'z',2),(2,2,4,NULL,'late',NULL,NULL,'w',3)");
                }
            }
        }
        assertEquals(List.of("1@1", "1@2", "2@1"), read, "(2,2) is beyond the fixed upper bound");
        family(target);
        transfer("s", "s", FIELDS, KEYS, "k", 1, this::save);
        assertEquals(rows(source, "s", PROJECTION, "k,id"), rows(target, "s", PROJECTION, "k,id"));
    }

    @ParameterizedTest @ValueSource(strings = {"String", "LowCardinality(String)"})
    void malformedTextInsertedAfterCursorPreparationAbortsItsActualPage(String type) throws Exception {
        sql("CREATE TABLE " + name(source, "late_text") + " (id Int64,note " + type + ") ENGINE=MergeTree ORDER BY id",
                "SYSTEM STOP MERGES " + name(source, "late_text"),
                "INSERT INTO " + name(source, "late_text") + " VALUES(1,'first'),(3,'last')");
        var operator = new ClickhouseDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(1);
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "late_text", List.of("id", "note"), "id",
                List.of("id"), null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next()); assertEquals("first", cursor.row()[1]);
            sql("INSERT INTO " + name(source, "late_text") + " VALUES(2,unhex('FF'))");
            var failure = assertThrows(SQLException.class, cursor::next);
            assertTrue(failure.getMessage().contains("valid UTF-8"), "the actual text guard, not a generation or parts rejection, must fail");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"String", "LowCardinality(String)"})
    void malformedTextSortingKeyCannotPublishAReplacementDecodedUpperBound(String type) throws Exception {
        sql("CREATE TABLE " + name(source, "bad_key") + " (s " + type + ",v Int64) ENGINE=MergeTree ORDER BY s",
                "INSERT INTO " + name(source, "bad_key") + " VALUES('a',1),(unhex('FF'),2)");
        var operator = new ClickhouseDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(1);
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = operator.openCursor(new CursorReadRequest(source, "bad_key", List.of("s", "v"), "s",
                    List.of("s"), null, null, null, LIMITS, false, pin))) { }
        });
    }

    @Test void cursorPageRejectsCompatibleNumericTypeChangeAfterGenerationChecksBeforeReturningAnyRow() throws Exception {
        String table = name(source, "stale_page");
        sql("CREATE TABLE " + table + " (id Int64,payload Int32) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1,100)");
        var changed = new AtomicBoolean();
        var dataSource = beforeRead("SELECT `id`,`payload` FROM " + table + " WHERE ", () -> {
            sql("ALTER TABLE " + table + " MODIFY COLUMN payload Int64 SETTINGS mutations_sync=2"); changed.set(true);
        });
        var operator = new ClickhouseDatabaseOperator(dataSource); operator.configure(OPTIONS); operator.setFetchSize(1);
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "stale_page", List.of("id", "payload"), "id",
                List.of("id"), null, null, null, LIMITS, false, pin))) {
            assertThrows(SQLException.class, cursor::next);
        }
        assertTrue(changed.get(), "actual ALTER completed after page generation checks and before executeQuery");
        assertEquals("Int64", columnType("stale_page", "payload"));
    }

    @Test void cursorUpperRejectsSortingKeyTypeAndRelationReplacementAfterFrozenShapeBeforePublishingTheBound() throws Exception {
        String table = name(source, "stale_upper");
        sql("CREATE TABLE " + table + " (id Int32) ENGINE=MergeTree ORDER BY id", "INSERT INTO " + table + " VALUES(1)");
        var changed = new AtomicBoolean();
        var dataSource = beforeRead("FROM " + table + " ORDER BY", () -> {
            // This server refuses ALTER of the sorting-key type; a real replacement can race the upper query.
            sql("DROP TABLE " + table + " SYNC", "CREATE TABLE " + table + " (id Int64) ENGINE=MergeTree ORDER BY id",
                    "INSERT INTO " + table + " VALUES(1)"); changed.set(true);
        });
        var operator = new ClickhouseDatabaseOperator(dataSource); operator.configure(OPTIONS); operator.setFetchSize(1);
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = operator.openCursor(new CursorReadRequest(source, "stale_upper", List.of("id"), "id",
                    List.of("id"), null, null, null, LIMITS, false, pin))) { }
        });
        assertTrue(changed.get(), "actual relation replacement completed after shape capture and before upper executeQuery");
        assertEquals("Int64", columnType("stale_upper", "id"));
    }

    @FunctionalInterface private interface BeforeRead { void run() throws Exception; }
    private static DataSource beforeRead(String fragment, BeforeRead change) {
        var fired = new AtomicBoolean();
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var connection = open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object value; try { value = method.invoke(connection, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (value instanceof Statement statement && (method.getName().equals("createStatement") || method.getName().equals("prepareStatement"))) {
                        String preparedSql = method.getName().equals("prepareStatement") ? (String) args[0] : null;
                        Class<?> contract = preparedSql == null ? Statement.class : PreparedStatement.class;
                        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (p, m, a) -> {
                            String sql = m.getName().equals("executeQuery") ? preparedSql == null ? (String) a[0] : preparedSql : null;
                            if (sql != null && sql.contains(fragment) && fired.compareAndSet(false, true)) change.run();
                            try { return m.invoke(statement, a); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    return value;
                });
            }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        };
    }
    private String columnType(String table, String column) throws Exception {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT type FROM system.columns WHERE database=? AND table=? AND name=?")) {
            statement.setString(1, source); statement.setString(2, table); statement.setString(3, column);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); String type = rows.getString(1); assertFalse(rows.next()); return type; }
        }
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysAsAnIdempotentOverwrite() throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0),(1,2,2,NULL,'b',NULL,NULL,'y',1),"
                + "(2,1,3,NULL,'c',NULL,NULL,'z',2),(2,2,4,NULL,'d',NULL,NULL,'w',3)");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("s", "s", FIELDS, KEYS, "k", 1,
                value -> calls.incrementAndGet() != 3 && save(value)));
        assertEquals(2, count(target));
        assertFalse(saved.get().complete()); assertEquals(1, saved.get().sequence());
        // A stale value at an applied key is overwritten by the replay, not duplicated.
        sql("ALTER TABLE " + table(target) + " UPDATE v=99,note='stale' WHERE k=1 AND id=1 SETTINGS mutations_sync=2");
        transfer("s", "s", FIELDS, KEYS, "k", 1, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "s", PROJECTION, "k,id"), rows(target, "s", PROJECTION, "k,id"));
    }

    @Test void lostInsertAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0),(1,2,2,NULL,'b',NULL,NULL,'y',1)");
        var fault = new AtomicBoolean(true);
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(fault), ProductTypeEnum.CLICKHOUSE,
                ProductTypeEnum.CLICKHOUSE, source, "s", target, "s", FIELDS, FIELDS, KEYS, KEYS, "k", LIMITS, 1,
                binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(fault.get()); assertEquals(1, count(target), "The injected loss happened after a real write");
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer("s", "s", FIELDS, KEYS, "k", 1, this::save);
        assertEquals(rows(source, "s", PROJECTION, "k,id"), rows(target, "s", PROJECTION, "k,id"));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "DATABASE_RECREATE", "TABLE_RECREATE", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationRelationOrTargetBeforeWriting(String change) throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0)");
        transfer("s", "s", FIELDS, KEYS, "k", 1, this::save);
        assertEquals(1, count(target));
        switch (change) {
            case "PIN_ROTATE" -> { try (var c = open()) { pin = ClickhouseSourceGeneration.rotate(c, pin, UUID.randomUUID().toString(), 15); } }
            case "DATABASE_RECREATE" -> { sql("DROP DATABASE " + source + " SYNC"); sql("CREATE DATABASE " + source + " ENGINE=Atomic");
                family(source); sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0)"); }
            case "TABLE_RECREATE" -> { sql("DROP TABLE " + table(source) + " SYNC"); family(source);
                sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0)"); }
            case "TARGET_RECREATE" -> { sql("DROP TABLE " + table(target) + " SYNC"); family(target); }
            default -> { }
        }
        sql("INSERT INTO " + table(source) + " VALUES (1,2,2,NULL,'new',NULL,NULL,'y',1)");
        long before = count(target);
        if (change.equals("NONE")) {
            transfer("s", "s", FIELDS, KEYS, "k", 1, this::save);
            assertEquals(before + 1, count(target)); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "s", FIELDS, KEYS, "k", 1, this::save));
        assertEquals(before, count(target));
    }

    @Test void unsupportedShapesAndMissingGenerationFailBeforeReading() throws Exception {
        family(source);
        sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0)");
        sql("CREATE TABLE " + name(source, "dup") + " (k Int64,id Int64,v Int64) ENGINE=MergeTree ORDER BY (k,id)");
        sql("INSERT INTO " + name(source, "dup") + " VALUES (1,1,1),(1,1,2)");
        sql("CREATE TABLE " + name(source, "expr") + " (k Int64,id Int64) ENGINE=MergeTree ORDER BY (k + id)");
        sql("CREATE TABLE " + name(source, "nullable_key") + " (k Nullable(Int64),id Int64) ENGINE=MergeTree ORDER BY (k,id) SETTINGS allow_nullable_key=1");
        sql("CREATE TABLE " + name(source, "bool_key") + " (b Bool,v Int64) ENGINE=MergeTree ORDER BY b");
        SourceGenerationPin foreign; try (var c = open()) { foreign = ClickhouseSourceGeneration.initialize(c, target, 15); }
        var operator = new ClickhouseDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(2);
        var rejected = List.<CursorReadRequest>of(
                new CursorReadRequest(source, "s", FIELDS, "k", KEYS, null, null, null, LIMITS, false, null),
                new CursorReadRequest(source, "s", FIELDS, "k", KEYS, null, null, null, LIMITS, false, foreign),
                new CursorReadRequest(source, "s", FIELDS, "id", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "s", FIELDS, "k", List.of("k"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "dup", List.of("k", "id", "v"), "k", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "expr", List.of("k", "id"), "k", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "nullable_key", List.of("k", "id"), "k", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "bool_key", List.of("b", "v"), "b", List.of("b"), null, null, null, LIMITS, false, pin));
        for (var request : rejected) assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request), request.table() + request.primaryKeys());
        // Controls on the same fixtures: only the rejected pin, key or shape differs; an empty table is a frozen empty window.
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "s", FIELDS, "k", KEYS, null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
        }
        sql("CREATE TABLE " + name(source, "empty") + " (k Int64,id Int64) ENGINE=MergeTree ORDER BY (k,id)");
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "empty", List.of("k", "id"), "k", KEYS, null, null, null, LIMITS, false, pin))) {
            assertFalse(cursor.next()); assertNull(cursor.upperBound());
        }
        // The applied watermark must not silently move backwards.
        family(target); transfer("s", "s", FIELDS, KEYS, "k", 2, this::save);
        assertEquals(1, count(target));
        sql("ALTER TABLE " + table(source) + " DELETE WHERE 1=1 SETTINGS mutations_sync=2");
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "s", FIELDS, KEYS, "k", 2, this::save));
        assertEquals(1, count(target));
    }

    @Test void upsertWriterConvergesWithMutationReadbackAndProvesVisibility() throws Exception {
        family(target);
        sql("INSERT INTO " + table(target) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0)");
        var writer = new ClickhouseUpsertWriter(ds(null)); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "s", FIELDS, KEYS);
        // Row 1: existing key with changed values -> UPDATE mutation; row 2: absent key -> INSERT.
        var receipt = writer.writeWithReceipt(FIELDS, List.of(
                new Object[]{1L, 1L, 2L, AMOUNT, "更新", LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000), LocalDate.of(2026, 10, 2), "p", BigInteger.ZERO},
                new Object[]{1L, 2L, 3L, null, null, null, null, "q", new BigInteger("18446744073709551615")}));
        assertEquals(2, receipt.acknowledgedRows()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, receipt.commitAcknowledgement());
        // The mutation was waited for and read back: the server state is the written state, no unfinished mutations.
        assertEquals(List.of("1|1|2|" + AMOUNT.toPlainString() + "|更新|2026-10-03T12:34:56.123456|2026-10-02|p|0",
                "1|2|3|NULL|NULL|NULL|NULL|q|18446744073709551615"), readRows(target));
        try (var connection = open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count() FROM system.mutations WHERE database='" + target + "' AND table='s' AND is_done=0")) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
        // Replay converges: a stale target value is overwritten by the mutation path, not duplicated.
        sql("ALTER TABLE " + table(target) + " UPDATE v=99 WHERE k=1 AND id=2 SETTINGS mutations_sync=2");
        assertEquals(2, writer.writeWithReceipt(FIELDS, List.of(
                new Object[]{1L, 1L, 2L, AMOUNT, "更新", LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000), LocalDate.of(2026, 10, 2), "p", BigInteger.ZERO},
                new Object[]{1L, 2L, 3L, null, null, null, null, "q", new BigInteger("18446744073709551615")})).acknowledgedRows());
        assertEquals(2, count(target));
        assertEquals(List.of("1|1|2|" + AMOUNT.toPlainString() + "|更新|2026-10-03T12:34:56.123456|2026-10-02|p|0",
                "1|2|3|NULL|NULL|NULL|NULL|q|18446744073709551615"), readRows(target));
    }

    @Test void upsertPreflightRejectsNullDuplicateOverflowAndImmutableChangesBeforeAnySend() throws Exception {
        family(target);
        sql("CREATE TABLE " + name(target, "im") + " (k Int64,sk Int64,v Int64) ENGINE=MergeTree ORDER BY (k,sk)");
        sql("INSERT INTO " + name(target, "im") + " VALUES (1,1,1)");
        var writer = new ClickhouseUpsertWriter(ds(null)); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "im", List.of("k", "sk", "v"), List.of("k"));
        // Immutable sorting-key column differs on an existing key: rejected before any mutation.
        assertThrows(IllegalStateException.class, () -> writer.writeWithReceipt(List.of("k", "sk", "v"),
                List.<Object[]>of(new Object[]{1L, 2L, 2L})));
        assertEquals("1|1|1", String.join("|", rows(target, "im", "k,sk,v", "k,sk").getFirst()));
        // Null/duplicate keys and overflow reject the whole batch before borrowing a connection.
        var borrowed = new AtomicInteger(); var delegate = ds(null);
        var counting = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { borrowed.incrementAndGet(); return delegate.getConnection(); }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        };
        var guarded = new ClickhouseUpsertWriter(counting); guarded.configure(OPTIONS); guarded.configureBatchLimits(LIMITS);
        guarded.prepareUpsert(target, "im", List.of("k", "sk", "v"), List.of("k")); borrowed.set(0);
        assertThrows(BatchRejectedException.class, () -> guarded.writeWithReceipt(List.of("k", "sk", "v"),
                List.<Object[]>of(new Object[]{null, 1L, 1L})));
        assertThrows(BatchRejectedException.class, () -> guarded.writeWithReceipt(List.of("k", "sk", "v"),
                List.of(new Object[]{2L, 1L, 1L}, new Object[]{2L, 2L, 2L})));
        assertThrows(BatchRejectedException.class, () -> guarded.writeWithReceipt(List.of("k", "sk", "v"),
                List.<Object[]>of(new Object[]{2L, new BigInteger("9223372036854775808"), 1L})));
        assertEquals(0, borrowed.get()); assertEquals(1, count(target, "im"));
        assertThrows(IllegalArgumentException.class, () -> guarded.prepareUpsert(target, "im", List.of("k", "sk", "v"), List.of("v", "v")));
        // The server silently clamps out-of-range civil temporals; the writer rejects them before borrowing a connection.
        var temporal = new ClickhouseUpsertWriter(counting); temporal.configure(OPTIONS); temporal.configureBatchLimits(LIMITS);
        temporal.prepareUpsert(target, "s", FIELDS, KEYS); borrowed.set(0);
        assertThrows(BatchRejectedException.class, () -> temporal.writeWithReceipt(FIELDS, List.<Object[]>of(new Object[]{
                9L, 9L, null, null, null, LocalDateTime.of(2299, 12, 31, 23, 59, 59, 999999999).plusNanos(1), null, "x", BigInteger.ZERO})));
        assertEquals(0, borrowed.get()); assertEquals(0, count(target, "s"));
    }

    @Test void successfulMutationWithLostResponseRemainsUnknownAndIsNotRetried() throws Exception {
        family(target);
        sql("INSERT INTO " + table(target) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0)");
        var failure = new AtomicBoolean(true); var writer = new ClickhouseUpsertWriter(ds(failure, true));
        writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "s", FIELDS, KEYS);
        var exception = assertThrows(UnconfirmedWriteException.class, () -> writer.writeWithReceipt(FIELDS,
                List.<Object[]>of(new Object[]{1L, 1L, 7L, null, "committed", null, null, "c", BigInteger.ZERO})));
        assertNull(exception.getCause()); assertFalse(failure.get());
        assertEquals("committed", readRows(target).getFirst().split("\\|")[4], "The mutation was server-committed");
        // Replay of the same row converges through the now existing values.
        var writer2 = new ClickhouseUpsertWriter(ds(null)); writer2.configure(OPTIONS); writer2.configureBatchLimits(LIMITS);
        writer2.prepareUpsert(target, "s", FIELDS, KEYS);
        assertEquals(1, writer2.writeWithReceipt(FIELDS,
                List.<Object[]>of(new Object[]{1L, 1L, 7L, null, "committed", null, null, "c", BigInteger.ZERO})).acknowledgedRows());
        assertEquals(1, count(target));
    }

    @Test void publicServiceRunsAPinnedCursorAndReplaysTheAppliedWatermarkGroup() throws Exception {
        family(source);
        sql("INSERT INTO " + table(source) + " VALUES (1,1,1,NULL,'a',NULL,NULL,'x',0),(1,2,2,NULL,'b',NULL,NULL,'y',1),(2,1,3,NULL,'c',NULL,NULL,'z',2)");
        sql("CREATE TABLE " + name(target, "t") + FAMILY);
        var request = request();
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "s", PROJECTION, "k,id"), rows(target, "t", PROJECTION, "k,id"));
        sql("INSERT INTO " + table(source) + " VALUES (2,0,4,NULL,'late',NULL,NULL,'w',3),(3,1,5,NULL,'new',NULL,NULL,'n',4)");
        request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(rows(source, "s", PROJECTION, "k,id"), rows(target, "t", PROJECTION, "k,id"));
    }

    private MigrationRequest request() {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.CLICKHOUSE); input.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        input.setUrl(url()); input.setUsername("default"); input.setPassword(""); input.setPoolSize(2); input.setFetchSize(2);
        input.setSourceSchema(source); input.setSourceIncludes("s"); input.setIncreaseColumnName("k");
        input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^s$", "t"))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.CLICKHOUSE);
        output.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        output.setUrl(url()); output.setUsername("default"); output.setPassword(""); output.setPoolSize(2); output.setTargetSchema(target);
        output.setBatchLimits(LIMITS); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("s", binding(this::save)));
        return request;
    }
    private void transfer(String sourceTable, String targetTable, List<String> fields, List<String> keys, String watermark,
            int fetch, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.CLICKHOUSE, source, sourceTable,
                target, targetTable, fields, fields, keys, keys, watermark, LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("c".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private static void family(String db) throws Exception { sql("CREATE TABLE " + name(db, "s") + FAMILY); }
    private void keyFamily(String table, String ddl) throws Exception {
        sql("CREATE TABLE " + name(source, table) + " " + ddl);
        sql("CREATE TABLE " + name(target, table.replace("_s", "_t")) + " " + ddl);
    }
    private static String name(String db, String table) { return ClickhouseSql.table(db, table); }
    private static String table(String db) { return name(db, "s"); }
    private static String url() { return System.getProperty("datahub.test.clickhouse.url"); }
    private static Connection open() throws SQLException { return DriverManager.getConnection(url(), "default", ""); }
    private static void sql(String... commands) throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) { statement.setQueryTimeout(60); for (var sql : commands) statement.execute(sql); }
    }
    private static long count(String db) throws Exception { return count(db, "s"); }
    private static long count(String db, String table) throws Exception {
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count() FROM " + name(db, table))) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
    private static List<List<String>> rows(String db, String table, String projection, String order) throws Exception {
        var result = new ArrayList<List<String>>();
        try (var connection = open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT " + projection + " FROM " + name(db, table) + " ORDER BY " + order)) {
            while (rows.next()) {
                var values = new ArrayList<String>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) values.add(text(rows.getObject(i)));
                result.add(values);
            }
            return result;
        }
    }
    private static List<String> readRows(String db) throws Exception {
        return rows(db, "s", PROJECTION, "k,id").stream().map(values -> String.join("|", values)).toList();
    }
    private static String text(Object value) {
        if (value == null) return "NULL";
        return value instanceof BigDecimal decimal ? decimal.toPlainString()
                : value instanceof LocalDateTime time ? time.toString()
                : value instanceof LocalDate date ? date.toString() : String.valueOf(value);
    }
    /** Owned connections that fail on JDBC transaction control; an armed fault loses one write acknowledgement after the real write. */
    private static DataSource ds(AtomicBoolean fault) { return ds(fault, false); }
    private static DataSource ds(AtomicBoolean fault, boolean mutate) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("getAutoCommit", "setAutoCommit", "commit", "rollback").contains(method.getName()))
                        throw new AssertionError("Unexpected ClickHouse JDBC transaction control");
                    try {
                        Object value = method.invoke(real, args);
                        // The upsert writer sends one statement per row operation: lose its response after the real send.
                        if (fault != null && method.getName().equals("createStatement")) {
                            var statement = (Statement) value;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (p, m, a) -> {
                                try {
                                    Object result = m.invoke(statement, a);
                                    if (m.getName().equals("execute") && a.length == 1 && a[0] instanceof String sent
                                            && sent.startsWith(mutate ? "ALTER TABLE" : "INSERT INTO")
                                            && fault.compareAndSet(true, false))
                                        throw new SQLException("injected response loss after actual write");
                                    return result;
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
