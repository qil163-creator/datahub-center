package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.GbaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

/**
 * Real GBase 8a 8.6.2.43-R7-free typed cursor: pinned fixed-upper keyset without a snapshot (GBase 8a has no
 * snapshot isolation and keeps PRIMARY KEY as metadata without uniqueness enforcement), with generation/relation
 * rechecks on every page, against the transactional PostgreSQL UPSERT target. Native GBase target UPSERT has
 * separate locked-batch tests; cursor writes require a managed target-generation pin before any side effect.
 */
@Timeout(300)
@EnabledIfSystemProperty(named = "datahub.test.gbase8a.url", matches = "jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
@EnabledIfSystemProperty(named = "datahub.test.gbase8a.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_GBASE8A_TEST_PASSWORD", matches = ".+")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationCursorGbase8aTest {
    private static final BatchLimits LIMITS = new BatchLimits(8, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0);
    private static final String TABLE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT,d DECIMAL(38,8),note VARCHAR(64),at DATETIME,day DATE,PRIMARY KEY(k,id))"
            + " ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DISTRIBUTED BY('id')";
    private static final String SIMPLE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,note VARCHAR(64),PRIMARY KEY(k,id))"
            + " ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DISTRIBUTED BY('id')";
    private static final String PG_TABLE = "(k bigint NOT NULL, id bigint NOT NULL, v integer, d numeric(38,8), note varchar(64),"
            + " at timestamp(6), day date, PRIMARY KEY (k, id))";
    private static final String PG_SIMPLE = "(k bigint NOT NULL, id bigint NOT NULL, note varchar(64), PRIMARY KEY (k, id))";
    private static final List<String> FIELDS = List.of("k", "id", "v", "d", "note", "at", "day");
    private static final List<String> SIMPLE_FIELDS = List.of("k", "id", "note");
    private static final List<String> KEYS = List.of("k", "id");
    private static final BigDecimal MAX_DECIMAL = new BigDecimal("999999999999999999999999999999.99999999");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private Connection control;
    private String source, pgSchema;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> databases = new ArrayList<>();
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        Class.forName("com.gbase.jdbc.Driver");
        control = DriverManager.getConnection(url(), "root", password());
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = database("gcur_s_" + id); pgSchema = "gcur_t_" + id;
        pin = Gbase8aSourceGeneration.initialize(control, source, 15);
        runtime = new DbSwitchRuntime(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover(), OPTIONS, null);
        try (var pg = DriverManager.getConnection(pgUrl(), pgUser(), pgPassword())) {
            pgSql(pg, "CREATE SCHEMA \"" + pgSchema + "\"");
        }
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally {
            try (var owned = control) {
                for (String database : databases) sql("DROP DATABASE IF EXISTS " + database);
            }
            try (var pg = DriverManager.getConnection(pgUrl(), pgUser(), pgPassword())) {
                pgSql(pg, "DROP SCHEMA IF EXISTS \"" + pgSchema + "\" CASCADE");
            }
        }
    }

    @Test void keysetPagesDeliverExactValuesAndResumeReplaysTheAppliedWatermarkGroup() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + TABLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_TABLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,10,1,12.34000000,'一','2026-10-03 12:34:56.123456','2026-10-01'),"
                + "(1,20,2,-0.00000001,'','0001-01-01 00:00:00.000001','9999-12-31'),"
                + "(2,5,3,NULL,NULL,NULL,NULL),(2,6,4,0,'x ','2026-01-01 00:00:00','2026-02-01'),"
                + "(3,1,5," + MAX_DECIMAL + ",'last','9999-12-31 23:59:59.999999','2026-03-01')");
        // Page size 2 splits the (2,*) watermark group across pages and proves the ORDER BY matches the model.
        transfer("s", "t", FIELDS, 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of("3", "1"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals(List.of("3", "1"), saved.get().fixedUpper().values().stream().map(v -> v.value()).toList());
        assertEquals(pgRows("t", FIELDS), rows(source, "s"));
        // Equal-watermark rows that became visible later are replayed by the next run; a newer watermark row follows.
        sql("INSERT INTO " + table(source, "s") + " VALUES (3,0,6,2.00000000,'late','2026-10-04 00:00:00','2026-10-04'),(4,9,7,3,'new',NULL,NULL)");
        // Older than the applied watermark: outside the cursor window by contract (needs DIFF or an explicit rewind).
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,99,8,4,'old',NULL,NULL)");
        transfer("s", "t", FIELDS, 2, this::save);
        var expected = rows(source, "s"); expected.removeIf(row -> row.get(0).equals("1") && row.get(1).equals("99"));
        assertEquals(expected, pgRows("t", FIELDS));
        long sequence = saved.get().sequence();
        transfer("s", "t", FIELDS, 2, this::save);
        assertEquals(expected, pgRows("t", FIELDS)); assertTrue(saved.get().sequence() > sequence, "The applied watermark group is replayed idempotently");
    }

    @Test void fixedUpperBoundIgnoresRowsBeyondItUntilTheNextRun() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(2,1,'b')");
        var operator = new GbaseDatabaseOperator(gbaseDs()); operator.configure(OPTIONS); operator.setFetchSize(1);
        var read = new ArrayList<String>();
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals(List.of("2", "1"), cursor.upperBound().values().stream().map(v -> v.value()).toList());
            while (cursor.next()) {
                read.add(cursor.row()[0] + "@" + cursor.row()[1]);
                if (read.size() == 1) {
                    // (1,2) sorts inside the frozen bound and becomes visible before its page: it is still delivered.
                    sql("INSERT INTO " + table(source, "s") + " VALUES (1,2,'mid'),(2,2,'late')");
                }
            }
        }
        assertEquals(List.of("1@1", "1@2", "2@1"), read, "(2,2) is beyond the fixed upper bound");
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertEquals(rows(source, "s"), pgRows("t", SIMPLE_FIELDS));
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysAsAnIdempotentOverwrite() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(1,2,'b'),(2,1,'c'),(2,2,'d')");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("s", "t", SIMPLE_FIELDS, 1,
                value -> calls.incrementAndGet() != 3 && save(value)));
        awaitCount("t", 2, 10); assertFalse(saved.get().complete()); assertEquals(1, saved.get().sequence());
        // A stale value at an applied key is overwritten by the replay, not duplicated.
        pgSql("UPDATE \"" + pgSchema + "\".t SET note='stale' WHERE k=1 AND id=2");
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "s"), pgRows("t", SIMPLE_FIELDS));
    }

    @Test void lostCommitAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(2,1,'b')");
        var fault = new AtomicBoolean(true);
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, gbaseDs(), faultPgDs(fault), ProductTypeEnum.GBASE8A,
                ProductTypeEnum.POSTGRESQL, source, "s", pgSchema, "t", SIMPLE_FIELDS, SIMPLE_FIELDS, KEYS, KEYS, "k",
                LIMITS, 2, binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(fault.get()); awaitCount("t", 2, 10);
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertEquals(rows(source, "s"), pgRows("t", SIMPLE_FIELDS));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "TABLE_RECREATE", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationRelationOrTargetBeforeWriting(String change) throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        transfer("s", "t", SIMPLE_FIELDS, 2, this::save);
        assertEquals(1, count("t"));
        switch (change) {
            case "PIN_ROTATE" -> {Gbase8aSourceGeneration.fence(control,pin,15);pin = Gbase8aSourceGeneration.rotate(control, pin, UUID.randomUUID().toString(), 15);}
            case "TABLE_RECREATE" -> { sql("DROP TABLE " + table(source, "s")); sql("CREATE TABLE " + table(source, "s")
                    + "(k BIGINT NOT NULL,id BIGINT NOT NULL,note VARCHAR(80),PRIMARY KEY(k,id)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DISTRIBUTED BY('id')");
                sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TARGET_RECREATE" -> { pgSql("DROP TABLE \"" + pgSchema + "\".t"); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE); }
            default -> { }
        }
        sql("INSERT INTO " + table(source, "s") + " VALUES (2,1,'new')");
        long before = count("t");
        if (change.equals("NONE")) {
            transfer("s", "t", SIMPLE_FIELDS, 2, this::save);
            assertEquals(before + 1, count("t")); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "t", SIMPLE_FIELDS, 2, this::save));
        assertEquals(before, count("t"));
    }

    @Test void unsupportedSourceShapesFailBeforeReading() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        sql("CREATE TABLE " + table(source, "dup") + SIMPLE);
        sql("INSERT INTO " + table(source, "dup") + " VALUES (1,1,'a'),(1,1,'b')");
        sql("CREATE TABLE " + table(source, "nullable") + "(k BIGINT NULL,id BIGINT NOT NULL,note VARCHAR(64),PRIMARY KEY(id)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DISTRIBUTED BY('id')");
        sql("INSERT INTO " + table(source, "nullable") + " VALUES (1,1,'x')");
        sql("CREATE TABLE " + table(source, "charkey") + "(w CHAR(8) NOT NULL,v INT,PRIMARY KEY(w)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DISTRIBUTED BY('w')");
        sql("CREATE TABLE " + table(source, "mem") + "(k INT NOT NULL,v INT,PRIMARY KEY(k)) ENGINE=MEMORY");
        sql("CREATE TABLE " + table(source, "txt") + "(w VARCHAR(20) NOT NULL,v INT,PRIMARY KEY(w)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DISTRIBUTED BY('w')");
        sql("INSERT INTO " + table(source, "txt") + " VALUES ('a',1)");
        SourceGenerationPin foreign = Gbase8aSourceGeneration.initialize(control, database("gcur_f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)), 15);
        var operator = new GbaseDatabaseOperator(gbaseDs()); operator.configure(OPTIONS); operator.setFetchSize(2);
        var rejected = List.<CursorReadRequest>of(
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, null),
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, foreign),
                new CursorReadRequest(source, "dup", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "nullable", SIMPLE_FIELDS, "k", List.of("id"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "charkey", List.of("w", "v"), "w", List.of("w"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "mem", List.of("k", "v"), "k", List.of("k"), null, null, null, LIMITS, false, pin));
        for (var request : rejected) assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request), request.table());
        // Text keys read fine under the identical native contract; the watermark can be text as well.
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "txt", List.of("w", "v"), "w", List.of("w"), null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
        }
        // Cross-product replay of a nonnumeric key is rejected before any write reaches the PostgreSQL target.
        pgSql("CREATE TABLE \"" + pgSchema + "\".t (w varchar(20) NOT NULL, v integer, PRIMARY KEY (w))");
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, gbaseDs(), pgDs(), ProductTypeEnum.GBASE8A, ProductTypeEnum.POSTGRESQL,
                source, "txt", pgSchema, "t", List.of("w", "v"), List.of("w", "v"), List.of("w"), List.of("w"), "w", LIMITS, 2,
                binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertEquals(0, count("t"));
    }

    @Test void gbaseTargetCursorRequiresItsOwnManagedPinBeforeAnySideEffect() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        sql("CREATE TABLE " + table(source, "t2") + SIMPLE);
        sql("INSERT INTO " + table(source, "t2") + " VALUES (99,99,'sentinel')");
        var before = rows(source, "t2");
        assertNotNull(runtime.upsertWriter(gbaseDs(), ProductTypeEnum.GBASE8A));
        assertEquals(before, rows(source, "t2"));
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, gbaseDs(), gbaseDs(), ProductTypeEnum.GBASE8A,
                ProductTypeEnum.GBASE8A, source, "s", source, "t2", SIMPLE_FIELDS, SIMPLE_FIELDS, KEYS, KEYS, "k", LIMITS, 2,
                binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertEquals(before, rows(source, "t2")); assertNull(saved.get());
    }

    @Test void ordinarySourceDmlAcrossSecondsKeepsItsPhysicalIdentityAndResumes() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'first')");
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        var before = Gbase8aCursorMetadata.read(control, source, "s", 15);
        // Cross a native CREATE_TIME second boundary; its refresh timing is not a relation identity contract.
        Thread.sleep(1200);
        sql("INSERT INTO " + table(source, "s") + " VALUES (2,1,'later')");
        sql("LOCK TABLES " + table(source, "s") + " READ");
        sql("UNLOCK TABLES");
        var after = Gbase8aCursorMetadata.read(control, source, "s", 15);
        assertEquals(before.physicalTableId(), after.physicalTableId());
        assertEquals(before.relationEvidence(), after.relationEvidence());
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "s"), pgRows("t", SIMPLE_FIELDS));
        assertEquals(2, count("t"));
    }

    @Test void publicServiceRunsAPinnedCursorAndResumeDeliversNewRows() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); pgSql("CREATE TABLE \"" + pgSchema + "\".t " + PG_SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,2,'a'),(1,1,'b'),(2,1,'c')");
        var request = request(ProductTypeEnum.POSTGRESQL, pgSchema, "t");
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertTrue(saved.get().complete()); assertEquals(rows(source, "s"), pgRows("t", SIMPLE_FIELDS));
        sql("INSERT INTO " + table(source, "s") + " VALUES (2,0,'d')");
        request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(rows(source, "s"), pgRows("t", SIMPLE_FIELDS));
    }

    private MigrationRequest request(ProductTypeEnum targetProduct, String targetSchema, String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.GBASE8A); input.setDriverClassName("com.gbase.jdbc.Driver");
        input.setUrl(url()); input.setUsername("root"); input.setPassword(password()); input.setPoolSize(2); input.setFetchSize(2);
        input.setSourceSchema(source); input.setSourceIncludes("s"); input.setIncreaseColumnName("k"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setSourcePrimaryKeys(KEYS);
        input.setRegexTableMapper(List.of(new PatternMapper("^s$", targetTable))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(targetProduct); output.setDriverClassName("org.postgresql.Driver");
        output.setUrl(pgUrl()); output.setUsername(pgUser()); output.setPassword(pgPassword()); output.setPoolSize(2); output.setTargetSchema(targetSchema);
        output.setBatchLimits(LIMITS); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("s", binding(this::save)));
        return request;
    }
    private void transfer(String sourceTable, String targetTable, List<String> fields, int fetch, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, gbaseDs(), pgDs(), ProductTypeEnum.GBASE8A, ProductTypeEnum.POSTGRESQL, source, sourceTable, pgSchema, targetTable,
                fields, fields, KEYS, KEYS, "k", LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private String database(String name) throws Exception { sql("CREATE DATABASE " + name); databases.add(name); return name; }
    private static String table(String schema, String table) { return "`" + schema + "`.`" + table + "`"; }

    private List<List<Object>> rows(String schema, String table) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT * FROM " + table(schema, table) + " ORDER BY k,id")) {
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
    private List<List<Object>> pgRows(String table, List<String> fields) throws Exception {
        try (var pg = DriverManager.getConnection(pgUrl(), pgUser(), pgPassword())) {
            var select = new ArrayList<String>();
            for (String field : fields) select.add("\"" + field + "\"::text");
            var order = "\"k\",\"id\"";
            try (var statement = pg.createStatement(); var result = statement.executeQuery("SELECT " + String.join(",", select)
                    + " FROM \"" + pgSchema + "\"." + table + " ORDER BY " + order)) {
                var rows = new ArrayList<List<Object>>();
                while (result.next()) {
                    var row = new ArrayList<Object>();
                    for (int i = 1; i <= fields.size(); i++) row.add(canonical(result.getString(i)));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private static String canonical(Object value) {
        if (value == null) return null;
        if (value instanceof String text && text.matches("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?")) {
            String temporal = text.replace(' ', 'T');
            if (!temporal.matches(".*:\\d{2}:\\d{2}.*")) temporal += ":00";
            return temporal;
        }
        if (value instanceof java.sql.Timestamp stamp) return strictSeconds(stamp.toLocalDateTime().toString());
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof LocalDateTime time) return strictSeconds(time.toString());
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        return String.valueOf(value);
    }
    private static String strictSeconds(String iso) {
        return iso.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}") ? iso + ":00" : iso;
    }
    private long count(String table) throws Exception {
        try (var pg = DriverManager.getConnection(pgUrl(), pgUser(), pgPassword());
             var statement = pg.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM \"" + pgSchema + "\"." + table)) {
            result.next(); return result.getLong(1);
        }
    }
    private void awaitCount(String table, long expected, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long actual = -1;
        while (System.currentTimeMillis() < deadline) {
            actual = count(table);
            if (actual == expected) return;
            Thread.sleep(200);
        }
        fail("Target row count is " + actual + ", expected " + expected);
    }
    private static void pgSql(Connection pg, String text) throws SQLException { try (var statement = pg.createStatement()) { statement.setQueryTimeout(20); statement.execute(text); } }
    private static void pgSql(String text) throws SQLException { try (var pg = DriverManager.getConnection(pgUrl(), pgUser(), pgPassword())) { pgSql(pg, text); } }
    private static String url() { return System.getProperty("datahub.test.gbase8a.url"); }
    private static String password() { return System.getenv("DATAHUB_GBASE8A_TEST_PASSWORD"); }
    private static String pgUrl() { return System.getProperty("datahub.test.postgres.url"); }
    private static String pgUser() { return System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
    private static String pgPassword() { return System.getProperty("datahub.test.postgres.password", ""); }
    private void sql(String text) throws Exception { try (var statement = control.createStatement()) { statement.setQueryTimeout(30); statement.execute(text); } }
    private DataSource gbaseDs() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url(), "root", password());
            }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        };
    }
    private DataSource pgDs() { return new DriverManagerDataSource(pgUrl(), pgUser(), pgPassword()); }
    /** Owned PG connections whose commit returns and then throws: a lost commit acknowledgement after the real commit. */
    private DataSource faultPgDs(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = new DriverManagerDataSource(pgUrl(), pgUser(), pgPassword()).getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(real, args);
                        if (fault != null && method.getName().equals("commit") && fault.compareAndSet(true, false))
                            throw new SQLException("injected commit acknowledgement loss after the real commit");
                        return value;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        };
    }
}
