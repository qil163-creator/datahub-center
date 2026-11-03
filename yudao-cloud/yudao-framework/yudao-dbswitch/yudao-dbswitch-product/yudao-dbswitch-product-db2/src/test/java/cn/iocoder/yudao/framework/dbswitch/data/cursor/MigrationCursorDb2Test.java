package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.Db2SourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DB2DatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.db2.Db2UpsertWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DB2 LUW 12.1.5.0 cursor and native MERGE upsert. SET CURRENT ISOLATION = RR (verified through the
 * CURRENT ISOLATION register; JCC 4.13.127 reports blanks for it after JDBC setTransactionIsolation,
 * and the table-level WITH RR clause is refused once WHERE/ORDER BY follow it). FETCH FIRST n ROWS ONLY
 * keyset paging over the exact ordered primary key; concurrent writers block for the snapshot lifetime.
 * The generation fence is re-read on a dedicated CS connection between pages. DB2 MERGE counts source
 * rows, not per-row effects; the receipt acknowledges inputs only. The verified endpoint profile is the
 * Community 12.1.5.0 image whose user schema is the default uppercase DB2INST1.
 */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.db2.url", matches = "jdbc:db2://127[.]0[.]0[.]1:[0-9]+/Q17DB")
@EnabledIfSystemProperty(named = "datahub.test.db2.user", matches = ".+")
class MigrationCursorDb2Test {
    private static String url;
    private Connection control;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> fields = List.of("WM", "K1", "K2", "V"), keys = List.of("K2", "K1");
    private final BatchLimits limits = new BatchLimits(2, 65536, 16384, 2000, 1000);
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();
    @BeforeAll static void driver() throws Exception {
        // The driver jar is not a compile dependency; it is provided through maven.test.additionalClasspath.
        Class.forName(ProductTypeEnum.DB2.getDriveClassName());
        url = System.getProperty("datahub.test.db2.url");
    }
    @BeforeEach void setup() throws Exception {
        control = open();
        try (var s = control.createStatement()) {
            try (var r = s.executeQuery("SELECT service_level FROM SYSIBMADM.ENV_INST_INFO")) {
                assertTrue(r.next()); assertEquals("DB2 v12.1.5.0", r.getString(1));
            }
            try (var r = s.executeQuery("VALUES CURRENT SCHEMA")) {
                assertTrue(r.next()); assertEquals("DB2INST1", r.getString(1).trim());
            }
        }
        pin = Db2SourceGeneration.initialize(control, "DB2INST1", 15); runtime = DbSwitchRuntime.standalone();
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); } finally { if (control != null) try (var c = control) {
            for (String table : List.of("S", "T", "nativevals", "identitygen", "temporal", "odd name")) try { sql(c, "DROP TABLE " + t(table)); } catch (SQLException ignored) { }
            if (pin != null) try { Db2SourceGeneration.drop(c, pin.schema(), 15); } catch (SQLException ignored) { }
        } }
    }
    private Connection open() throws SQLException { DriverManager.setLoginTimeout(15); return DriverManager.getConnection(url, System.getProperty("datahub.test.db2.user", "db2inst1"), password()); }
    private DataSource ds() { return new AbstractDataSource() { public Connection getConnection() throws SQLException { return open(); } public Connection getConnection(String u, String p) throws SQLException { throw new SQLException("Fixture principal is fixed"); } }; }
    private static String password() {
        String property = System.getProperty("datahub.test.db2.password");
        return property != null ? property : System.getenv("DATAHUB_DB2_TEST_PASSWORD");
    }
    private static void sql(Connection c, String value) throws SQLException { try (var s = c.createStatement()) { s.setQueryTimeout(15); s.execute(value); } }
    private String t(String name) { return "\"" + name + "\""; }
    private void create(String table) throws SQLException { sql(control, "CREATE TABLE " + t(table) + "(\"WM\" TIMESTAMP NOT NULL,\"K1\" BIGINT NOT NULL,\"K2\" DECIMAL(31,0) NOT NULL,\"V\" VARCHAR(80),PRIMARY KEY(\"K2\",\"K1\"))"); }
    private void rows(int count) throws SQLException { for (int i = 1; i <= count; i++) sql(control, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:00'," + i + "," + (i % 2) + ",'original')"); }
    private long count(String table) throws SQLException { try (var s = control.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + t(table))) { assertTrue(r.next()); return r.getLong(1); } }
    private CursorReadSession cursor() { return cursor(fields, keys, "WM", null, null); }
    private CursorReadSession cursor(List<String> names, List<String> keyNames, String watermark, cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor lower, String expected) { var reader = new DB2DatabaseOperator(ds()); reader.setFetchSize(1); return reader.openCursor(new CursorReadRequest("DB2INST1", "S", names, watermark, keyNames, lower, null, expected, limits, false, pin)); }
    private Db2UpsertWriter writer(String table, List<String> names, List<String> keyNames) { var writer = new Db2UpsertWriter(ds()); writer.configureBatchLimits(limits); writer.prepareUpsert("DB2INST1", table, names, keyNames); return writer; }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private DbSwitchTableResult transfer(DataSource target, CursorRunBinding.CheckpointStore store) throws Exception { var result = new DbSwitchTableResult(); CursorTransfer.execute(runtime, ds(), target, ProductTypeEnum.DB2, ProductTypeEnum.DB2, "DB2INST1", "S", "DB2INST1", "T", fields, fields, keys, keys, "WM", limits, 2, binding(store), result, () -> {}); return result; }
    private MigrationRequest request() { var r = new MigrationRequest(); var s = new SourceDataSourceProperties(); s.setSourceProductType(ProductTypeEnum.DB2); s.setUrl(url); s.setUsername(System.getProperty("datahub.test.db2.user", "db2inst1")); s.setPassword(password()); s.setPoolSize(2); s.setSourceSchema("DB2INST1"); s.setSourceIncludes("S"); s.setFetchSize(2); s.setIncreaseColumnName("WM"); s.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1"); s.setRegexTableMapper(List.of(new PatternMapper("^S$", "T"))); r.getSource().add(s);
        var target = r.getTarget(); target.setTargetProductType(ProductTypeEnum.DB2); target.setUrl(url); target.setUsername(System.getProperty("datahub.test.db2.user", "db2inst1")); target.setPassword(password()); target.setPoolSize(1); target.setTargetSchema("DB2INST1"); target.setChangeDataSync(true); target.setChangeDataSyncType("INCREASE_COLUMN"); target.setCursorRunBindings(Map.of("S", binding(this::save))); return r; }

    @Test void directNativeCursorAndUpsertPreserveCompositeNumeric31Keys() throws Exception {
        create("S"); create("T"); rows(5); var writer = writer("T", fields, keys); int read = 0;
        try (var cursor = cursor()) { while (cursor.next()) { assertEquals(1, writer.writeWithReceipt(fields, Collections.singletonList(cursor.row())).acknowledgedRows()); read++; } }
        assertEquals(5, read); assertEquals(5, count("T"));
    }
    @Test void fetchFirstKeysetNeverSkipsOrDuplicatesAtEqualWatermarks() throws Exception {
        sql(control, "CREATE TABLE " + t("S") + "(\"WM\" TIMESTAMP NOT NULL,\"K2\" INTEGER NOT NULL,\"K1\" INTEGER NOT NULL,\"V\" VARCHAR(10),PRIMARY KEY(\"K2\",\"K1\"))");
        for (int i = 1; i <= 7; i++) { String micro = i <= 2 ? ".000000" : i <= 4 ? ".123456" : ".654321"; sql(control, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:00" + micro + "'," + i + "," + i + ",'r')"); }
        var names = List.of("WM", "K2", "K1", "V"); var keyNames = List.of("K2", "K1"); var seen = new ArrayList<Integer>();
        var reader = new DB2DatabaseOperator(ds()); reader.setFetchSize(2);
        try (var cursor = reader.openCursor(new CursorReadRequest("DB2INST1", "S", names, "WM", keyNames, null, null, null, limits, false, pin))) { while (cursor.next()) seen.add((Integer) cursor.row()[1]); }
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), seen);
    }
    @Test void missingDeploymentPinAndReorderedPrimaryKeyFailBeforeReading() throws Exception {
        create("S"); rows(1); var operator = new DB2DatabaseOperator(ds());
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(new CursorReadRequest("DB2INST1", "S", fields, "WM", keys, null, null, null, limits, false, null)));
        assertThrows(IllegalArgumentException.class, () -> cursor(fields, List.of("K1", "K2"), "WM", null, null));
    }
    @Test void textWatermarkIsRefusedBeforeAnyRowIsRead() throws Exception {
        sql(control, "CREATE TABLE " + t("S") + "(\"WM\" VARCHAR(20) NOT NULL,\"K2\" INTEGER NOT NULL,\"K1\" INTEGER NOT NULL,\"V\" INTEGER,PRIMARY KEY(\"K2\",\"K1\"))");
        sql(control, "INSERT INTO " + t("S") + " VALUES('w',1,1,1)");
        assertThrows(IllegalArgumentException.class, this::cursor);
        assertEquals(1, count("S"));
    }
    @Test void repeatableReadSnapshotBlocksConcurrentUpdatesAndInsertsUntilClose() throws Exception {
        create("S"); rows(3);
        try (var cursor = cursor()) { assertTrue(cursor.next());
            try (var other = open()) { try { sqlT(other, "UPDATE " + t("S") + " SET \"V\"='changed' WHERE \"K1\"=1", 5); fail("Concurrent update must block"); } catch (SQLException blocked) { }
                try { sqlT(other, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:01',99,99,'phantom')", 5); fail("Concurrent insert must block"); } catch (SQLException blocked) { } }
            assertEquals("original", value("S", 1)); assertEquals(3, count("S")); }
        try (var other = open()) { sql(other, "UPDATE " + t("S") + " SET \"V\"='changed' WHERE \"K1\"=1"); sql(other, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:01',99,99,'phantom')"); }
        assertEquals("changed", value("S", 1)); assertEquals(4, count("S"));
    }
    private static void sqlT(Connection c, String value, int timeout) throws SQLException { try (var s = c.createStatement()) { s.setQueryTimeout(timeout); s.execute(value); } }
    private String value(String table, int k1) throws SQLException { try (var s = control.createStatement(); var r = s.executeQuery("SELECT \"V\" FROM " + t(table) + " WHERE \"K1\"=" + k1)) { assertTrue(r.next()); return r.getString(1); } }
    @Test void generationFenceIsCurrentInsideSnapshotAndRotationIsExplicitIdempotent() throws Exception {
        create("S"); rows(3); assertEquals(pin, Db2SourceGeneration.initialize(control, pin.schema(), 15));
        try (var cursor = cursor()) { assertTrue(cursor.next()); Db2SourceGeneration.fence(control, pin, 15); assertThrows(SQLException.class, cursor::next); }
        var old = pin; String next = UUID.randomUUID().toString(); pin = Db2SourceGeneration.rotate(control, old, next, 15); assertEquals(pin, Db2SourceGeneration.rotate(control, old, next, 15));
        assertThrows(SQLException.class, () -> Db2SourceGeneration.verifyCurrent(control, old, 15)); Db2SourceGeneration.verifyCurrent(control, pin, 15); Db2SourceGeneration.fence(control, pin, 15);
        assertThrows(SQLException.class, () -> Db2SourceGeneration.rotate(control, pin, old.generation(), 15));
    }
    @ParameterizedTest @ValueSource(strings = {"DELETE", "CHANGE"})
    void sourceMarkerTamperingNeverBootstrapsDuringRead(String operation) throws Exception {
        create("S"); rows(1); String marker = t(Db2SourceGeneration.marker(pin.schema()));
        sql(control, operation.equals("DELETE") ? "DELETE FROM " + marker : "UPDATE " + marker + " SET GENERATION='" + UUID.randomUUID() + "'");
        assertThrows(IllegalArgumentException.class, this::cursor); assertEquals(1, count("S"));
    }
    @Test void targetWithoutVerifiedPrimaryKeyIsRejectedBeforeWrites() throws Exception {
        sql(control, "CREATE TABLE " + t("T") + "(\"K\" INTEGER NOT NULL,\"V\" INTEGER)");
        assertThrows(IllegalArgumentException.class, () -> writer("T", List.of("K", "V"), List.of("K"))); assertEquals(0, count("T"));
    }
    @Test void publicServiceReplaysEqualWatermarkAndPersistsCommittedReceipts() throws Exception {
        create("S"); create("T"); rows(5); var request = request(); var result = new MigrationService(request, ignored -> {}, null, runtime).run(); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement()); assertEquals(5, count("T"));
        sql(control, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:00',0,0,'late')"); request.getTarget().setCursorRunBindings(Map.of("S", binding(this::save))); assertTrue(new MigrationService(request, ignored -> {}, null, runtime).run().getIfAllSuccess().get()); assertEquals(6, count("T"));
    }
    @Test void nativeMergePreservesExactNumericTicksUnicodeAndNull() throws Exception {
        String table = "nativevals"; var names = List.of("K", "D", "TS", "T", "DT", "C", "V");
        sql(control, "CREATE TABLE " + t(table) + "(\"K\" DECIMAL(31,0) NOT NULL,\"D\" DECIMAL(31,18),\"TS\" TIMESTAMP,\"T\" TIME,\"DT\" DATE,\"C\" VARCHAR(40),\"V\" VARCHAR(10),PRIMARY KEY(\"K\"))");
        var writer = writer(table, names, List.of("K")); var maximum = new BigDecimal("9999999999999999999999999999999");
        var tick = LocalDateTime.of(2026, 10, 6, 10, 0, 0, 123_456_000);
        Object[] row = { maximum, new BigDecimal("-1E-18"), tick, LocalTime.parse("10:00:00"), LocalDate.of(2026, 10, 6), "\u6c49\u5b57", "v" };
        for (int repeat = 0; repeat < 2; repeat++) assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(row)).acknowledgedRows());
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT * FROM " + t(table))) { assertTrue(r.next()); assertEquals(maximum, r.getBigDecimal(1)); assertEquals(row[1], r.getBigDecimal(2)); assertEquals(tick, r.getTimestamp(3).toLocalDateTime()); assertEquals(LocalTime.parse("10:00:00"), r.getTime(4).toLocalTime()); assertEquals(LocalDate.of(2026, 10, 6), r.getDate(5).toLocalDate()); assertEquals("\u6c49\u5b57", r.getString(6)); assertEquals("v", r.getString(7)); assertFalse(r.next()); }
        assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(new Object[] { maximum, null, null, null, null, null, null })).acknowledgedRows());
    }
    @Test void temporalAndTextKeysUseNativeOrderingAtPageBoundaries() throws Exception {
        var names = List.of("WM", "T", "K", "V"); var primary = List.of("T", "K");
        sql(control, "CREATE TABLE " + t("S") + "(\"WM\" TIMESTAMP NOT NULL,\"T\" TIMESTAMP NOT NULL,\"K\" VARCHAR(30) NOT NULL,\"V\" INTEGER,PRIMARY KEY(\"T\",\"K\"))");
        sql(control, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:00.000000','2026-10-06 10:00:00.000000','z',1)");
        sql(control, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:00.000000','2026-10-06 10:00:00.000000','\u00c1',2)");
        sql(control, "INSERT INTO " + t("S") + " VALUES('2026-10-06 10:00:00.123456','2026-10-06 10:00:00.123456','b',3)");
        var observed = new ArrayList<Integer>(); try (var cursor = cursor(names, primary, "WM", null, null)) { while (cursor.next()) observed.add((Integer) cursor.row()[3]); }
        assertEquals(List.of(1, 2, 3), observed);
    }
    @Test void generatedAlwaysIdentityIsRejectedAndGeneratedByDefaultReplaysExplicitKeys() throws Exception {
        sql(control, "CREATE TABLE " + t("identitygen") + "(\"K\" INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1, NO CACHE),\"V\" VARCHAR(20),PRIMARY KEY(\"K\"))");
        var names = List.of("K", "V");
        assertThrows(IllegalArgumentException.class, () -> writer("identitygen", names, List.of("K")));
        assertEquals(0, count("identitygen"));
        sql(control, "DROP TABLE " + t("identitygen"));
        sql(control, "CREATE TABLE " + t("identitygen") + "(\"K\" INTEGER NOT NULL GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1, NO CACHE),\"V\" VARCHAR(20),PRIMARY KEY(\"K\"))");
        var writer = writer("identitygen", names, List.of("K"));
        assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(new Object[] { 100, "explicit" })).acknowledgedRows());
        sql(control, "INSERT INTO " + t("identitygen") + "(\"V\") VALUES('native')");
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT \"K\" FROM " + t("identitygen") + " WHERE \"V\"='native'")) { assertTrue(r.next()); assertNotEquals(100, r.getInt(1)); }
        assertEquals(2, count("identitygen"));
    }
    @Test void invalidLaterValueRejectsWholeBatchBeforeBorrowingTarget() throws Exception {
        sql(control, "CREATE TABLE " + t("T") + "(\"K\" BIGINT NOT NULL,\"TS\" TIMESTAMP,\"T\" TIME,\"V\" VARCHAR(4),PRIMARY KEY(\"K\"))"); var borrowed = new AtomicInteger();
        var source = new AbstractDataSource() { public Connection getConnection() throws SQLException { borrowed.incrementAndGet(); return open(); } public Connection getConnection(String u, String p) throws SQLException { return getConnection(); } };
        var writer = new Db2UpsertWriter(source); writer.prepareUpsert("DB2INST1", "T", List.of("K", "TS", "T", "V"), List.of("K")); borrowed.set(0);
        assertThrows(RuntimeException.class, () -> writer.writeWithReceipt(List.of("K", "TS", "T", "V"), List.of(new Object[] { 1L, LocalDateTime.of(2026, 10, 6, 10, 0, 0, 1_000_001), LocalTime.parse("10:00:00"), "ok" }, new Object[] { 2L, null, null, "ok" })));
        assertThrows(RuntimeException.class, () -> writer.writeWithReceipt(List.of("K", "TS", "T", "V"), Collections.singletonList(new Object[] { 3L, null, LocalTime.parse("10:00:00.5"), "ok" })));
        assertThrows(RuntimeException.class, () -> writer.writeWithReceipt(List.of("K", "TS", "T", "V"), Collections.singletonList(new Object[] { 4L, null, null, "toolong" }))); assertEquals(0, borrowed.get()); assertEquals(0, count("T"));
    }
    @Test void commitBeforeCheckpointCrashRewindsWithoutDuplicatingTarget() throws Exception {
        create("S"); create("T"); rows(5); var calls = new AtomicInteger(); assertThrows(IllegalStateException.class, () -> transfer(ds(), value -> calls.incrementAndGet() != 3 && save(value))); assertEquals(4, count("T")); assertFalse(saved.get().complete());
        transfer(ds(), this::save); assertEquals(5, count("T")); assertTrue(saved.get().complete());
    }
    @Test void actualCommitLostAcknowledgementDoesNotAdvanceCheckpoint() throws Exception {
        create("S"); create("T"); rows(2); var fault = fault("commit"); assertThrows(UnconfirmedWriteException.class, () -> transfer(fault, this::save)); assertEquals(2, count("T")); assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence()); transfer(ds(), this::save); assertEquals(2, count("T"));
    }
    @Test void completedEmptyWindowRemainsFrozenUntilExplicitNextRun() throws Exception {
        create("S"); create("T"); var calls = new AtomicInteger(); assertThrows(IllegalStateException.class, () -> transfer(ds(), value -> calls.incrementAndGet() == 1 && save(value))); assertNull(saved.get().fixedUpper()); rows(1); transfer(ds(), this::save); assertEquals(0, count("T")); transfer(ds(), this::save); assertEquals(1, count("T"));
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE_RECREATE", "TARGET_RECREATE", "SOURCE_TRUNCATE", "PIN_ROTATE"})
    void resumeRequiresUnchangedPhysicalSchemaAndExternalGeneration(String mode) throws Exception {
        create("S"); create("T"); rows(1); transfer(ds(), this::save);
        if (mode.equals("SOURCE_RECREATE")) { sql(control, "DROP TABLE " + t("S")); create("S"); rows(1); } else if (mode.equals("TARGET_RECREATE")) { sql(control, "DROP TABLE " + t("T")); create("T"); }
        else if (mode.equals("SOURCE_TRUNCATE")) sql(control, "TRUNCATE TABLE " + t("S") + " IMMEDIATE");
        else { Db2SourceGeneration.fence(control, pin, 15); pin = Db2SourceGeneration.rotate(control, pin, UUID.randomUUID().toString(), 15); }
        long before = count("T"); assertThrows(RuntimeException.class, () -> transfer(ds(), this::save)); assertEquals(before, count("T"));
    }
    @Test void concurrentIndependentMergesSerializeWithoutDuplicateKeyRace() throws Exception {
        sql(control, "CREATE TABLE " + t("T") + "(\"K\" BIGINT NOT NULL,\"V\" INTEGER,PRIMARY KEY(\"K\"))"); var one = writer("T", List.of("K", "V"), List.of("K")); var two = writer("T", List.of("K", "V"), List.of("K"));
        try (var threads = Executors.newFixedThreadPool(2)) { var start = new CountDownLatch(1); var a = threads.submit(() -> { start.await(); return one.writeWithReceipt(List.of("K", "V"), Collections.singletonList(new Object[] { 1L, 10 })); }); var b = threads.submit(() -> { start.await(); return two.writeWithReceipt(List.of("K", "V"), Collections.singletonList(new Object[] { 1L, 20 })); }); start.countDown(); assertEquals(1, a.get(30, TimeUnit.SECONDS).acknowledgedRows()); assertEquals(1, b.get(30, TimeUnit.SECONDS).acknowledgedRows()); } assertEquals(1, count("T"));
    }
    @Test void oddPhysicalNamesRoundTripThroughQuotedIdentifiers() throws Exception {
        String table = "odd name"; var names = List.of("K", "V"); sql(control, "CREATE TABLE " + t(table) + "(\"K\" INTEGER NOT NULL,\"V\" VARCHAR(10),PRIMARY KEY(\"K\"))");
        sql(control, "INSERT INTO " + t(table) + " VALUES(1,'x')");
        var writer = writer(table, names, List.of("K"));
        assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(new Object[] { 2, "y" })).acknowledgedRows());
        assertEquals(2, count(table));
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT \"V\" FROM " + t(table) + " WHERE \"K\"=2")) { assertTrue(r.next()); assertEquals("y", r.getString(1)); }
    }
    private DataSource fault(String method) { return new AbstractDataSource() { public Connection getConnection() throws SQLException { Connection c = open(); return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (p, m, a) -> { try { Object v = m.invoke(c, a); if (m.getName().equals(method)) throw new SQLException("fixture response lost"); return v; } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); } }); } public Connection getConnection(String u, String p) throws SQLException { return getConnection(); } }; }
}
