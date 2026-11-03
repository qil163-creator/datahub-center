package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.HiveSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.HiveDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.hive.HiveUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real Hive 4.1.0 typed cursor: directory identity, fixed upper bound, keyset paging, full ACID ORC upsert target. */
@Timeout(600)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "datahub.test.hive.url", matches = "jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.hive.driver", matches = ".+")
class MigrationCursorHiveTest {
    private static final List<String> FIELDS = List.of("w", "id", "note");
    private static final List<String> KEYS = List.of("w", "id");
    private static final BatchLimits LIMITS = new BatchLimits(3, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 90, 0, 0, 250, 0);
    private final String schema = "native_hive_cursor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private String source, target;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private com.zaxxer.hikari.HikariDataSource pool;
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeAll void start() throws Exception {
        Path artifact = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        var spec = new DbSwitchProperties.Driver(); spec.setPath(artifact.toString());
        spec.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(artifact))));
        var drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, spec));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers);
        var properties = new TargetDataSourceProperties(); properties.setTargetProductType(ProductTypeEnum.HIVE);
        properties.setUrl(url()); properties.setUsername("hive"); properties.setPassword(""); properties.setPoolSize(6);
        pool = DataSourceUtils.createTargetDataSource(properties, null, null, drivers.find(ProductTypeEnum.HIVE).orElseThrow());
        sql("CREATE DATABASE " + schema);
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            pin = HiveSourceGeneration.initialize(connection, schema, 90);
        }
    }
    @AfterAll void stop() throws Exception {
        try { if (pool != null) sql("DROP DATABASE IF EXISTS " + schema + " CASCADE"); }
        finally {
            try { if (pool != null) pool.close(); }
            finally { if (runtime != null) runtime.close(); }
        }
    }
    @BeforeEach void tableNames() {
        source = "src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        target = "dst_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        saved.set(null);
    }
    @AfterEach void dropTables() throws Exception {
        sql("DROP TABLE IF EXISTS " + name(source)); sql("DROP TABLE IF EXISTS " + name(target));
    }

    @Test void keysetPagesEqualWatermarkGroupsAndReplaysTheAppliedGroup() throws Exception {
        create(source); create(target);
        insert(source, "(1,1,'a'),(1,2,'b'),(2,1,'c'),(2,2,'d'),(3,1,'e')");
        transfer(source, target, FIELDS, KEYS, 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of("3", "1"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals(List.of("3", "1"), saved.get().fixedUpper().values().stream().map(v -> v.value()).toList());
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
        // A completed run resumes from the applied watermark group and re-freezes the upper bound: equal-watermark
        // rows that became visible later are replayed, and newer rows are delivered in the same run.
        insert(source, "(3,0,'late'),(4,1,'next')");
        transfer(source, target, FIELDS, KEYS, 2, this::save);
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
        long sequence = saved.get().sequence();
        transfer(source, target, FIELDS, KEYS, 2, this::save);
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
        assertTrue(saved.get().sequence() > sequence, "The applied watermark group is replayed idempotently");
    }

    @Test void textKeysAndTimestampWatermarkKeepNativeOrderAndReplay() throws Exception {
        createTs(source); createTs(target);
        insertTs(source, "('2026-09-25 10:00:00','a',1),('2026-09-25 10:00:00',decode(unhex('E4B8AD'),'UTF-8'),2),('2026-09-25 11:00:00','b',3)");
        transfer(source, target, List.of("ts", "id", "v"), List.of("ts", "id"), 1, this::save);
        assertEquals(List.of("2026-09-25T11:00", "b"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals("timestamp", saved.get().applied().values().getFirst().typeName());
        assertEquals(rows(source, "ts,id,v", "ts,id"), rows(target, "ts,id,v", "ts,id"));
        insertTs(source, "('2026-09-25 11:00:00','a',4)");
        transfer(source, target, List.of("ts", "id", "v"), List.of("ts", "id"), 1, this::save);
        assertEquals(rows(source, "ts,id,v", "ts,id"), rows(target, "ts,id,v", "ts,id"));
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysIdempotently() throws Exception {
        create(source); create(target);
        insert(source, "(1,1,'a'),(1,2,'b'),(2,1,'c'),(2,2,'d'),(3,1,'e')");
        var calls = new AtomicInteger();
        // Store calls: 1 = initial bound freeze, 2 = first flush, 3 = second flush (fails), 4+ = later flushes.
        // The second flush is applied by the target before its checkpoint CAS fails, so it must not be re-sent as a new batch.
        assertThrows(IllegalStateException.class, () -> transfer(source, target, FIELDS, KEYS, 2,
                value -> calls.incrementAndGet() != 3 && save(value)));
        assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        assertEquals(4, count(target), "Applied batches stay applied without their checkpoint");
        sql("UPDATE " + name(target) + " SET note='stale' WHERE w=1 AND id=1");
        transfer(source, target, FIELDS, KEYS, 2, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
    }

    @Test void lostInsertAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        create(source); create(target);
        insert(source, "(1,1,'a'),(2,1,'b')");
        var fault = new AtomicBoolean(true);
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, pool, ds(fault), ProductTypeEnum.HIVE,
                ProductTypeEnum.HIVE, schema, source, schema, target, FIELDS, FIELDS, KEYS, KEYS, "w",
                LIMITS, 2, binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(fault.get()); assertTrue(count(target) >= 1, "The injected loss happened after a real write");
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer(source, target, FIELDS, KEYS, 2, this::save);
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "SOURCE_RECREATE", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationRelationOrTargetBeforeWriting(String change) throws Exception {
        create(source); create(target);
        insert(source, "(1,1,'a')");
        transfer(source, target, FIELDS, KEYS, 2, this::save);
        assertEquals(1, count(target));
        switch (change) {
            case "PIN_ROTATE" -> { try (var c = pool.getConnection()) { HiveJdbcWriteSupport.session(c, 90);
                pin = HiveSourceGeneration.rotate(c, pin, UUID.randomUUID().toString(), 90); } }
            case "SOURCE_RECREATE" -> { sql("DROP TABLE " + name(source)); create(source); insert(source, "(1,1,'x')"); }
            case "TARGET_RECREATE" -> { sql("DROP TABLE " + name(target)); create(target); }
            default -> { }
        }
        insert(source, "(2,1,'new')");
        if (change.equals("NONE")) {
            // Control: the unchanged generation, relations and pin resume and deliver the new row.
            transfer(source, target, FIELDS, KEYS, 2, this::save);
            assertEquals(2, count(target));
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer(source, target, FIELDS, KEYS, 2, this::save));
        assertEquals(change.equals("TARGET_RECREATE") ? 0 : 1, count(target));
    }

    @Test void fixedUpperBoundIgnoresRowsArrivingDuringTheRunUntilTheNextRun() throws Exception {
        create(source);
        insert(source, "(1,1,'a'),(2,1,'b')");
        var operator = new HiveDatabaseOperator(pool); operator.configure(OPTIONS); operator.setFetchSize(1);
        var read = new ArrayList<String>();
        try (var cursor = operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "w", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals(List.of("2", "1"), cursor.upperBound().values().stream().map(v -> v.value()).toList());
            while (cursor.next()) {
                read.add(cursor.row()[0] + "@" + cursor.row()[1]);
                if (read.size() == 1) insert(source, "(2,0,'late-in-window'),(3,1,'above-upper')");
            }
        }
        // (2,0) sorts after the first page position and inside the frozen upper; (3,1) is above it.
        assertEquals(List.of("1@1", "2@0", "2@1"), read);
        create(target);
        transfer(source, target, FIELDS, KEYS, 1, this::save);
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
        assertEquals(4, count(target));
    }

    @Test void upsertRequiresFullAcidOrcVerifiedUniqueKeysAndRejectsBeforeAnyWrite() throws Exception {
        sql("CREATE TABLE " + name(target) + " (w BIGINT,id BIGINT,note STRING) STORED AS TEXTFILE");
        var writer = new HiveUpsertWriter(pool); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, target, FIELDS, KEYS));
        assertEquals(0, count(target));
        sql("DROP TABLE " + name(target));
        create(target); insert(target, "(1,1,'a'),(1,1,'b')");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, target, FIELDS, KEYS));
        sql("DROP TABLE " + name(target));
        create(target);
        writer.prepareUpsert(schema, target, FIELDS, KEYS);
        assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class, () ->
                writer.writeWithReceipt(FIELDS, List.<Object[]>of(new Object[]{1L, null, "x"})));
        assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class, () ->
                writer.writeWithReceipt(FIELDS, List.<Object[]>of(new Object[]{1L, 1L, "x"}, new Object[]{1L, 1L, "y"})));
        assertEquals(0, count(target));
    }

    @Test void upsertInsertsMissingUpdatesExistingAndReplaysIdempotently() throws Exception {
        create(target); insert(target, "(1,1,'old'),(2,1,'keep')");
        var writer = new HiveUpsertWriter(pool); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, target, FIELDS, KEYS);
        assertEquals(3, writer.writeWithReceipt(FIELDS, List.<Object[]>of(
                new Object[]{1L, 1L, "new"}, new Object[]{2L, 1L, "keep"}, new Object[]{3L, 1L, "fresh"})).acknowledgedRows());
        assertEquals(rows(target, "w,id,note", "w,id"), List.of(List.of("1", "1", "new"), List.of("2", "1", "keep"), List.of("3", "1", "fresh")));
        // Replay of the same batch converges; a stale target value at an applied key is overwritten, not duplicated.
        sql("UPDATE " + name(target) + " SET note='stale' WHERE w=3 AND id=1");
        assertEquals(3, writer.writeWithReceipt(FIELDS, List.<Object[]>of(
                new Object[]{1L, 1L, "new"}, new Object[]{2L, 1L, "keep"}, new Object[]{3L, 1L, "fresh"})).acknowledgedRows());
        assertEquals(rows(target, "w,id,note", "w,id"), List.of(List.of("1", "1", "new"), List.of("2", "1", "keep"), List.of("3", "1", "fresh")));
    }

    @Test void publicServiceRunsAPinnedCursorAndReplaysTheAppliedGroup() throws Exception {
        create(source); create(target);
        insert(source, "(1,1,'a'),(1,2,'b'),(2,1,'c')");
        var request = request(target);
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
        insert(source, "(2,0,'late')");
        request.getTarget().setCursorRunBindings(Map.of(source, binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(rows(source, "w,id,note", "w,id"), rows(target, "w,id,note", "w,id"));
    }

    @Test void directoryIdentityChangesWhenTheSourceIsRecreatedOrReshaped() throws Exception {
        sql("CREATE TABLE " + name(source) + " (w BIGINT,note STRING) STORED AS TEXTFILE");
        String first;
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            first = cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata.read(connection, schema, source, 90).fingerprint();
        }
        // A column reshape changes the materialized identity.
        sql("ALTER TABLE " + name(source) + " ADD COLUMNS (extra BIGINT)");
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            assertNotEquals(first, cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata.read(connection, schema, source, 90).fingerprint(),
                    "A column reshape must change the identity");
        }
        // Recreation changes CreateTime; Hive stores it with second granularity, so cross the boundary.
        sql("DROP TABLE " + name(source)); Thread.sleep(1100);
        sql("CREATE TABLE " + name(source) + " (w BIGINT,note STRING,extra BIGINT) STORED AS TEXTFILE");
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            assertNotEquals(first, cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata.read(connection, schema, source, 90).fingerprint(),
                    "Table recreation must change the directory identity");
        }
    }

    @Test void partitionedSourceKeysetPagesAcrossPartitionDirectoriesAndReplays() throws Exception {
        sql("CREATE TABLE " + name(source) + " (w BIGINT,id BIGINT,note STRING) PARTITIONED BY (region STRING) STORED AS TEXTFILE");
        sql("INSERT INTO TABLE " + name(source) + " (w,id,note,region) VALUES "
                + "(1,1,'a','cn'),(1,2,'b','cn'),(2,1,'c','us'),(2,2,'d','us')");
        create(target);
        // The target keeps the partition column as an ordinary verified unique-key field.
        sql("DROP TABLE " + name(target));
        sql("CREATE TABLE " + name(target) + " (w BIGINT,id BIGINT,note STRING,region STRING) STORED AS ORC TBLPROPERTIES ('transactional'='true')");
        transfer(source, target, List.of("w", "id", "note", "region"), List.of("w", "id"), 2, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "w,id,note,region", "w,id"), rows(target, "w,id,note,region", "w,id"));
        sql("INSERT INTO TABLE " + name(source) + " (w,id,note,region) VALUES (2,0,'late','cn')");
        transfer(source, target, List.of("w", "id", "note", "region"), List.of("w", "id"), 2, this::save);
        assertEquals(rows(source, "w,id,note,region", "w,id"), rows(target, "w,id,note,region", "w,id"));
    }

    @Test void readerRejectsNullWatermarksNullKeysForeignPinsAndUnsupportedDomainsBeforeReading() throws Exception {
        var operator = new HiveDatabaseOperator(pool); operator.configure(OPTIONS); operator.setFetchSize(2);
        create(source); insert(source, "(1,1,'a'),(2,NULL,'b')");
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "w", KEYS, null, null, null, LIMITS, false, pin)));
        sql("DROP TABLE " + name(source)); create(source); insert(source, "(1,1,'a'),(2,2,'b')");
        SourceGenerationPin foreign; try (var c = pool.getConnection()) { HiveJdbcWriteSupport.session(c, 90);
            foreign = HiveSourceGeneration.initialize(c, "default", 90); }
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "w", KEYS, null, null, null, LIMITS, false, foreign)));
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "note", KEYS, null, null, null, LIMITS, false, pin)));
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "w", KEYS, null, null, null, LIMITS, false, null)));
        // Control on the same fixture: the verified projection still reads.
        try (var cursor = operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "w", KEYS, null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
        }
        sql("DROP TABLE " + name(source)); create(source); insert(source, "(1,1,'a'),(NULL,2,'b')");
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(new CursorReadRequest(schema, source, FIELDS, "w", KEYS, null, null, null, LIMITS, false, pin)));
    }

    private void create(String table) throws Exception {
        sql("CREATE TABLE " + name(table) + " (w BIGINT,id BIGINT,note STRING) STORED AS ORC TBLPROPERTIES ('transactional'='true')");
    }
    private void createTs(String table) throws Exception {
        sql("CREATE TABLE " + name(table) + " (ts TIMESTAMP,id STRING,v BIGINT) STORED AS ORC TBLPROPERTIES ('transactional'='true')");
    }
    private void insert(String table, String values) throws Exception {
        sql("INSERT INTO TABLE " + name(table) + " (w,id,note) VALUES " + values);
    }
    private void insertTs(String table, String values) throws Exception {
        sql("INSERT INTO TABLE " + name(table) + " (ts,id,v) VALUES " + values);
    }
    private void transfer(String sourceTable, String targetTable, List<String> fields, List<String> keys, int fetch,
            CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, pool, pool, ProductTypeEnum.HIVE, ProductTypeEnum.HIVE, schema, sourceTable, schema, targetTable,
                fields, fields, keys, keys, fields.getFirst(), LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private MigrationRequest request(String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.HIVE); input.setUrl(url()); input.setUsername("hive"); input.setPassword("");
        input.setSourceSchema(schema); input.setSourceIncludes(source); input.setSourcePrimaryKeys(KEYS);
        input.setPoolSize(2); input.setFetchSize(2); input.setIncreaseColumnName("w"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^" + source + "$", targetTable))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.HIVE);
        output.setUrl(url()); output.setUsername("hive"); output.setPassword(""); output.setTargetSchema(schema); output.setTargetTable(targetTable);
        output.setPoolSize(4); output.setWriterEngineInsert(true); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setBatchLimits(LIMITS); output.setCursorRunBindings(Map.of(source, binding(this::save)));
        return request;
    }
    private static String url() { return System.getProperty("datahub.test.hive.url"); }
    private String name(String table) { return HiveJdbcWriteSupport.table(schema, table); }
    private void sql(String sql) throws Exception {
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            try (var statement = connection.createStatement()) { statement.setQueryTimeout(90); statement.execute(sql); }
        }
    }
    private List<List<String>> rows(String table, String projection, String order) throws Exception {
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(90);
                try (var rows = statement.executeQuery("SELECT " + projection + " FROM " + name(table) + " ORDER BY " + order)) {
                    var result = new ArrayList<List<String>>();
                    while (rows.next()) {
                        var row = new ArrayList<String>();
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) row.add(String.valueOf(rows.getObject(i)));
                        result.add(row);
                    }
                    return result;
                }
            }
        }
    }
    private long count(String table) throws Exception {
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + name(table))) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }
    /** Owned connections that fail on JDBC transaction control; an armed fault loses one INSERT acknowledgement after the write. */
    private DataSource ds(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = pool.getConnection(); // The fixture may use an isolated vendor driver loader.
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Unexpected Hive JDBC transaction control");
                    try {
                        Object result = method.invoke(real, args);
                        if (fault != null && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO")) {
                            var statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    Object value = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && fault.compareAndSet(true, false)) throw new SQLException("injected response loss after actual write");
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
