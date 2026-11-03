package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseOracleReplayProfile;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original Oracle-mode tenant. The factory continues to identify OCEANBASE throughout public replay. */
@Timeout(180)
@EnabledIfSystemProperty(named="datahub.test.oboracle.url", matches="jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named="datahub.test.oboracle.driver", matches=".+")
class MigrationCursorOceanbaseOracleTest {
    private static final ProductTypeEnum OB = ProductTypeEnum.OCEANBASE;
    private static final List<String> FIELDS = List.of("WM", "ID", "V");
    private static final List<String> KEYS = List.of("ID");
    private final String prefix = "R89_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    private final Set<String> tables = new LinkedHashSet<>();
    private final BatchLimits limits = new BatchLimits(2, 65536, 16384, 65535, 1000);
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();
    private ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private SourceGenerationPin pin;

    @BeforeEach void setup() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.oboracle.driver")).toRealPath();
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers = new ConfiguredJdbcDrivers(Map.of(OB, config));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        try (var c = connection()) { pin = OceanbaseSourceGeneration.initialize(c, schema(), 15); }
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) try (var c = connection()) {
            for (String name : tables) try { sql(c, "DROP TABLE " + table(name) + " PURGE"); }
            catch (SQLException failure) { if (failure.getErrorCode() != 942) throw failure; }
        } } finally { if (runtime != null) runtime.close(); if (drivers != null) drivers.close(); }
    }

    @Test void nativeProfileAndExactValuesSurviveInsertUpdateAndReplay() throws Exception {
        create("T", "ID NUMBER(38,0) PRIMARY KEY,AMOUNT NUMBER(38,18),V NVARCHAR2(40),B RAW(3),D DATE,TS TIMESTAMP(9)");
        try (var c = connection()) {
            var shape = OceanbaseOracleReplayProfile.INSTANCE.inspect(c, schema(), name("T"), 15);
            assertEquals(List.of("ID", "AMOUNT", "V", "B", "D", "TS"), new ArrayList<>(shape.columns().keySet()));
        }
        var names = List.of("ID", "AMOUNT", "V", "B", "D", "TS");
        var writer = runtime.upsertWriter(ds(), OB); writer.prepareUpsert(schema(), name("T"), names, KEYS);
        assertEquals(OB, writer.keyContract().product());
        Object[] row = {new BigDecimal("99999999999999999999999999999999999999"), new BigDecimal("-0.000000000000000001"),
                "汉字🙂'\\", new byte[]{0, 39, (byte) 255}, LocalDateTime.parse("1988-04-17T02:30:00"), LocalDateTime.parse("1988-04-17T02:30:00.123456789")};
        for (int i = 0; i < 2; i++) assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED,
                writer.writeWithReceipt(names, Collections.singletonList(row)).commitAcknowledgement());
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT ID,AMOUNT,V,B,TO_CHAR(D,'YYYY-MM-DD HH24:MI:SS'),TO_CHAR(TS,'YYYY-MM-DD HH24:MI:SS.FF9') FROM " + t("T"))) {
            assertTrue(r.next()); assertEquals(0, ((BigDecimal) row[0]).compareTo(r.getBigDecimal(1))); assertEquals(row[1], r.getBigDecimal(2));
            assertEquals(row[2], r.getString(3)); assertArrayEquals((byte[]) row[3], r.getBytes(4));
            assertEquals("1988-04-17 02:30:00", r.getString(5)); assertEquals("1988-04-17 02:30:00.123456789", r.getString(6)); assertFalse(r.next());
        }
        row[1] = new BigDecimal("12345678901234567890.123456789012345678"); row[2] = "updated";
        assertEquals(1, writer.write(names, Collections.singletonList(row))); assertEquals("updated", scalar("SELECT V FROM " + t("T")));
        Object[] bad = row.clone(); bad[1] = new BigDecimal("0.0000000000000000001");
        assertThrows(BatchRejectedException.class, () -> writer.write(names, List.of(row, bad)));
        assertEquals(1, count("T"));
    }

    @Test void concurrentSameKeyMergesCommitWithoutDuplicateInsertRace() throws Exception {
        create("T", "ID NUMBER(19,0) PRIMARY KEY,V VARCHAR2(20)"); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 4; i++) { final int value = i; futures.add(executor.submit(() -> {
                var writer = runtime.upsertWriter(ds(), OB); writer.prepareUpsert(schema(), name("T"), List.of("ID", "V"), KEYS);
                assertTrue(start.await(10, TimeUnit.SECONDS)); return writer.write(List.of("ID", "V"), Collections.singletonList(new Object[]{1L, "v" + value}));
            })); }
            start.countDown(); for (var future : futures) assertEquals(1L, future.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, count("T")); assertTrue(scalar("SELECT V FROM " + t("T")).matches("v[0-3]"));
    }

    @Test void nativeConstraintFailureRollsBackTheWholeBatch() throws Exception {
        create("T", "ID NUMBER(19,0) PRIMARY KEY,V NUMBER CHECK(V>0)");
        var writer = runtime.upsertWriter(ds(), OB); writer.prepareUpsert(schema(), name("T"), List.of("ID", "V"), KEYS);
        assertThrows(UnconfirmedWriteException.class, () -> writer.write(List.of("ID", "V"), List.of(new Object[]{1L, 1L}, new Object[]{2L, -1L})));
        assertEquals(0, count("T"));
    }

    @Test void fixedWidthAndUnboundedNumberRejectSilentPaddingAndRounding() throws Exception {
        create("T", "ID NUMBER(19,0) PRIMARY KEY,C CHAR(4),NC NCHAR(2),N NUMBER");
        var fields = List.of("ID", "C", "NC", "N"); var writer = runtime.upsertWriter(ds(), OB);
        writer.prepareUpsert(schema(), name("T"), fields, KEYS);
        Object[] row = {1L, "abcd", "🙂汉", new BigDecimal("0.12345678901234567890123456789012345678")};
        assertEquals(1, writer.write(fields, Collections.singletonList(row)));
        for (int index : List.of(1, 2, 3)) {
            Object[] bad = row.clone(); bad[0] = 2L;
            bad[index] = index == 3 ? new BigDecimal("0.123456789012345678901234567890123456789012345") : index == 2 ? "🙂" : "x";
            assertThrows(BatchRejectedException.class, () -> writer.write(fields, List.of(row, bad)));
        }
        assertEquals(1, count("T")); assertEquals("abcd", scalar("SELECT C FROM " + t("T"))); assertEquals("🙂汉", scalar("SELECT NC FROM " + t("T")));
    }

    @Test void primaryKeyOnlyMergeHasNoSyntheticUpdate() throws Exception {
        create("T", "ID NUMBER(19,0) PRIMARY KEY"); var writer = runtime.upsertWriter(ds(), OB);
        writer.prepareUpsert(schema(), name("T"), KEYS, KEYS);
        for (int i = 0; i < 2; i++) assertEquals(2, writer.write(KEYS, List.of(new Object[]{1L}, new Object[]{2L})));
        assertEquals(2, count("T"));
    }

    @Test void secondPrecisionTimestampWatermarkKeyAndPayloadDoNotUseFractionFormat() throws Exception {
        for (String name : List.of("S", "T")) create(name, "WM TIMESTAMP(0) NOT NULL,K TIMESTAMP(0) PRIMARY KEY,V TIMESTAMP(0)");
        exec("INSERT INTO " + t("S") + " VALUES(TIMESTAMP '1988-04-17 02:30:00',TIMESTAMP '2026-10-08 12:34:56',TIMESTAMP '1988-04-17 02:30:00')");
        var fields = List.of("WM", "K", "V"); var keys = List.of("K");
        for (int i = 0; i < 2; i++) CursorTransfer.execute(runtime, ds(), ds(), OB, OB, schema(), name("S"), schema(), name("T"), fields, fields,
                keys, keys, "WM", limits, 1, binding(this::save), new DbSwitchTableResult(), () -> { });
        assertEquals(1, count("T")); assertEquals("1988-04-17 02:30:00", scalar("SELECT TO_CHAR(V,'YYYY-MM-DD HH24:MI:SS') FROM " + t("T")));
    }

    @Test void temporalWatermarkAndCompositeTextNumberKeysPageAndReplayExactly() throws Exception {
        for (String name : List.of("S", "T")) create(name, "WM TIMESTAMP(9) NOT NULL,K1 VARCHAR2(16) NOT NULL,K2 NUMBER(38,0) NOT NULL,V NVARCHAR2(30),PRIMARY KEY(K2,K1)");
        String huge = "99999999999999999999999999999999999999";
        exec("INSERT INTO " + t("S") + " VALUES(TIMESTAMP '1988-04-17 02:30:00.123456789','a'," + huge + ",'first')");
        exec("INSERT INTO " + t("S") + " VALUES(TIMESTAMP '1988-04-17 02:30:00.123456789','Z'," + huge + ",'second')");
        exec("INSERT INTO " + t("S") + " VALUES(TIMESTAMP '1988-04-17 02:30:00.123456789','汉',-" + huge + ",'third')");
        var fields = List.of("WM", "K1", "K2", "V"); var keys = List.of("K2", "K1");
        var expected = new ArrayList<String>();
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT V FROM " + t("S") + " ORDER BY WM,K2,K1")) { while (r.next()) expected.add(r.getString(1)); }
        var reader = runtime.reader(ds(), OB); reader.setFetchSize(1); var actual = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema(), name("S"), fields, "WM", keys, null, null, null, limits, false, pin))) {
            while (cursor.next()) { assertEquals(LocalDateTime.parse("1988-04-17T02:30:00.123456789"), cursor.row()[0]); actual.add((String) cursor.row()[3]); }
        }
        assertEquals(expected, actual);
        for (int i = 0; i < 2; i++) CursorTransfer.execute(runtime, ds(), ds(), OB, OB, schema(), name("S"), schema(), name("T"), fields, fields,
                keys, keys, "WM", limits, 1, binding(this::save), new DbSwitchTableResult(), () -> { });
        assertEquals(3, count("T")); assertEquals("1988-04-17 02:30:00.123456789", scalar("SELECT DISTINCT TO_CHAR(WM,'YYYY-MM-DD HH24:MI:SS.FF9') FROM " + t("T")));
    }

    @Test void writerRefusesPreexistingSerializableSessionWithoutEffects() throws Exception {
        ordinary("T"); DataSource serializable = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { var c = connection(); c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); return c; }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        var writer = runtime.upsertWriter(serializable, OB);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema(), name("T"), FIELDS, KEYS)); assertEquals(0, count("T"));
    }

    @Test void existingGenerationAdministratorSupportsOracleTenantInitializationAndRotation() throws Exception {
        try (var c = connection()) {
            var again = OceanbaseSourceGeneration.initialize(c, schema(), 15); assertEquals(pin.instanceId(), again.instanceId());
            String next = UUID.randomUUID().toString(); var rotated = OceanbaseSourceGeneration.rotate(c, pin, next, 15);
            assertEquals(pin.instanceId(), rotated.instanceId()); assertEquals(next, rotated.generation());
            assertEquals(rotated, OceanbaseSourceGeneration.rotate(c, pin, next, 15));
            assertThrows(SQLException.class, () -> OceanbaseSourceGeneration.rotate(c, pin, pin.generation(), 15));
            assertThrows(SQLException.class, () -> OceanbaseSourceGeneration.rotate(c,
                    new SourceGenerationPin(schema(), UUID.randomUUID().toString(), pin.generation()), next, 15));
        }
    }

    @Test void lostCommitAcknowledgementDoesNotAdvanceCheckpointAndReplayConverges() throws Exception {
        ordinary("S"); ordinary("T"); seed(2);
        DataSource fault = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = connection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try { Object value = method.invoke(actual, args); if (method.getName().equals("commit")) throw new SQLException("fixture lost acknowledgement"); return value; }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        assertThrows(UnconfirmedWriteException.class, () -> transfer(fault, this::save));
        assertEquals(2, count("T")); assertEquals(0, saved.get().sequence()); assertNull(saved.get().applied());
        transfer(ds(), this::save); assertTrue(saved.get().complete()); assertEquals(2, count("T"));
    }

    @Test void publicMigrationServiceResumesAndRewindsEqualWatermarks() throws Exception {
        ordinary("S"); ordinary("T"); seed(5);
        var request = request();
        var first = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(first.getIfAllSuccess().get(), () -> first.getTableResultList().toString()); assertEquals(5, count("T"));
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        exec("INSERT INTO " + t("S") + " VALUES(10,0,'late equal watermark')");
        exec("UPDATE " + t("S") + " SET V='changed' WHERE ID=5");
        request.getTarget().setCursorRunBindings(Map.of(name("S"), binding(this::save)));
        var second = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(second.getIfAllSuccess().get(), () -> second.getTableResultList().toString());
        assertEquals(6, count("T")); assertEquals("changed", scalar("SELECT V FROM " + t("T") + " WHERE ID=5"));
    }

    @Test void commitBeforeCheckpointCrashResumesTheFrozenWindow() throws Exception {
        ordinary("S"); ordinary("T"); seed(5); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(ds(), next -> calls.incrementAndGet() != 3 && save(next)));
        assertEquals(4, count("T")); assertFalse(saved.get().complete());
        exec("INSERT INTO " + t("S") + " VALUES(11,99,'next window')");
        transfer(ds(), this::save); assertEquals(5, count("T")); assertTrue(saved.get().complete());
        transfer(ds(), this::save); assertEquals(6, count("T"));
    }

    @Test void sourceSnapshotAllowsDmlButRejectsConflictingDdl() throws Exception {
        ordinary("S"); seed(3); var reader = runtime.reader(ds(), OB); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest(schema(), name("S"), FIELDS, "WM", KEYS, null, null, null, limits, false, pin))) {
            assertTrue(cursor.next()); exec("UPDATE " + t("S") + " SET V='changed' WHERE ID=3");
            try (var other = connection()) { other.setAutoCommit(false);
                assertThrows(SQLException.class, () -> sql(other, "LOCK TABLE " + t("S") + " IN EXCLUSIVE MODE NOWAIT")); other.rollback(); }
            try (var other = connection(); var ddl = other.createStatement()) {
                ddl.setQueryTimeout(2); assertThrows(SQLException.class, () -> ddl.execute("TRUNCATE TABLE " + t("S")));
            }
            int count = 1; while (cursor.next()) { assertEquals("original", cursor.row()[2]); count++; } assertEquals(3, count);
        }
        exec("TRUNCATE TABLE " + t("S")); assertEquals(0, count("S"));
    }

    @ParameterizedTest @ValueSource(strings={"SOURCE_TRUNCATE", "TARGET_RECREATE", "GENERATION"})
    void resumeRejectsChangedIdentityWithoutWrites(String kind) throws Exception {
        ordinary("S"); ordinary("T"); seed(1); transfer(ds(), this::save); var before = saved.get();
        if (kind.equals("SOURCE_TRUNCATE")) { exec("TRUNCATE TABLE " + t("S")); seed(1); }
        else if (kind.equals("TARGET_RECREATE")) { exec("DROP TABLE " + t("T") + " PURGE"); ordinary("T"); }
        else pin = new SourceGenerationPin(pin.schema(), pin.instanceId(), UUID.randomUUID().toString());
        long rows = count("T"); assertThrows(IllegalArgumentException.class, () -> transfer(ds(), this::save));
        assertSame(before, saved.get()); assertEquals(rows, count("T"));
    }

    @ParameterizedTest @ValueSource(strings={"IDENTITY", "DEFAULT", "EXTRA_UNIQUE", "TRIGGER"})
    void replayRefusesUnverifiedSideEffectsBeforeAnyWrite(String kind) throws Exception {
        switch (kind) {
            case "IDENTITY" -> create("T", "ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,V NUMBER");
            case "DEFAULT" -> create("T", "ID NUMBER PRIMARY KEY,V NUMBER DEFAULT 1");
            case "EXTRA_UNIQUE" -> create("T", "ID NUMBER PRIMARY KEY,V NUMBER UNIQUE");
            default -> { create("T", "ID NUMBER PRIMARY KEY,V NUMBER");
                exec("CREATE TRIGGER " + q(schema()) + "." + q(name("TR")) + " BEFORE INSERT ON " + t("T") + " FOR EACH ROW BEGIN :NEW.V := 2; END;"); }
        }
        var writer = runtime.upsertWriter(ds(), OB);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema(), name("T"), List.of("ID", "V"), KEYS));
        assertEquals(0, count("T"));
    }

    @Test void nativeNullableAddRetainsRowsAndRejectsUnsupportedChangesWithoutSql() throws Exception {
        create("T", "ID NUMBER(19,0)"); exec("INSERT INTO " + t("T") + " VALUES(7)");
        var dialect = new DatabaseOceanbaseImpl(); try (var c = connection()) { dialect.prepareDialect(c); }
        var column = new ColumnDescription(); column.setDbType(OB); column.setFieldName("MixedCase"); column.setFieldType(Types.DECIMAL);
        column.setFieldTypeName("DECIMAL"); column.setPrecisionSize(24); column.setScaleSize(6); column.setNullable(true);
        exec(dialect.getAddColumnSql(schema(), name("T"), column));
        assertEquals("7", scalar("SELECT ID FROM " + t("T"))); assertNull(scalar("SELECT \"MixedCase\" FROM " + t("T")));
        for (int mode = 0; mode < 5; mode++) {
            column.setNullable(mode != 0); column.setPk(mode == 1); column.setDefaultValue(mode == 2 ? "1" : null);
            column.setAutoIncrement(mode == 3); column.setFieldType(mode == 4 ? Types.OTHER : Types.DECIMAL);
            assertThrows(SQLFeatureNotSupportedException.class, () -> dialect.getAddColumnSql(schema(), name("T"), column));
        }
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + t("T"))) {
            assertEquals(2, r.getMetaData().getColumnCount()); assertTrue(r.next()); assertEquals(7, r.getInt(1)); assertNull(r.getObject(2)); assertFalse(r.next());
        }
    }

    private MigrationRequest request() {
        var r = new MigrationRequest(); var input = new SourceDataSourceProperties(); input.setSourceProductType(OB); input.setUrl(url());
        input.setUsername(user()); input.setPassword(password()); input.setSourceSchema(schema()); input.setSourceIncludes(name("S")); input.setFetchSize(2);
        input.setIncreaseColumnName("WM"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^" + name("S") + "$", name("T")))); r.getSource().add(input);
        var output = r.getTarget(); output.setTargetProductType(OB); output.setUrl(url()); output.setUsername(user()); output.setPassword(password());
        output.setTargetSchema(schema()); output.setPoolSize(1); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of(name("S"), binding(this::save))); return r;
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("b".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private void transfer(DataSource target, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(), target, OB, OB, schema(), name("S"), schema(), name("T"), FIELDS, FIELDS,
                KEYS, KEYS, "WM", limits, 2, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private void ordinary(String name) throws SQLException { create(name, "WM NUMBER(38,18) NOT NULL,ID NUMBER(19,0) PRIMARY KEY,V VARCHAR2(80)"); }
    private void create(String name, String columns) throws SQLException { tables.add(name(name)); exec("CREATE TABLE " + t(name) + "(" + columns + ")"); }
    private void seed(int count) throws SQLException { for (int i = 1; i <= count; i++) exec("INSERT INTO " + t("S") + " VALUES(10," + i + ",'original')"); }
    private long count(String name) throws SQLException { return Long.parseLong(scalar("SELECT COUNT(*) FROM " + t(name))); }
    private String scalar(String query) throws SQLException {
        try (var c = connection(); var s = c.createStatement(); var r = s.executeQuery(query)) { assertTrue(r.next()); String value = r.getString(1); assertFalse(r.next()); return value; }
    }
    private Connection connection() throws SQLException { return runtime.openConnection(OB, null, url(), user(), password()); }
    private DataSource ds() { return new AbstractDataSource() {
        @Override public Connection getConnection() throws SQLException { return connection(); }
        @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Explicit fixture principal required"); }
    }; }
    private void exec(String sql) throws SQLException { try (var c = connection()) { sql(c, sql); } }
    private static void sql(Connection c, String sql) throws SQLException { try (var s = c.createStatement()) { s.setQueryTimeout(15); s.execute(sql); } }
    private String name(String name) { return prefix + "_" + name; }
    private String t(String name) { return table(name(name)); }
    private String table(String name) { return q(schema()) + "." + q(name); }
    private static String q(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static String url() { return System.getProperty("datahub.test.oboracle.url"); }
    private static String schema() { return System.getProperty("datahub.test.oboracle.schema", "DBS88"); }
    private static String user() { return System.getProperty("datahub.test.oboracle.user"); }
    private static String password() { return System.getenv().getOrDefault("DATAHUB_OBORACLE_TEST_PASSWORD", ""); }
}
