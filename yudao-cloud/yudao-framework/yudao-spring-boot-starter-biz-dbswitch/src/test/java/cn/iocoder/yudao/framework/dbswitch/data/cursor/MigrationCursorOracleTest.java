package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.math.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Typed cursor and native MERGE UPSERT on the task-owned Oracle Free 23.26.3 PDB. */
@Timeout(180)
@EnabledIfSystemProperty(named="datahub.test.oracle.url",matches="jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
@EnabledIfEnvironmentVariable(named="DATAHUB_TEST_ORACLE_PASSWORD",matches=".+")
class MigrationCursorOracleTest {
    private static final ProductTypeEnum ORACLE = ProductTypeEnum.ORACLE;
    private DbSwitchRuntime runtime;
    private String schema, userPassword;
    private Connection control;
    private final List<String> fields = List.of("WM","K1","K2","V");
    private final List<String> keys = List.of("K2","K1");
    private final BatchLimits limits = new BatchLimits(2, 65536, 16384, 65535, 1000);
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();
    private SourceGenerationPin pin;

    @BeforeEach void setup() throws Exception {
        Class.forName("oracle.jdbc.OracleDriver");
        control = DriverManager.getConnection(url(), "SYSTEM", System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));
        assertTrue(control.getMetaData().getDatabaseProductName().contains("Oracle"));
        schema = "MIGC_" + UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        userPassword = "Qx" + UUID.randomUUID().toString().replace("-","");
        sql(control, "CREATE USER " + q(schema) + " IDENTIFIED BY " + userPassword + " DEFAULT TABLESPACE CODEX_Q11 QUOTA 100M ON CODEX_Q11");
        sql(control, "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE TRIGGER TO " + q(schema));
        runtime = DbSwitchRuntime.standalone();
        try (var connection = oracle()) { pin = OracleSourceGeneration.initialize(connection, schema, 15); }
        // The deployed pin is resolved through the deployment configuration, which must admit ORACLE endpoints.
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ORACLE); generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ORACLE, url(), schema));
        generation.setSchema(pin.schema()); generation.setInstanceId(pin.instanceId()); generation.setGeneration(pin.generation());
        assertEquals(pin, new ConfiguredSourceGenerations(Map.of("oracle", generation)).find(ORACLE, url(), schema).orElseThrow());
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally { if (control != null) try (var owned = control) { sql(owned, "DROP USER " + q(schema) + " CASCADE"); } }
    }

    // ---- public service and recovery contract ---------------------------------------------------------

    @Test void publicServiceUsesNativeCursorUpsertAndRewindsEqualWatermark() throws Exception {
        create("S"); create("T"); rows(5);
        var request = request();
        var first = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(first.getIfAllSuccess().get(), () -> first.getTableResultList().toString());
        assertEquals(5, count("T")); assertTrue(saved.get().complete()); assertEquals(2, saved.get().version());
        assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement()); assertNull(saved.get().lastCommit());
        exec("INSERT INTO " + t("S") + " VALUES(10,0,0,'late equal watermark')");
        exec("UPDATE " + t("S") + " SET V='updated' WHERE K1=5");
        request.getTarget().setCursorRunBindings(Map.of("S", binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(6, count("T"));
        assertEquals(texts("SELECT K1,V FROM " + t("S") + " ORDER BY K1"), texts("SELECT K1,V FROM " + t("T") + " ORDER BY K1"));
    }

    @Test void commitBeforeCheckpointCrashReplaysFrozenWindowThenAdvances() throws Exception {
        create("S"); create("T"); rows(5); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(next -> calls.incrementAndGet() != 3 && save(next)));
        assertEquals(4, count("T")); assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete()); assertNull(saved.get().lastCommit());
        exec("INSERT INTO " + t("S") + " VALUES(10,0,0,'late'),(11,99,99,'next window')");
        transfer(this::save);
        assertEquals(6, count("T")); assertTrue(saved.get().complete());
        transfer(this::save);
        assertEquals(7, count("T"));
    }

    @Test void actualCommitWithLostAcknowledgementNeverAdvancesTheCheckpoint() throws Exception {
        create("S"); create("T"); rows(2);
        DataSource fault = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = oracle();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        Object value = method.invoke(actual, arguments);
                        if (method.getName().equals("commit")) throw new SQLException("fixture lost acknowledgement");
                        return value;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        assertThrows(UnconfirmedWriteException.class, () -> transfer(fault, this::save));
        assertEquals(2, count("T")); assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer(this::save);
        assertTrue(saved.get().complete()); assertEquals(2, count("T"));
    }

    @Test void frozenEmptyWindowRemainsEmptyUntilTheNextCompletedRun() throws Exception {
        create("S"); create("T"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(next -> calls.incrementAndGet() == 1 && save(next)));
        assertNull(saved.get().fixedUpper()); assertFalse(saved.get().complete());
        rows(1);
        transfer(this::save);
        assertEquals(0, count("T")); assertTrue(saved.get().complete());
        transfer(this::save);
        assertEquals(1, count("T"));
    }

    @ParameterizedTest @ValueSource(strings = {"TARGET_RECREATE", "SOURCE_TRUNCATE", "DEPLOYED_GENERATION"})
    void resumeRejectsChangedPhysicalOrDeployedGenerationBeforeWrites(String mode) throws Exception {
        create("S"); create("T"); rows(1); transfer(this::save);
        switch (mode) {
            case "TARGET_RECREATE" -> { exec("DROP TABLE " + t("T")); create("T"); }
            case "SOURCE_TRUNCATE" -> { exec("TRUNCATE TABLE " + t("S")); rows(1); }
            default -> pin = new SourceGenerationPin(pin.schema(), pin.instanceId(), UUID.randomUUID().toString());
        }
        long before = count("T"); var checkpoint = saved.get();
        assertThrows(IllegalArgumentException.class, () -> transfer(this::save));
        assertEquals(before, count("T")); assertSame(checkpoint, saved.get());
    }

    @Test void pinMustNameTheActualPdbBeforeTheFirstRead() throws Exception {
        create("S"); create("T"); rows(1);
        pin = new SourceGenerationPin(pin.schema(), UUID.randomUUID().toString(), pin.generation());
        assertThrows(IllegalArgumentException.class, () -> transfer(this::save));
        assertNull(saved.get()); assertEquals(0, count("T"));
    }

    // ---- snapshot ------------------------------------------------------------------------------------

    @Test void serializableSnapshotAllowsDmlButRowSharePreventsTruncate() throws Exception {
        create("S"); rows(3);
        var reader = runtime.reader(ds(), ORACLE); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "S", fields, "WM", keys, null, null, null, limits, false, pin))) {
            assertTrue(cursor.next());
            exec("UPDATE " + t("S") + " SET V='changed' WHERE K1=3");
            exec("INSERT INTO " + t("S") + " VALUES(11,9,9,'after snapshot')");
            exec("COMMIT");
            try (var other = oracle(); var s = other.createStatement()) { s.setQueryTimeout(2); assertThrows(SQLException.class, () -> s.execute("TRUNCATE TABLE " + t("S"))); }
            try (var other = oracle(); var s = other.createStatement()) { s.setQueryTimeout(2); assertThrows(SQLException.class, () -> s.execute("DROP TABLE " + t("S"))); }
            int read = 1;
            while (cursor.next()) { assertEquals("original", cursor.row()[3]); read++; }
            assertEquals(3, read);
        }
        exec("TRUNCATE TABLE " + t("S"));
        assertEquals(0, count("S"));
    }

    // ---- ordering, keys and precision ----------------------------------------------------------------

    @Test void keysetPagesOverNegativeAndHugeNumbersMatchOrderByExactly() throws Exception {
        create("S");
        exec("INSERT INTO " + t("S") + " VALUES(-0.000000000000000001,-9223372036854775808,-99999999999999999999999999999999999999,'first'),"
                + "(-0.000000000000000001,9223372036854775807,-99999999999999999999999999999999999999,'second'),"
                + "(-0.000000000000000001,0,99999999999999999999999999999999999999,'third'),(0,-1,-99999999999999999999999999999999999999,'fourth'),"
                + "(10,5,1,'fifth'),(10,5,2,'sixth')");
        var reader = runtime.reader(ds(), ORACLE); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "S", fields, "WM", keys, null, null, null, limits, false, pin))) {
            assertEquals(List.of("10.000000000000000000", "2", "5"), cursor.upperBound().values().stream().map(TypedCursorValue::value).toList());
            while (cursor.next()) seen.add((String) cursor.row()[3]);
        }
        assertEquals(List.of("first", "second", "third", "fourth", "fifth", "sixth"), seen);
        assertEquals(texts("SELECT K2,K1 FROM " + t("S") + " ORDER BY WM,K2,K1"),
                texts("SELECT K2,K1 FROM " + t("S") + " WHERE (WM<=10 AND K2<=2 AND K1<=5 OR WM<=10 AND K2<2 OR WM<10) ORDER BY WM,K2,K1"));
    }

    @Test void temporalKeysAndDateWatermarkPageExactly() throws Exception {
        exec("CREATE TABLE " + t("S") + "(WM DATE NOT NULL,CREATED TIMESTAMP(6) NOT NULL,SLOT TIMESTAMP(3) NOT NULL,ID NUMBER(10,0) NOT NULL,V VARCHAR2(20),PRIMARY KEY(CREATED,SLOT,ID))");
        exec("CREATE TABLE " + t("T") + "(WM DATE NOT NULL,CREATED TIMESTAMP(6) NOT NULL,SLOT TIMESTAMP(3) NOT NULL,ID NUMBER(10,0) NOT NULL,V VARCHAR2(20),PRIMARY KEY(CREATED,SLOT,ID))");
        exec("INSERT INTO " + t("S") + " VALUES(TO_DATE('2026-03-08','YYYY-MM-DD'),TO_TIMESTAMP('1988-04-17 02:30:00.123456','YYYY-MM-DD HH24:MI:SS.FF6'),"
                + "TO_TIMESTAMP('2026-03-08 02:30:00.001','YYYY-MM-DD HH24:MI:SS.FF3'),4294967295,'a'),"
                + "(TO_DATE('2026-03-08','YYYY-MM-DD'),TO_TIMESTAMP('1988-04-17 02:30:00.123456','YYYY-MM-DD HH24:MI:SS.FF6'),TO_TIMESTAMP('2026-03-08 02:30:00.001','YYYY-MM-DD HH24:MI:SS.FF3'),0,'b'),"
                + "(TO_DATE('2026-03-08','YYYY-MM-DD'),TO_TIMESTAMP('1988-04-17 02:30:00.123456','YYYY-MM-DD HH24:MI:SS.FF6'),TO_TIMESTAMP('2026-03-08 02:30:00.002','YYYY-MM-DD HH24:MI:SS.FF3'),1,'c'),"
                + "(TO_DATE('2026-03-08','YYYY-MM-DD'),TO_TIMESTAMP('1988-04-17 02:30:00.123457','YYYY-MM-DD HH24:MI:SS.FF6'),TO_TIMESTAMP('2026-03-08 02:30:00.001','YYYY-MM-DD HH24:MI:SS.FF3'),7,'d'),"
                + "(TO_DATE('2026-03-09','YYYY-MM-DD'),TO_TIMESTAMP('0001-01-01 00:00:00.000000','YYYY-MM-DD HH24:MI:SS.FF6'),TO_TIMESTAMP('1970-01-01 08:00:00.000','YYYY-MM-DD HH24:MI:SS.FF3'),7,'e')");
        var names = List.of("WM", "CREATED", "SLOT", "ID", "V"); var key = List.of("CREATED", "SLOT", "ID");
        var reader = runtime.reader(ds(), ORACLE); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "S", names, "WM", key, null, null, null, limits, false, pin))) {
            assertEquals(List.of("2026-03-09T00:00", "0001-01-01T00:00", "1970-01-01T08:00", "7"),
                    cursor.upperBound().values().stream().map(TypedCursorValue::value).toList());
            while (cursor.next()) seen.add((String) cursor.row()[4]);
        }
        assertEquals(List.of("b", "a", "c", "d", "e"), seen);
        transfer(names, key, this::save);
        assertEquals(5, count("T")); assertTrue(saved.get().complete());
        assertEquals(texts("SELECT ID,V FROM " + t("S") + " ORDER BY WM,CREATED,SLOT,ID"),
                texts("SELECT ID,V FROM " + t("T") + " ORDER BY WM,CREATED,SLOT,ID"));
    }

    @Test void textKeysPageInBinaryOrderAndReplayUnderTheIdenticalProfile() throws Exception {
        String shape = "(WM NUMBER NOT NULL,K VARCHAR2(20) NOT NULL,V VARCHAR2(20),PRIMARY KEY(K))";
        exec("CREATE TABLE " + t("S") + shape); exec("CREATE TABLE " + t("T") + shape);
        try (var connection = oracle(); var insert = connection.prepareStatement("INSERT INTO " + t("S") + " VALUES(1,?,?)")) {
            for (String key : List.of("a", "B", "b c", "Z", "汉", "🙂", "A", "b")) { insert.setString(1, key); insert.setString(2, key); insert.executeUpdate(); }
        }
        var names = List.of("WM", "K", "V"); var key = List.of("K");
        var reader = runtime.reader(ds(), ORACLE); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "S", names, "WM", key, null, null, null, limits, false, pin))) {
            while (cursor.next()) seen.add((String) cursor.row()[1]);
        }
        assertEquals(texts("SELECT K FROM " + t("S") + " ORDER BY WM,K"), seen);
        transfer(names, key, this::save);
        assertEquals(texts("SELECT K,V FROM " + t("S") + " ORDER BY K"), texts("SELECT K,V FROM " + t("T") + " ORDER BY K"));
        assertEquals(8, count("T"));
    }

    @Test void publicCursorPreservesExactPayloadDomains() throws Exception {
        String shape = "(WM NUMBER(38,18) NOT NULL,ID NUMBER(19,0) NOT NULL,AMOUNT NUMBER(38,18),NOTE VARCHAR2(80),NATIONAL NVARCHAR2(20),"
                + "PAYLOAD RAW(16),DAY DATE,HAPPENED TIMESTAMP(9),PRIMARY KEY(ID))";
        exec("CREATE TABLE " + t("S") + shape); exec("CREATE TABLE " + t("T") + shape);
        try (var connection = oracle(); var insert = connection.prepareStatement("INSERT INTO " + t("S") + " VALUES(?,?,?,?,?,?,TO_DATE(?,'YYYY-MM-DD'),TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS.FF9'))")) {
            insert.setBigDecimal(1, new BigDecimal("-1E-18")); insert.setLong(2, 9007199254740993L);
            insert.setBigDecimal(3, new BigDecimal("12345678901234567890.123456789012345678"));
            insert.setString(4, "汉字'\\\"; 🙂  "); insert.setNString(5, "国家");
            insert.setBytes(6, new byte[]{0, 39, 92, (byte) 255}); insert.setString(7, "0001-01-01"); insert.setString(8, "1988-04-17 02:30:00.123456789");
            insert.executeUpdate();
        }
        var result = new MigrationService(request(), ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertEquals(texts("SELECT WM,ID,AMOUNT,NOTE,NATIONAL,RAWTOHEX(PAYLOAD),TO_CHAR(DAY,'YYYY-MM-DD'),TO_CHAR(HAPPENED,'YYYY-MM-DD HH24:MI:SS.FF9') FROM " + t("S")),
                texts("SELECT WM,ID,AMOUNT,NOTE,NATIONAL,RAWTOHEX(PAYLOAD),TO_CHAR(DAY,'YYYY-MM-DD'),TO_CHAR(HAPPENED,'YYYY-MM-DD HH24:MI:SS.FF9') FROM " + t("T")));
        assertEquals(1, count("T"));
    }

    // ---- native MERGE UPSERT -------------------------------------------------------------------------

    @Test void upsertAcknowledgesInputRowsWhateverAffectedCountOracleReports() throws Exception {
        exec("CREATE TABLE " + t("T") + "(K NUMBER PRIMARY KEY,V VARCHAR2(20))");
        var writer = runtime.upsertWriter(ds(), ORACLE); var names = List.of("K", "V");
        writer.prepareUpsert(schema, "T", names, List.of("K"));
        for (var batch : List.of(List.<Object[]>of(new Object[]{1L, "new"}, new Object[]{2L, "new"}),
                List.<Object[]>of(new Object[]{1L, "changed"}, new Object[]{2L, "new"}),
                List.<Object[]>of(new Object[]{1L, "changed"}, new Object[]{2L, "new"}),
                List.<Object[]>of(new Object[]{3L, "first"}, new Object[]{3L, "last"}))) {
            var receipt = writer.writeWithReceipt(names, batch);
            assertEquals(2, receipt.acknowledgedRows());
            assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, receipt.commitAcknowledgement());
            assertNull(receipt.receipt());
        }
        assertEquals(List.of("1|changed", "2|new", "3|last"), texts("SELECT K,V FROM " + t("T") + " ORDER BY K"));
    }

    @Test void exactValuesSurviveInsertUpdateAndIdenticalReplay() throws Exception {
        exec("CREATE TABLE " + t("T") + "(K NUMBER(38,0) PRIMARY KEY,AMOUNT NUMBER(38,18),NOTE VARCHAR2(12),PAYLOAD RAW(3),DAY DATE,HAPPENED TIMESTAMP(9))");
        var writer = runtime.upsertWriter(ds(), ORACLE);
        var names = List.of("K", "AMOUNT", "NOTE", "PAYLOAD", "DAY", "HAPPENED");
        writer.prepareUpsert(schema, "T", names, List.of("K"));
        BigDecimal key = new BigDecimal("99999999999999999999999999999999999999");
        Object[] first = {key, new BigDecimal("-1E-18"), "汉字🙂'\\", new byte[]{0, 39, (byte) 255},
                java.time.LocalDateTime.parse("0001-01-01T00:00"), java.time.LocalDateTime.parse("1988-04-17T02:30:00.123456789")};
        Object[] second = {key, new BigDecimal("12345678901234567890.123456789012345678"), "x", new byte[]{1, 2, 3},
                java.time.LocalDateTime.parse("9999-12-31T23:59:59"), java.time.LocalDateTime.parse("9999-12-31T23:59:59.999999999")};
        for (int i = 0; i < 2; i++) assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(first)).acknowledgedRows());
        assertEquals(List.of(MAX38 + "|-0.000000000000000001|汉字🙂'\\|0027FF|0001-01-01|1988-04-17 02:30:00.123456789"),
                texts("SELECT K,TO_CHAR(AMOUNT,'FM99999999999999999990.999999999999999999'),NOTE,RAWTOHEX(PAYLOAD),TO_CHAR(DAY,'YYYY-MM-DD'),TO_CHAR(HAPPENED,'YYYY-MM-DD HH24:MI:SS.FF9') FROM " + t("T")));
        for (int i = 0; i < 2; i++) assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(second)).acknowledgedRows());
        assertEquals(List.of(MAX38 + "|12345678901234567890.123456789012345678|x|010203|9999-12-31|9999-12-31 23:59:59.999999999"),
                texts("SELECT K,TO_CHAR(AMOUNT,'FM99999999999999999990.999999999999999999'),NOTE,RAWTOHEX(PAYLOAD),TO_CHAR(DAY,'YYYY-MM-DD'),TO_CHAR(HAPPENED,'YYYY-MM-DD HH24:MI:SS.FF9') FROM " + t("T")));
    }

    @ParameterizedTest @ValueSource(strings = {"DECIMAL_SCALE", "DECIMAL_RANGE", "VARCHAR_OVERLONG", "RAW_OVERLONG",
            "TIMESTAMP_NANOS", "DATE_FRACTION", "NOT_NULL"})
    void exactValuePreflightRejectsTheWholeBatchBeforeAnyWrite(String kind) throws Exception {
        exec("CREATE TABLE " + t("T") + "(K NUMBER(19,0) PRIMARY KEY,D NUMBER(10,2),V VARCHAR2(3),R RAW(4),TS TIMESTAMP(3),DAY DATE,NN NUMBER NOT NULL)");
        var names = List.of("K", "D", "V", "R", "TS", "DAY", "NN");
        var writer = runtime.upsertWriter(ds(), ORACLE); writer.prepareUpsert(schema, "T", names, List.of("K"));
        Object[] good = {1L, new BigDecimal("1.23"), "abc", new byte[]{1, 2, 3, 4},
                java.time.LocalDateTime.parse("2026-01-02T03:04:05.123"), java.time.LocalDateTime.parse("2026-01-02T00:00"), 1L};
        Object[] bad = good.clone(); bad[0] = 2L;
        switch (kind) {
            case "DECIMAL_SCALE" -> bad[1] = new BigDecimal("1.235");
            case "DECIMAL_RANGE" -> bad[1] = new BigDecimal("123456789.10");
            case "VARCHAR_OVERLONG" -> bad[2] = "汉字";
            case "RAW_OVERLONG" -> bad[3] = new byte[]{1, 2, 3, 4, 5};
            case "TIMESTAMP_NANOS" -> bad[4] = java.time.LocalDateTime.parse("2026-01-02T03:04:05.1234");
            case "DATE_FRACTION" -> bad[5] = java.time.LocalDateTime.parse("2026-01-02T03:04:05.000000001");
            case "NOT_NULL" -> bad[6] = null;
            default -> throw new AssertionError(kind);
        }
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(names, List.of(good, bad)));
        assertEquals(0, count("T"));
        assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(good)).acknowledgedRows());
        assertEquals(1, count("T"));
    }

    @ParameterizedTest @ValueSource(strings = {"SECOND_UNIQUE", "NO_UNIQUE", "NULLABLE_UNIQUE", "KEY_SUBSET", "FUNCTIONAL_UNIQUE",
            "VIRTUAL_FIELD", "VIEW", "MISSING_FIELD", "TRIGGER"})
    void upsertRefusesAmbiguousConflictTargetsAndUnverifiedColumnsBeforeWriting(String kind) throws Exception {
        String definition = switch (kind) {
            case "SECOND_UNIQUE" -> "(K NUMBER PRIMARY KEY,V NUMBER NOT NULL,CONSTRAINT other UNIQUE(V))";
            case "NO_UNIQUE" -> "(K NUMBER NOT NULL,V NUMBER)";
            case "NULLABLE_UNIQUE" -> "(K NUMBER NULL,V NUMBER,CONSTRAINT nullable_key UNIQUE(K))";
            case "KEY_SUBSET" -> "(K NUMBER NOT NULL,V NUMBER NOT NULL,PRIMARY KEY(K,V))";
            case "FUNCTIONAL_UNIQUE" -> "(K NUMBER NOT NULL,V NUMBER)";
            case "VIRTUAL_FIELD" -> "(K NUMBER PRIMARY KEY,V NUMBER GENERATED ALWAYS AS (K+1) VIRTUAL)";
            default -> "(K NUMBER PRIMARY KEY,V NUMBER)";
        };
        exec("CREATE TABLE " + t("BASE") + definition);
        if (kind.equals("FUNCTIONAL_UNIQUE")) exec("CREATE UNIQUE INDEX FN_IDX ON " + t("BASE") + "(K+1)");
        if (kind.equals("VIEW")) exec("CREATE VIEW " + t("VIEWED") + " AS SELECT K,V FROM " + t("BASE"));
        if (kind.equals("TRIGGER")) exec("CREATE TRIGGER " + t("SIDE") + " BEFORE INSERT ON " + t("BASE")
                + " FOR EACH ROW BEGIN :NEW.V := 7; END;");
        String table = kind.equals("VIEW") ? "VIEWED" : "BASE";
        var names = kind.equals("MISSING_FIELD") ? List.of("K", "ABSENT") : List.of("K", "V");
        var writer = runtime.upsertWriter(ds(), ORACLE);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, table, names, List.of("K")));
        assertThrows(RuntimeException.class, () -> writer.write(names, Collections.singletonList(new Object[]{1L, 1})));
        assertEquals(0, count("BASE"));
    }

    @Test void serverConstraintFailureRollsBackTheWholeBatchAndIsNeverAcknowledged() throws Exception {
        exec("CREATE TABLE " + t("T") + "(K NUMBER PRIMARY KEY,V NUMBER,CONSTRAINT positive_value CHECK (V > 0))");
        var writer = runtime.upsertWriter(ds(), ORACLE); var names = List.of("K", "V");
        writer.prepareUpsert(schema, "T", names, List.of("K"));
        assertThrows(UnconfirmedWriteException.class, () -> writer.writeWithReceipt(names, List.of(new Object[]{1L, 1L}, new Object[]{2L, -1L})));
        assertEquals(0, count("T"));
    }

    @Test void keysetAndUpsertStatementsAreTheVerifiedDialectText() throws Exception {
        exec("CREATE TABLE " + t("S") + "(WM DATE NOT NULL,ID NUMBER(19,0) PRIMARY KEY,V VARCHAR2(20))");
        exec("CREATE TABLE " + t("T") + "(WM DATE NOT NULL,ID NUMBER(19,0) PRIMARY KEY,V VARCHAR2(20))");
        exec("INSERT INTO " + t("S") + " VALUES(TO_DATE('2026-03-08 02:30:00','YYYY-MM-DD HH24:MI:SS'),1,'a'),"
                + "(TO_DATE('2026-03-08 02:30:00','YYYY-MM-DD HH24:MI:SS'),2,'b'),(TO_DATE('2026-03-08 02:30:01','YYYY-MM-DD HH24:MI:SS'),3,'c')");
        var statements = new LinkedHashSet<String>();
        DataSource recording = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = oracle();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        if (method.getName().equals("prepareStatement") && String.valueOf(arguments[0]).contains(q(schema)))
                            statements.add(String.valueOf(arguments[0]).replace(q(schema), "USR"));
                        return method.invoke(actual, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        var names = List.of("WM", "ID", "V");
        CursorTransfer.execute(runtime, recording, recording, ORACLE, ORACLE, schema, "S", schema, "T", names, names,
                List.of("ID"), List.of("ID"), "WM", limits, 2, binding(this::save), new DbSwitchTableResult(), () -> { });
        assertTrue(statements.contains("MERGE INTO USR.\"T\" d USING(SELECT CAST(? AS NUMBER) \"ID\" FROM dual)s ON(d.\"ID\"=s.\"ID\")"
                + " WHEN MATCHED THEN UPDATE SET d.\"WM\"=?,d.\"V\"=? WHEN NOT MATCHED THEN INSERT(\"WM\",\"ID\",\"V\")VALUES(?,s.\"ID\",?)"), statements::toString);
        assertTrue(statements.stream().anyMatch(sql -> sql.startsWith("SELECT TO_CHAR(\"WM\",'YYYY-MM-DD HH24:MI:SS'),\"ID\",\"V\" FROM USR.\"S\" WHERE ((\"WM\"<")
                && sql.contains(" FETCH FIRST 2 ROWS ONLY")), statements::toString);
        assertTrue(statements.stream().anyMatch(sql -> sql.contains(" AND ((\"WM\">TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')) OR (\"WM\"=TO_DATE(?,'YYYY-MM-DD HH24:MI:SS') AND \"ID\">?))")), statements::toString);
        assertEquals(3, count("T")); assertTrue(saved.get().complete());
    }

    @Test void administratorHelpersOnlyReadThePdbAndNeverWriteAMarker() throws Exception {
        SourceGenerationPin again;
        try (var connection = oracle()) { again = OracleSourceGeneration.initialize(connection, schema, 15); }
        assertEquals(pin.instanceId(), again.instanceId()); assertNotEquals(pin.generation(), again.generation());
        String next = UUID.randomUUID().toString();
        SourceGenerationPin rotated;
        try (var connection = oracle()) { rotated = OracleSourceGeneration.rotate(connection, pin, next, 15); }
        assertEquals(new SourceGenerationPin(schema, pin.instanceId(), next), rotated);
        try (var connection = oracle()) { assertEquals(rotated, OracleSourceGeneration.rotate(connection, pin, next, 15)); }
        try (var connection = oracle()) { assertThrows(SQLException.class, () -> OracleSourceGeneration.rotate(connection, pin, pin.generation(), 15)); }
        try (var connection = oracle()) {
            assertThrows(SQLException.class, () -> OracleSourceGeneration.rotate(connection, new SourceGenerationPin(schema, UUID.randomUUID().toString(), pin.generation()), next, 15));
        }
        assertEquals("0", scalar("SELECT COUNT(*) FROM sys.all_objects WHERE owner='" + schema + "' AND object_type='TABLE' AND object_name IN ('DBSWITCH_SOURCE_GENERATION','DBSWITCH_SOURCE_GENERATION_HISTORY')"));
    }

    @ParameterizedTest @ValueSource(strings = {"LINGUISTIC_SESSION", "OPEN_TRANSACTION"})
    void sessionProfileIsProvenOnTheActualConnectionBeforeReadingOrWriting(String kind) throws Exception {
        create("S"); create("T"); rows(1);
        DataSource altered = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = oracle();
                try {
                    switch (kind) {
                        case "LINGUISTIC_SESSION" -> sql(connection, "ALTER SESSION SET NLS_COMP='LINGUISTIC' NLS_SORT='GENERIC_M'");
                        default -> { connection.setAutoCommit(false); sql(connection, "SELECT 1 FROM dual"); }
                    }
                    return connection;
                } catch (SQLException failure) { connection.close(); throw failure; }
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        assertThrows(IllegalArgumentException.class, () -> transfer(altered, this::save));
        assertNull(saved.get()); assertEquals(0, count("T")); assertEquals(1, count("S"));
        transfer(this::save);
        assertEquals(1, count("T")); assertTrue(saved.get().complete());
    }

    @Test void secondPrecisionTimestampWatermarkKeyAndPayloadDoNotUseFractionFormat() throws Exception {
        for (String table : List.of("S", "T")) exec("CREATE TABLE " + t(table) + "(WM TIMESTAMP(0) NOT NULL,K TIMESTAMP(0) PRIMARY KEY,V TIMESTAMP(0))");
        exec("INSERT INTO " + t("S") + " VALUES(TIMESTAMP '1988-04-17 02:30:00',TIMESTAMP '2026-10-08 12:34:56',TIMESTAMP '1988-04-17 02:30:00')");
        for (int i = 0; i < 2; i++) transfer(List.of("WM", "K", "V"), List.of("K"), this::save);
        assertEquals(1, count("T"));
        assertEquals("1988-04-17 02:30:00", scalar("SELECT TO_CHAR(V,'YYYY-MM-DD HH24:MI:SS') FROM " + t("T")));
    }

    // ---- fixture ---------------------------------------------------------------------------------------

    private MigrationRequest request() {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ORACLE); input.setUrl(url()); input.setUsername(schema); input.setPassword(userPassword);
        input.setSourceSchema(schema); input.setSourceIncludes("S"); input.setFetchSize(2); input.setIncreaseColumnName("WM");
        input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1"); input.setRegexTableMapper(List.of(new PatternMapper("^S$", "T")));
        request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ORACLE); output.setUrl(url());
        output.setUsername(schema); output.setPassword(userPassword); output.setTargetSchema(schema); output.setPoolSize(1);
        output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("S", binding(this::save)));
        return request;
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint checkpoint) { saved.set(checkpoint); return true; }
    private DbSwitchTableResult transfer(CursorRunBinding.CheckpointStore store) throws Exception { return transfer(ds(), store); }
    private DbSwitchTableResult transfer(DataSource targetSource, CursorRunBinding.CheckpointStore store) throws Exception {
        return transfer(fields, keys, targetSource, store);
    }
    private DbSwitchTableResult transfer(List<String> names, List<String> key, CursorRunBinding.CheckpointStore store) throws Exception {
        return transfer(names, key, ds(), store);
    }
    private DbSwitchTableResult transfer(List<String> names, List<String> key, DataSource targetSource, CursorRunBinding.CheckpointStore store) throws Exception {
        var result = new DbSwitchTableResult();
        CursorTransfer.execute(runtime, ds(), targetSource, ORACLE, ORACLE, schema, "S", schema, "T", names, names, key, key,
                "WM", limits, 2, binding(store), result, () -> { });
        return result;
    }
    private void create(String table) throws SQLException {
        exec("CREATE TABLE " + t(table) + "(WM NUMBER(38,18) NOT NULL,K1 NUMBER(19,0) NOT NULL,K2 NUMBER(38,0) NOT NULL,V VARCHAR2(80),PRIMARY KEY(K2,K1))");
    }
    private void rows(int count) throws SQLException {
        for (int i = 1; i <= count; i++) exec("INSERT INTO " + t("S") + " VALUES(10," + i + "," + (i % 2) + ",'original')");
    }
    private long count(String table) throws SQLException { return Long.parseLong(scalar("SELECT COUNT(*) FROM " + t(table))); }
    private List<String> texts(String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var connection = oracle(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(20);
            try (var rows = statement.executeQuery(sql)) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    var line = new StringJoiner("|");
                    for (int i = 1; i <= columns; i++) line.add(String.valueOf(rows.getString(i)));
                    values.add(line.toString());
                }
            }
        }
        return values;
    }
    private String scalar(String sql) throws SQLException {
        try (var connection = oracle(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); String value = rows.getString(1); assertFalse(rows.next()); return value;
        }
    }
    private Connection oracle() throws SQLException { return runtime.openConnection(ORACLE, null, url(), schema, userPassword); }
    private void exec(String sql) throws SQLException { try (var connection = oracle()) { sql(connection, sql); } }
    private DataSource ds() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return oracle(); }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Explicit fixture principal required"); }
        };
    }
    private String t(String table) { return q(schema) + "." + q(table); }
    private static void sql(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); } }
    private static String q(String name) { return '"' + name + '"'; }
    private static String url() { return System.getProperty("datahub.test.oracle.url"); }
    private static final String MAX38 = "99999999999999999999999999999999999999";
}
