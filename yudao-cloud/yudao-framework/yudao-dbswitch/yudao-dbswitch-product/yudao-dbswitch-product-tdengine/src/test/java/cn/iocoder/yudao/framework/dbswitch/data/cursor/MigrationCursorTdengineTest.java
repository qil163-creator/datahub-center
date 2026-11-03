package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.TdengineSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.TdengineDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineTimestamp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real TDengine 3.3.8.8 (WebSocket driver) typed cursor: non-snapshot keyset, deployed generation pin, overwrite target. */
@Timeout(240)
@EnabledIfSystemProperty(named = "datahub.test.tdengine.ws.url", matches = "jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfEnvironmentVariable(named = "DBSWITCH_TEST_TDENGINE_PASSWORD", matches = ".+")
class MigrationCursorTdengineTest {
    private static final BatchLimits LIMITS = new BatchLimits(3, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0);
    /** Recent nanosecond epoch inside the default KEEP window, with sub-microsecond digits. */
    private static final long T0 = (System.currentTimeMillis() / 1000 - 7200) * 1_000_000_000L + 123_456_789L;
    private static final List<String> FAMILY = List.of("tbname", "ts", "v", "note", "site", "g");
    private static final List<String> FAMILY_KEYS = List.of("ts", "tbname");
    private String source, target;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> databases = new ArrayList<>();
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = database("tdc_s_" + id, "ns"); target = database("tdc_t_" + id, "ns");
        try (var c = open()) { pin = TdengineSourceGeneration.initialize(c, source, 15); }
        runtime = DbSwitchRuntime.standalone();
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally { for (String database : databases) sql("DROP DATABASE IF EXISTS " + database); }
    }

    @Test void supertableKeysetPagesEqualTimestampChildrenAndReplaysTheAppliedGroup() throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + source + ".c_b USING " + source + ".st TAGS ('b', 2) VALUES (" + T0 + ", 10, 'b0') (" + (T0 + 2) + ", 12, 'b2') "
                + source + ".c_a USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 20, 'a0') (" + (T0 + 1) + ", 21, 'a1') "
                + source + ".`C_a` USING " + source + ".st TAGS ('A', 3) VALUES (" + T0 + ", 30, 'C0') (" + (T0 + 2) + ", 32, 'C2')");
        // Page size 2 splits the three-child timestamp group; binary tbname order puts C_a before c_a.
        transfer("st", "st", FAMILY, FAMILY_KEYS, 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of(Long.toString(T0 + 2), "c_b"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals("TIMESTAMP(ns)", saved.get().applied().values().getFirst().typeName());
        assertEquals(familyRows(source), familyRows(target));
        assertEquals(List.of("C_a|A|3", "c_a|a|1", "c_b|b|2"), tags(target));
        // Equal-watermark rows of other children that became visible later are replayed; a newer row follows.
        sql("INSERT INTO " + source + ".c_0 USING " + source + ".st TAGS ('z', 9) VALUES (" + (T0 + 2) + ", 40, 'late') "
                + source + ".`A_late` USING " + source + ".st TAGS ('L', 8) VALUES (" + (T0 + 2) + ", 41, 'late') "
                + source + ".c_a VALUES (" + (T0 + 5) + ", 25, 'a5')");
        // Older than the applied watermark: outside the cursor window by contract (needs DIFF or an explicit rewind).
        sql("INSERT INTO " + source + ".c_b VALUES (" + (T0 + 1) + ", 11, 'old')");
        transfer("st", "st", FAMILY, FAMILY_KEYS, 2, this::save);
        var expected = familyRows(source); expected.removeIf(row -> row.equals(List.of("c_b", Long.toString(T0 + 1), "11", "old", "b", "2")));
        assertEquals(expected, familyRows(target));
        assertEquals(List.of("A_late|L|8", "C_a|A|3", "c_0|z|9", "c_a|a|1", "c_b|b|2"), tags(target));
        long sequence = saved.get().sequence();
        transfer("st", "st", FAMILY, FAMILY_KEYS, 2, this::save);
        assertEquals(expected, familyRows(target)); assertTrue(saved.get().sequence() > sequence, "The applied timestamp group is replayed idempotently");
    }

    @Test void subPrecisionWatermarksStayExactAndAnotherTargetPrecisionIsRejected() throws Exception {
        String us = database(source + "_us", "us"), usTarget = database(target + "_us", "us"), ms = database(target + "_ms", "ms");
        for (String db : List.of(source, target, us, usTarget, ms)) sql("CREATE TABLE " + db + ".n (ts TIMESTAMP, v BIGINT)");
        sql("INSERT INTO " + source + ".n VALUES (" + T0 + ", 1) (" + (T0 + 1) + ", 2) (" + (T0 + 999) + ", 3)");
        transfer("n", "n", List.of("ts", "v"), List.of("ts"), 2, this::save);
        assertEquals(List.of(List.of(Long.toString(T0), "1"), List.of(Long.toString(T0 + 1), "2"), List.of(Long.toString(T0 + 999), "3")), rows(target, "n", "CAST(ts AS BIGINT),v", "ts"));
        assertEquals(Long.toString(T0 + 999), saved.get().applied().values().getFirst().value());
        long micros = T0 / 1000;
        sql("INSERT INTO " + us + ".n VALUES (" + micros + ", 7) (" + (micros + 1) + ", 8)");
        SourceGenerationPin usPin; try (var c = open()) { usPin = TdengineSourceGeneration.initialize(c, us, 15); }
        var usSaved = new AtomicReference<CursorCheckpoint>();
        CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.TDENGINE, ProductTypeEnum.TDENGINE, us, "n", usTarget, "n",
                List.of("ts", "v"), List.of("ts", "v"), List.of("ts"), List.of("ts"), "ts", LIMITS, 2,
                new CursorRunBinding("e".repeat(64), null, value -> { usSaved.set(value); return true; }, usPin), new DbSwitchTableResult(), () -> { });
        assertEquals(List.of(List.of(Long.toString(micros), "7"), List.of(Long.toString(micros + 1), "8")), rows(usTarget, "n", "CAST(ts AS BIGINT),v", "ts"));
        assertEquals("TIMESTAMP(us)", usSaved.get().applied().values().getFirst().typeName());
        // The key contract binds the database precision: nanosecond keys cannot be collapsed into a millisecond target.
        saved.set(null);
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.TDENGINE, ProductTypeEnum.TDENGINE,
                source, "n", ms, "n", List.of("ts", "v"), List.of("ts", "v"), List.of("ts"), List.of("ts"), "ts", LIMITS, 2, binding(this::save),
                new DbSwitchTableResult(), () -> { }));
        assertEquals(0, count(ms, "n"));
    }

    @Test void unsignedCompositeKeyOrdersEveryPageBoundaryAndReplaysSmallerKeysOfTheAppliedTimestamp() throws Exception {
        for (String db : List.of(source, target)) sql("CREATE TABLE " + db + ".k (ts TIMESTAMP, id BIGINT UNSIGNED COMPOSITE KEY, v INT)");
        sql("INSERT INTO " + source + ".k VALUES (" + T0 + ", 18446744073709551615, 1) (" + T0 + ", 0, 2) (" + T0 + ", 1, 3) (" + (T0 + 1) + ", 5, 4)");
        transfer("k", "k", List.of("ts", "id", "v"), List.of("ts", "id"), 1, this::save);
        assertEquals(rows(source, "k", "CAST(ts AS BIGINT),id,v", "ts,id"), rows(target, "k", "CAST(ts AS BIGINT),id,v", "ts,id"));
        var applied = saved.get().applied().values();
        assertEquals(List.of(Long.toString(T0 + 1), "5"), applied.stream().map(v -> v.value()).toList()); assertEquals(Types.NUMERIC, applied.get(1).jdbcType());
        sql("INSERT INTO " + source + ".k VALUES (" + (T0 + 1) + ", 3, 5)");
        transfer("k", "k", List.of("ts", "id", "v"), List.of("ts", "id"), 1, this::save);
        assertEquals(rows(source, "k", "CAST(ts AS BIGINT),id,v", "ts,id"), rows(target, "k", "CAST(ts AS BIGINT),id,v", "ts,id"));
        assertEquals(5, count(target, "k"));
    }

    @Test void fixedUpperBoundIgnoresRowsArrivingDuringTheRunUntilTheNextRun() throws Exception {
        family(source);
        sql("INSERT INTO " + source + ".c1 USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 1, 'x') (" + (T0 + 1) + ", 2, 'x')");
        var operator = new TdengineDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(1);
        var read = new ArrayList<String>();
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "st", FAMILY, "ts", FAMILY_KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals(List.of(Long.toString(T0 + 1), "c1"), cursor.upperBound().values().stream().map(v -> v.value()).toList());
            while (cursor.next()) {
                assertInstanceOf(TdengineTimestamp.class, cursor.row()[1]);
                read.add(cursor.row()[0] + "@" + cursor.row()[1]);
                if (read.size() == 1) sql("INSERT INTO " + source + ".c0 USING " + source + ".st TAGS ('b', 2) VALUES (" + (T0 + 1) + ", 3, 'y') (" + (T0 + 9) + ", 4, 'y')");
            }
        }
        // c0@T0+1 sorts before the frozen (T0+1,c1) bound and became visible before its page; T0+9 waits for the next run.
        assertEquals(List.of("c1@" + T0, "c0@" + (T0 + 1), "c1@" + (T0 + 1)), read);
    }

    @Test void completedEmptyWindowRemainsFrozenUntilAnExplicitNextRun() throws Exception {
        for (String db : List.of(source, target)) sql("CREATE TABLE " + db + ".n (ts TIMESTAMP, v BIGINT)");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("n", "n", List.of("ts", "v"), List.of("ts"), 2, value -> calls.incrementAndGet() == 1 && save(value)));
        assertNull(saved.get().fixedUpper()); assertFalse(saved.get().complete());
        sql("INSERT INTO " + source + ".n VALUES (" + T0 + ", 1)");
        transfer("n", "n", List.of("ts", "v"), List.of("ts"), 2, this::save);
        assertEquals(0, count(target, "n")); assertTrue(saved.get().complete());
        transfer("n", "n", List.of("ts", "v"), List.of("ts"), 2, this::save);
        assertEquals(1, count(target, "n"));
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysAsAnIdempotentOverwrite() throws Exception {
        for (String db : List.of(source, target)) sql("CREATE TABLE " + db + ".n (ts TIMESTAMP, v BIGINT, s NCHAR(8), raw VARBINARY(4))");
        sql("INSERT INTO " + source + ".n VALUES (" + T0 + ", 1, '一', '\\x00ff') (" + (T0 + 1) + ", 2, NULL, NULL) (" + (T0 + 2) + ", 3, 'c', '\\x01') ("
                + (T0 + 3) + ", 4, 'd', NULL) (" + (T0 + 4) + ", 5, 'e', '\\x02')");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("n", "n", List.of("ts", "v", "s", "raw"), List.of("ts"), 2,
                value -> calls.incrementAndGet() != 3 && save(value)));
        assertEquals(4, count(target, "n")); assertFalse(saved.get().complete()); assertEquals(1, saved.get().sequence());
        // A stale value at an applied key is overwritten by the replay, not duplicated.
        sql("INSERT INTO " + target + ".n VALUES (" + (T0 + 1) + ", 99, 'stale', '\\x09')");
        transfer("n", "n", List.of("ts", "v", "s", "raw"), List.of("ts"), 2, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "n", "CAST(ts AS BIGINT),v,s,raw", "ts"), rows(target, "n", "CAST(ts AS BIGINT),v,s,raw", "ts"));
    }

    @Test void lostInsertAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        for (String db : List.of(source, target)) sql("CREATE TABLE " + db + ".n (ts TIMESTAMP, v BIGINT)");
        sql("INSERT INTO " + source + ".n VALUES (" + T0 + ", 1) (" + (T0 + 1) + ", 2)");
        var fault = new AtomicBoolean(true);
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(fault), ProductTypeEnum.TDENGINE,
                ProductTypeEnum.TDENGINE, source, "n", target, "n", List.of("ts", "v"), List.of("ts", "v"), List.of("ts"), List.of("ts"), "ts",
                LIMITS, 2, binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(fault.get()); assertEquals(1, count(target, "n"), "The injected loss happened after a real write");
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer("n", "n", List.of("ts", "v"), List.of("ts"), 2, this::save);
        assertEquals(rows(source, "n", "CAST(ts AS BIGINT),v", "ts"), rows(target, "n", "CAST(ts AS BIGINT),v", "ts"));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "DATABASE_RECREATE", "TABLE_RECREATE", "TAG_CHANGE", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationRelationOrChildTagsBeforeWriting(String change) throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + source + ".c1 USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 1, 'x')");
        sql("CREATE TABLE " + target + ".c1 USING " + target + ".st TAGS ('a', 1)");
        transfer("c1", "c1", List.of("ts", "v", "note"), List.of("ts"), 2, this::save);
        assertEquals(1, count(target, "c1"));
        switch (change) {
            case "PIN_ROTATE" -> { try (var c = open()) { pin = TdengineSourceGeneration.rotate(c, pin, UUID.randomUUID().toString(), 15); } }
            case "DATABASE_RECREATE" -> { sql("DROP DATABASE " + source); sql("CREATE DATABASE " + source + " VGROUPS 1 BUFFER 8 PRECISION 'ns'"); family(source);
                sql("INSERT INTO " + source + ".c1 USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 1, 'x')"); }
            case "TABLE_RECREATE" -> { sql("DROP TABLE " + source + ".c1"); sql("INSERT INTO " + source + ".c1 USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 1, 'x')"); }
            case "TAG_CHANGE" -> sql("ALTER TABLE " + source + ".c1 SET TAG site='changed'");
            case "TARGET_RECREATE" -> { sql("DROP TABLE " + target + ".c1"); sql("CREATE TABLE " + target + ".c1 USING " + target + ".st TAGS ('a', 1)"); }
            default -> { }
        }
        sql("INSERT INTO " + source + ".c1 VALUES (" + (T0 + 1) + ", 2, 'new')");
        long before = count(target, "c1");
        if (change.equals("NONE")) {
            // Control: the unchanged generation, relation and TAGs resume and deliver the new row.
            transfer("c1", "c1", List.of("ts", "v", "note"), List.of("ts"), 2, this::save);
            assertEquals(before + 1, count(target, "c1")); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("c1", "c1", List.of("ts", "v", "note"), List.of("ts"), 2, this::save));
        assertEquals(before, count(target, "c1"));
    }

    @Test void unsupportedSourceShapesAndMissingGenerationFailBeforeReading() throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + source + ".c1 USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 1, 'x')");
        sql("CREATE TABLE " + source + ".kv (ts TIMESTAMP, id VARCHAR(8) COMPOSITE KEY, v INT)");
        sql("CREATE TABLE " + source + ".k (ts TIMESTAMP, id INT COMPOSITE KEY, v INT)");
        sql("CREATE STABLE " + source + ".sj (ts TIMESTAMP, v INT) TAGS (j JSON)");
        sql("INSERT INTO " + source + ".j1 USING " + source + ".sj TAGS ('{\"k\":1}') VALUES (" + T0 + ", 1)");
        SourceGenerationPin foreign; try (var c = open()) { foreign = TdengineSourceGeneration.initialize(c, target, 15); }
        var operator = new TdengineDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(2);
        var rejected = List.<CursorReadRequest>of(
                new CursorReadRequest(source, "st", FAMILY, "ts", FAMILY_KEYS, null, null, null, LIMITS, false, null),
                new CursorReadRequest(source, "st", FAMILY, "ts", FAMILY_KEYS, null, null, null, LIMITS, false, foreign),
                new CursorReadRequest(source, "st", FAMILY, "v", List.of("v", "tbname"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "st", List.of("ts", "v"), "ts", List.of("ts"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "c1", List.of("ts", "v", "site"), "ts", List.of("ts"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "c1", List.of("tbname", "ts", "v"), "ts", List.of("ts", "tbname"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "kv", List.of("ts", "id", "v"), "ts", List.of("ts", "id"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "k", List.of("ts", "id", "v"), "ts", List.of("ts"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "sj", List.of("tbname", "ts", "v", "j"), "ts", FAMILY_KEYS, null, null, null, LIMITS, false, pin));
        for (var request : rejected) assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request), request.table() + request.primaryKeys());
        // Controls on the same fixtures: only the rejected projection, key, watermark or pin differs.
        for (var request : List.of(new CursorReadRequest(source, "st", FAMILY, "ts", FAMILY_KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "c1", List.of("ts", "v", "note"), "ts", List.of("ts"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "sj", List.of("tbname", "ts", "v"), "ts", FAMILY_KEYS, null, null, null, LIMITS, false, pin)))
            try (var cursor = operator.openCursor(request)) { assertTrue(cursor.next(), request.table()); }
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "k", List.of("ts", "id", "v"), "ts", List.of("ts", "id"), null, null, null, LIMITS, false, pin))) {
            assertFalse(cursor.next()); assertNull(cursor.upperBound());
        }
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.TDENGINE, ProductTypeEnum.TDENGINE,
                source, "st", target, "st", FAMILY, FAMILY, FAMILY_KEYS, FAMILY_KEYS, "ts", LIMITS, 2,
                new CursorRunBinding("e".repeat(64), null, this::save, foreign), new DbSwitchTableResult(), () -> { }));
        assertEquals(0, count(target, "st")); assertNull(saved.get());
    }

    @Test void supertableTargetRejectsDifferentlyTaggedOrForeignChildrenBeforeAnyWrite() throws Exception {
        family(source); family(target);
        sql("INSERT INTO " + source + ".c1 USING " + source + ".st TAGS ('a', 1) VALUES (" + T0 + ", 1, 'x') "
                + source + ".c2 USING " + source + ".st TAGS ('b', 2) VALUES (" + T0 + ", 2, 'y')");
        sql("INSERT INTO " + target + ".c1 USING " + target + ".st TAGS ('different', 1) VALUES (" + (T0 - 1) + ", 7, 'keep')");
        assertThrows(IllegalStateException.class, () -> transfer("st", "st", FAMILY, FAMILY_KEYS, 3, this::save));
        assertEquals(List.of(List.of("c1", Long.toString(T0 - 1), "7", "keep", "different", "1")), familyRows(target));
        sql("DROP TABLE " + target + ".c1"); sql("CREATE TABLE " + target + ".c2 (ts TIMESTAMP, v BIGINT, note NCHAR(8))");
        saved.set(null);
        assertThrows(IllegalStateException.class, () -> transfer("st", "st", FAMILY, FAMILY_KEYS, 3, this::save));
        assertEquals(0, count(target, "st")); assertEquals(0, count(target, "c2"));
        // One batch cannot route two TAG sets into one child, and every TAG must be present for routing.
        var writer = new TdengineUpsertWriter(ds(null)); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "st", FAMILY, FAMILY_KEYS);
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FAMILY, List.of(
                new Object[]{"c9", new TdengineTimestamp(T0, 9), 1L, "x", "a", 1}, new Object[]{"c9", new TdengineTimestamp(T0 + 1, 9), 2L, "x", "b", 1})));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(target, "st", List.of("tbname", "ts", "v", "site"), FAMILY_KEYS));
        assertEquals(0, count(target, "st"));
    }

    @Test void supertableRoutingCreatesChildrenWithExactTimestampBinaryFloatBoolTextAndNullTags() throws Exception {
        for (String db : List.of(source, target)) sql("CREATE STABLE " + db + ".rt (ts TIMESTAMP, v INT, raw VARBINARY(4), t2 TIMESTAMP, u SMALLINT UNSIGNED) "
                + "TAGS (tt TIMESTAMP, bt VARCHAR(8), vb VARBINARY(4), fl FLOAT, bl BOOL, nc NCHAR(4))");
        sql("INSERT INTO " + source + ".r1 USING " + source + ".rt TAGS (" + (T0 + 3) + ", 'a''b', '\\x00ff', 1.5, true, '中') VALUES (" + T0 + ", 1, '\\x01', " + (T0 + 7) + ", 65535) "
                + source + ".r2 USING " + source + ".rt TAGS (NULL, NULL, NULL, NULL, NULL, NULL) VALUES (" + T0 + ", 2, NULL, NULL, 0)");
        var fields = List.of("tbname", "ts", "v", "raw", "t2", "u", "tt", "bt", "vb", "fl", "bl", "nc");
        transfer("rt", "rt", fields, FAMILY_KEYS, 2, this::save);
        String projection = "tbname,CAST(ts AS BIGINT),v,raw,CAST(t2 AS BIGINT),u,CAST(tt AS BIGINT),bt,vb,fl,bl,nc";
        var expected = rows(source, "rt", projection, "ts,tbname");
        assertEquals(List.of("r1", Long.toString(T0), "1", "01", Long.toString(T0 + 7), "65535", Long.toString(T0 + 3), "a'b", "00ff", "1.5", "true", "中"), expected.getFirst());
        assertEquals(expected, rows(target, "rt", projection, "ts,tbname"));
        // Replaying into the now existing children compares their TAG evidence and overwrites the same keys.
        saved.set(null);
        transfer("rt", "rt", fields, FAMILY_KEYS, 2, this::save);
        assertEquals(expected, rows(target, "rt", projection, "ts,tbname"));
    }

    @Test void writerRequiresTimestampUnitEvidenceBeforeBorrowingAConnection() throws Exception {
        sql("CREATE TABLE " + target + ".n (ts TIMESTAMP, v BIGINT)");
        var borrowed = new AtomicInteger(); var delegate = ds(null);
        var counting = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { borrowed.incrementAndGet(); return delegate.getConnection(); }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        };
        var writer = new TdengineUpsertWriter(counting); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "n", List.of("ts", "v"), List.of("ts")); borrowed.set(0);
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(List.of("ts", "v"), List.<Object[]>of(new Object[]{T0, 1L})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(List.of("ts", "v"), List.<Object[]>of(new Object[]{new TdengineTimestamp(T0 / 1_000_000, 3), 1L})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(List.of("ts", "v"), List.<Object[]>of(new Object[]{new TdengineTimestamp(T0, 9), 1L}, new Object[]{new TdengineTimestamp(T0, 9), 2L})));
        assertEquals(0, borrowed.get()); assertEquals(0, count(target, "n"));
        var instant = Instant.ofEpochSecond(T0 / 1_000_000_000L, T0 % 1_000_000_000L);
        assertEquals(2, writer.writeWithReceipt(List.of("ts", "v"), List.of(new Object[]{Timestamp.from(instant), 1L}, new Object[]{new TdengineTimestamp(T0 + 1, 9), 2L})).acknowledgedRows());
        assertEquals(List.of(List.of(Long.toString(T0), "1"), List.of(Long.toString(T0 + 1), "2")), rows(target, "n", "CAST(ts AS BIGINT),v", "ts"));
    }

    @Test void publicServiceRunsAPinnedLeafCursorAndReplaysTheAppliedTimestamp() throws Exception {
        for (String db : List.of(source, target)) sql("CREATE TABLE " + db + "." + (db.equals(source) ? "s" : "t") + " (ts TIMESTAMP, id INT COMPOSITE KEY, v DOUBLE, d DECIMAL(20,4))");
        sql("INSERT INTO " + source + ".s VALUES (" + T0 + ", 2, 1.5, 12.3400) (" + T0 + ", 1, 2.5, -0.0001) (" + (T0 + 7) + ", 1, 3.5, 1.0000)");
        var request = request(ProductTypeEnum.TDENGINE, target, "t");
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertTrue(saved.get().complete()); assertEquals(rows(source, "s", "CAST(ts AS BIGINT),id,v,d", "ts,id"), rows(target, "t", "CAST(ts AS BIGINT),id,v,d", "ts,id"));
        sql("INSERT INTO " + source + ".s VALUES (" + (T0 + 7) + ", 0, 4.5, 2.0000)");
        request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(rows(source, "s", "CAST(ts AS BIGINT),id,v,d", "ts,id"), rows(target, "t", "CAST(ts AS BIGINT),id,v,d", "ts,id"));
    }

    @Test @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void postgresTargetKeepsExactEpochAndUnsignedKeysWhileTemporalAndReverseMappingsAreRejected() throws Exception {
        Class.forName("org.postgresql.Driver");
        String pgUrl = System.getProperty("datahub.test.postgres.url"), pgUser = System.getProperty("datahub.test.postgres.user", "dataforge_test"),
                pgPassword = System.getProperty("datahub.test.postgres.password", "");
        sql("CREATE TABLE " + source + ".s (ts TIMESTAMP, id BIGINT UNSIGNED COMPOSITE KEY, v DOUBLE, d DECIMAL(20,4), s NCHAR(8), b BOOL, raw VARBINARY(8), t2 TIMESTAMP)");
        sql("INSERT INTO " + source + ".s VALUES (" + T0 + ", 18446744073709551615, 1.25, 12.3400, '汉字', true, '\\x00ff', " + (T0 + 5) + ") ("
                + T0 + ", 0, NULL, NULL, NULL, NULL, NULL, NULL) (" + (T0 + 1) + ", 7, -2.5, -0.0001, 'x', false, '\\x01', " + T0 + ")");
        String schema = "tdc_pg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), quoted = '"' + schema + '"';
        try (var pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
            pgSql(pg, "CREATE SCHEMA " + quoted);
            try {
                pgSql(pg, "CREATE TABLE " + quoted + ".t (ts bigint NOT NULL, id numeric(20,0) NOT NULL, v double precision, d numeric(20,4), s varchar(8), b boolean, raw bytea, t2 bigint, PRIMARY KEY (ts, id))");
                var request = request(ProductTypeEnum.POSTGRESQL, schema, "t");
                var output = request.getTarget(); output.setUrl(pgUrl); output.setUsername(pgUser); output.setPassword(pgPassword); output.setDriverClassName("org.postgresql.Driver");
                var result = new MigrationService(request, ignored -> { }, null, runtime).run();
                assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
                assertEquals(ProductTypeEnum.POSTGRESQL, saved.get().targetProduct()); assertTrue(saved.get().complete());
                var expected = List.of(List.of(Long.toString(T0), "0", "null", "null", "null", "null", "null", "null"),
                        List.of(Long.toString(T0), "18446744073709551615", "1.25", "12.3400", "汉字", "true", "00ff", Long.toString(T0 + 5)),
                        List.of(Long.toString(T0 + 1), "7", "-2.5", "-0.0001", "x", "false", "01", Long.toString(T0)));
                assertEquals(expected, pgRows(pg, quoted + ".t"));
                sql("INSERT INTO " + source + ".s VALUES (" + (T0 + 1) + ", 3, 9.0, 1.0000, 'y', true, NULL, NULL)");
                request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
                assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
                assertEquals(4, pgRows(pg, quoted + ".t").size());
                // A PostgreSQL timestamp key has no proven nanosecond epoch mapping; reverse integer keys carry no TDengine unit.
                pgSql(pg, "CREATE TABLE " + quoted + ".z (ts timestamptz NOT NULL, id numeric(20,0) NOT NULL, v double precision, PRIMARY KEY (ts, id))");
                var pgSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(pgUrl, pgUser, pgPassword);
                saved.set(null);
                assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, ds(null), pgSource, ProductTypeEnum.TDENGINE, ProductTypeEnum.POSTGRESQL,
                        source, "s", schema, "z", List.of("ts", "id", "v"), List.of("ts", "id", "v"), List.of("ts", "id"), List.of("ts", "id"), "ts", LIMITS, 2,
                        binding(this::save), new DbSwitchTableResult(), () -> { }));
                assertTrue(pgRows(pg, quoted + ".z").isEmpty());
                pgSql(pg, "CREATE TABLE " + quoted + ".r (ts bigint NOT NULL PRIMARY KEY, v bigint NOT NULL)");
                pgSql(pg, "INSERT INTO " + quoted + ".r VALUES (" + T0 + ", 1)");
                sql("CREATE TABLE " + target + ".r (ts TIMESTAMP, v BIGINT)");
                assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, pgSource, ds(null), ProductTypeEnum.POSTGRESQL, ProductTypeEnum.TDENGINE,
                        schema, "r", target, "r", List.of("ts", "v"), List.of("ts", "v"), List.of("ts"), List.of("ts"), "ts", LIMITS, 2,
                        new CursorRunBinding("f".repeat(64), null, value -> true), new DbSwitchTableResult(), () -> { }));
                assertEquals(0, count(target, "r"));
            } finally { pgSql(pg, "DROP SCHEMA " + quoted + " CASCADE"); }
        }
    }

    private MigrationRequest request(ProductTypeEnum targetProduct, String targetSchema, String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.TDENGINE); input.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        input.setUrl(url()); input.setUsername("root"); input.setPassword(password()); input.setPoolSize(2); input.setFetchSize(2);
        input.setSourceSchema(source); input.setSourceIncludes("s"); input.setIncreaseColumnName("ts"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^s$", targetTable))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(targetProduct); output.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        output.setUrl(url()); output.setUsername("root"); output.setPassword(password()); output.setPoolSize(2); output.setTargetSchema(targetSchema);
        output.setBatchLimits(LIMITS); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("s", binding(this::save)));
        return request;
    }
    private void transfer(String sourceTable, String targetTable, List<String> fields, List<String> keys, int fetch, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.TDENGINE, ProductTypeEnum.TDENGINE, source, sourceTable, target, targetTable,
                fields, fields, keys, keys, "ts", LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private String database(String name, String precision) throws Exception {
        sql("CREATE DATABASE " + name + " VGROUPS 1 BUFFER 8 PRECISION '" + precision + "'"); databases.add(name); return name;
    }
    private static void family(String db) throws Exception { sql("CREATE STABLE " + db + ".st (ts TIMESTAMP, v BIGINT, note NCHAR(8)) TAGS (site NCHAR(16), g INT)"); }
    private List<List<String>> familyRows(String db) throws Exception { return rows(db, "st", "tbname,CAST(ts AS BIGINT),v,note,site,g", "ts,tbname"); }
    private List<String> tags(String db) throws Exception {
        return rows(db, "st", "TAGS tbname,site,g", null).stream().map(row -> String.join("|", row)).sorted().toList();
    }
    private static List<List<String>> rows(String db, String table, String projection, String order) throws Exception {
        String select = projection.startsWith("TAGS ") ? "SELECT " + projection + " FROM " + db + "." + table : "SELECT " + projection + " FROM " + db + "." + table + " ORDER BY " + order;
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery(select)) {
            var result = new ArrayList<List<String>>();
            while (r.next()) {
                var row = new ArrayList<String>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(text(r.getObject(i)));
                result.add(row);
            }
            return result;
        }
    }
    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            var decoded = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return Arrays.equals(decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes) && decoded.chars().allMatch(ch -> ch >= 0x20) ? decoded : HexFormat.of().formatHex(bytes);
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
    }
    private static long count(String db, String table) throws Exception {
        // An empty supertable can answer an aggregate with no row at all.
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + db + "." + table)) { return r.next() ? r.getLong(1) : 0; }
    }
    private static List<List<String>> pgRows(Connection pg, String table) throws SQLException {
        try (var s = pg.createStatement(); var r = s.executeQuery("SELECT ts::text,id::text,v::text,d::text,s,b::text,encode(raw,'hex'),t2::text FROM " + table + " ORDER BY ts,id")) {
            var result = new ArrayList<List<String>>();
            while (r.next()) { var row = new ArrayList<String>(); for (int i = 1; i <= 8; i++) row.add(String.valueOf(r.getString(i))); result.add(row); }
            return result;
        } catch (SQLException missingColumns) {
            try (var s = pg.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                r.next(); return Collections.nCopies(r.getInt(1), List.of());
            }
        }
    }
    private static void pgSql(Connection pg, String text) throws SQLException { try (var s = pg.createStatement()) { s.setQueryTimeout(20); s.execute(text); } }
    private static String url() { return System.getProperty("datahub.test.tdengine.ws.url"); }
    private static String password() { return System.getenv("DBSWITCH_TEST_TDENGINE_PASSWORD"); }
    private static Connection open() throws SQLException { return DriverManager.getConnection(url(), "root", password()); }
    private static void sql(String text) throws Exception { try (var c = open(); var s = c.createStatement()) { s.setQueryTimeout(30); s.execute(text); } }
    /** Owned WebSocket connections that fail on JDBC transaction control; an armed fault loses one INSERT acknowledgement after the write. */
    private static DataSource ds(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Unexpected TDengine JDBC transaction control");
                    try {
                        Object value = method.invoke(real, args);
                        if (fault != null && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO")) {
                            var statement = (PreparedStatement) value;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    Object result = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && fault.compareAndSet(true, false)) throw new SQLException("injected response loss after actual write");
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
