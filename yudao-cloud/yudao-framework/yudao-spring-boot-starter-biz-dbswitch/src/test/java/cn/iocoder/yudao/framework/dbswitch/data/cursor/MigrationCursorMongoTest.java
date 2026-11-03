package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoMigrationFrame;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB 7 (pinned driver) typed cursor: non-snapshot single-type watermark windows, deployed generation pin, _id upsert target. */
@Timeout(300)
@EnabledIfSystemProperty(named = "datahub.test.mongodb.url", matches = "jdbc:mongodb://127[.]0[.]0[.]1:[0-9]+/admin\\?authSource=admin")
@EnabledIfSystemProperty(named = "datahub.test.mongodb.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_MONGODB_TEST_PASSWORD", matches = ".+")
class MigrationCursorMongoTest {
    private static final ProductTypeEnum MONGO = ProductTypeEnum.MONGODB;
    private static final BatchLimits LIMITS = new BatchLimits(2, 262144, 131072, 65535, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(32L * 1024 * 1024, true, false, 15, 0, 300_000, 30_000, 0);
    private static final List<String> FIELD_FIELDS = List.of("_id", "stamp", "v");
    private static final List<String> KEYS = List.of("_id");
    private static ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private String schema;
    private SourceGenerationPin pin;
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeAll static void bundle() throws Exception {
        var jar = Path.of(System.getProperty("datahub.test.mongodb.driver")).toRealPath();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(jar)) { byte[] buffer = new byte[8192]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString()); config.setSha256(HexFormat.of().formatHex(digest.digest()));
        drivers = new ConfiguredJdbcDrivers(Map.of(MONGO, config));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers);
        schema = "mongo_cursor_" + UUID.randomUUID().toString().replace("-", "");
        command("{\"create\":\"source\"}"); command("{\"create\":\"target\"}");
        try (var connection = open()) { pin = MongoSourceGeneration.initialize(connection, schema, 15); }
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null && schema != null) command("{\"dropDatabase\":1}"); }
        finally { if (runtime != null) runtime.close(); }
        assertEquals(0, drivers.runtimes().connections().snapshot().active());
    }

    // ---- fixed window, equal watermarks, replay --------------------------------------------------------

    @Test void equalWatermarkGroupsMoveWithoutLossOrDuplicationAndReplayTheAppliedGroup() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"b\"}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"c\"}",
                "{\"_id\":{\"$numberLong\":\"4\"},\"stamp\":{\"$numberLong\":\"11\"},\"v\":\"d\"}",
                "{\"_id\":{\"$numberLong\":\"5\"},\"stamp\":{\"$numberLong\":\"12\"},\"v\":\"e\"}");
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 1, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertNull(saved.get().lastCommit()); assertEquals(MONGO, saved.get().sourceProduct()); assertEquals(MONGO, saved.get().targetProduct());
        assertEquals(5, count("target")); assertEquals(documents("source"), documents("target"));
        // The applied watermark group is replayed: a late row with the applied stamp and a smaller _id must not be skipped.
        insert("source", "{\"_id\":{\"$numberLong\":\"0\"},\"stamp\":{\"$numberLong\":\"12\"},\"v\":\"late equal stamp\"}",
                "{\"_id\":{\"$numberLong\":\"6\"},\"stamp\":{\"$numberLong\":\"13\"},\"v\":\"next\"}");
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 1, this::save);
        assertTrue(saved.get().complete()); assertEquals(7, count("target")); assertEquals(documents("source"), documents("target"));
        long sequence = saved.get().sequence();
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 1, this::save);
        assertEquals(documents("source"), documents("target")); assertTrue(saved.get().sequence() > sequence, "Replay is idempotent and advances");
    }

    @Test void frameCursorOnNumericIdMovesLosslesslyAndReplaysIdempotently() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"a\"}", "{\"_id\":{\"$numberLong\":\"2\"},\"v\":\"b\"}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"v\":\"c\"}", "{\"_id\":{\"$numberLong\":\"4\"},\"v\":\"d\"}");
        transfer("source", "target", MongoMigrationFrame.FIELDS, KEYS, "_id", 1, this::save);
        assertTrue(saved.get().complete()); assertEquals(4, count("target")); assertEquals(documents("source"), documents("target"));
        assertEquals("4", saved.get().applied().values().getFirst().value());
        insert("source", "{\"_id\":{\"$numberLong\":\"5\"},\"v\":\"e\"}");
        transfer("source", "target", MongoMigrationFrame.FIELDS, KEYS, "_id", 1, this::save);
        assertEquals(5, count("target")); assertEquals(documents("source"), documents("target"));
        transfer("source", "target", MongoMigrationFrame.FIELDS, KEYS, "_id", 1, this::save);
        assertEquals(5, count("target"));
        // A string _id makes the _id watermark domain unverifiable: the resume must refuse before any write.
        insert("source", "{\"_id\":\"late string id\",\"v\":\"x\"}");
        long before = count("target"); var checkpoint = saved.get();
        assertThrows(IllegalArgumentException.class, () -> transfer("source", "target", MongoMigrationFrame.FIELDS, KEYS, "_id", 1, this::save));
        assertEquals(before, count("target")); assertSame(checkpoint, saved.get());
    }

    // ---- window freezing and fail-closed concurrency ----------------------------------------------------

    @Test void fixedUpperBoundIgnoresRowsArrivingAboveItUntilTheNextRun() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"11\"},\"v\":\"b\"}");
        var reader = runtime.reader(ds(), MONGO); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "source", FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals("11", cursor.upperBound().values().getFirst().value());
            int read = 0;
            while (cursor.next()) {
                read++;
                if (read == 1) insert("source", "{\"_id\":{\"$numberLong\":\"9\"},\"stamp\":{\"$numberLong\":\"99\"},\"v\":\"above the frozen upper bound\"}");
            }
            assertEquals(2, read);
        }
        assertEquals(3, count("source"));
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "source", FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals("99", cursor.upperBound().values().getFirst().value());
        }
    }

    @Test void inWindowConcurrentInsertFailsTheRunClosedAtCompletion() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"12\"},\"v\":\"b\"}");
        var reader = runtime.reader(ds(), MONGO); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "source", FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
            // The insert sorts before the already advanced stream position: it cannot be delivered, and the
            // completion count recheck must fail the run instead of silently losing the row.
            insert("source", "{\"_id\":{\"$numberLong\":\"0\"},\"stamp\":{\"$numberLong\":\"5\"},\"v\":\"concurrent in-window\"}");
            assertThrows(SQLException.class, () -> { while (cursor.next()) { } });
        }
        assertEquals(3, count("source"));
    }

    @Test void completedEmptyWindowRemainsFrozenUntilAnExplicitNextRun() throws Exception {
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2,
                next -> calls.incrementAndGet() == 1 && save(next)));
        assertNull(saved.get().fixedUpper()); assertNull(saved.get().applied()); assertFalse(saved.get().complete());
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}");
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(0, count("target")); assertTrue(saved.get().complete());
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(1, count("target")); assertEquals(documents("source"), documents("target"));
    }

    // ---- recovery contract ------------------------------------------------------------------------------

    @Test void commitBeforeCheckpointCrashReplaysTheFrozenWindowThenAdvances() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"b\"}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"stamp\":{\"$numberLong\":\"11\"},\"v\":\"c\"}");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2,
                next -> calls.incrementAndGet() != 3 && save(next)));
        // The freeze checkpoint, batch one and batch two were persisted or attempted; batch two was committed but
        // not recorded: only the first applied checkpoint survives.
        assertEquals(3, count("target")); assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        insert("source", "{\"_id\":{\"$numberLong\":\"0\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"late same stamp\"}",
                "{\"_id\":{\"$numberLong\":\"4\"},\"stamp\":{\"$numberLong\":\"12\"},\"v\":\"next window\"}");
        // The resumed run keeps the crashed run's frozen upper bound: only the applied group and the window below it replay.
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(4, count("target")); assertTrue(saved.get().complete());
        // A fresh completed run re-captures the upper bound and moves the row that waited above the frozen window.
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(5, count("target")); assertEquals(documents("source"), documents("target"));
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(5, count("target"));
    }

    @Test void lostCommandAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"11\"},\"v\":\"b\"}");
        var fired = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, ds(), losingAck(fired), MONGO, MONGO,
                schema, "source", schema, "target", FIELD_FIELDS, FIELD_FIELDS, KEYS, KEYS, "stamp", LIMITS, 2,
                binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertTrue(fired.get());
        assertEquals(2, count("target"), "The injected loss happened after a real acknowledged write");
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence()); assertFalse(saved.get().complete());
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(2, count("target")); assertEquals(documents("source"), documents("target"));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "SOURCE_RECREATE", "WATERMARK_TYPE_CHANGED", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationOrCollectionBeforeWriting(String change) throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}");
        transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save); assertTrue(saved.get().complete());
        switch (change) {
            case "PIN_ROTATE" -> { try (var connection = open()) { pin = MongoSourceGeneration.rotate(connection, pin, UUID.randomUUID().toString(), 15); } }
            case "SOURCE_RECREATE" -> { command("{\"drop\":\"source\"}"); command("{\"create\":\"source\"}"); }
            case "WATERMARK_TYPE_CHANGED" -> insert("source", "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":\"now text\",\"v\":\"x\"}");
            case "TARGET_RECREATE" -> { command("{\"drop\":\"target\"}"); command("{\"create\":\"target\"}"); }
            default -> { }
        }
        insert("source", "{\"_id\":{\"$numberLong\":\"3\"},\"stamp\":{\"$numberLong\":\"11\"},\"v\":\"new\"}");
        long before = count("target"); var checkpoint = saved.get();
        if (change.equals("NONE")) {
            transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save);
            assertEquals(before + 1, count("target")); assertTrue(saved.get().complete()); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("source", "target", FIELD_FIELDS, KEYS, "stamp", 2, this::save));
        assertEquals(before, count("target")); assertSame(checkpoint, saved.get());
    }

    // ---- watermark domain precheck ----------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = {"text", "binary", "array", "mixed", "missing", "double", "null"})
    void watermarkDomainPrecheckRejectsUnreplayableShapesBeforeReading(String shape) throws Exception {
        command("{\"create\":\"" + shape + "\"}");
        switch (shape) {
            case "text" -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":\"ten\",\"v\":\"x\"}");
            case "binary" -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$binary\":{\"base64\":\"AA==\",\"subType\":\"00\"}},\"v\":\"x\"}");
            case "array" -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":[1,2],\"v\":\"x\"}");
            case "mixed" -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"x\"}",
                    "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberInt\":\"5\"},\"v\":\"y\"}");
            case "missing" -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"note\":\"no stamp\"}",
                    "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"x\"}");
            case "double" -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberDouble\":\"1.5\"},\"v\":\"x\"}");
            default -> insert(shape, "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":null,\"v\":\"x\"}",
                    "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"x\"}");
        }
        var reader = runtime.reader(ds(), MONGO); reader.setFetchSize(2);
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest(schema, shape,
                FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin)), shape);
        // Control: an exact int64 family opens and pages.
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"x\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"stamp\":{\"$numberLong\":\"11\"},\"v\":\"y\"}");
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "source", FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals("11", cursor.upperBound().values().getFirst().value());
            int read = 0; while (cursor.next()) read++; assertEquals(2, read);
        }
    }

    @Test void pinMustNameTheActualDatabaseBeforeTheFirstRead() throws Exception {
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"stamp\":{\"$numberLong\":\"10\"},\"v\":\"a\"}");
        var foreign = new SourceGenerationPin(schema, "11111111-1111-1111-1111-111111111111", UUID.randomUUID().toString());
        var reader = runtime.reader(ds(), MONGO); reader.setFetchSize(2);
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest(schema, "source",
                FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, foreign)));
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest(schema, "source",
                FIELD_FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, null)));
    }

    // ---- public service and registry --------------------------------------------------------------------

    @Test void publicServiceRunsAFrameCursorAndReplaysTheAppliedIdGroup() throws Exception {
        command("{\"create\":\"t\"}");
        insert("source", "{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"a\"}", "{\"_id\":{\"$numberLong\":\"2\"},\"v\":\"b\"}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"v\":\"c\"}");
        var request = request();
        var first = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(first.getIfAllSuccess().get(), () -> first.getTableResultList().toString());
        assertEquals(3, first.getTotalRowCount().get(), () -> first.getTableResultList().toString()); assertTrue(saved.get().complete());
        assertEquals(MONGO, saved.get().sourceProduct()); assertEquals(MONGO, saved.get().targetProduct()); assertEquals(2, saved.get().version());
        assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement()); assertNull(saved.get().lastCommit());
        insert("source", "{\"_id\":{\"$numberLong\":\"4\"},\"v\":\"d\"}");
        request.getTarget().setCursorRunBindings(Map.of("source", binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(4, count("t")); assertEquals(documents("source"), documents("t"));
    }

    @Test void registryDeclaresCursorAndUpsertCapabilitiesWithExactScopes() {
        var registry = DbSwitchProductRegistry.discover();
        var mongo = registry.product(MONGO);
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "MONGODB_PINNED_FIXED_UPPER_SINGLE_TYPE_WATERMARK_GROUPS_WITHOUT_SNAPSHOT"),
                mongo.capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "MONGODB_SINGLE_ID_TYPED_UPSERT_MAJORITY_JOURNALED"),
                mongo.capabilities().get(DatabaseOperation.WRITE_UPSERT));
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb.MongoUpsertWriter.class, registry.upsertWriter(ds(), MONGO));
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private void transfer(String source, String target, List<String> fields, List<String> keys, String watermark, int fetch,
                          CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(), ds(), MONGO, MONGO, schema, source, schema, target, fields, fields, keys, keys,
                watermark, LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) {
        return new CursorRunBinding("e".repeat(64), saved.get(), store, pin);
    }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private MigrationRequest request() {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(MONGO); input.setDriverClassName(MONGO.getDriveClassName());
        input.setUrl(url()); input.setUsername("q11root"); input.setPassword(System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
        input.setPoolSize(1); input.setFetchSize(2); input.setSourceSchema(schema); input.setSourceIncludes("source");
        input.setIncreaseColumnName("_id"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^source$", "t"))); request.getSource().add(input);
        var target = request.getTarget(); target.setTargetProductType(MONGO); target.setDriverClassName(MONGO.getDriveClassName());
        target.setUrl(url()); target.setUsername("q11root"); target.setPassword(System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
        target.setPoolSize(1); target.setTargetSchema(schema); target.setTargetTable("t");
        target.setChangeDataSync(true); target.setChangeDataSyncType("INCREASE_COLUMN");
        target.setCursorRunBindings(Map.of("source", binding(this::save)));
        return request;
    }
    private void insert(String collection, String... documents) throws Exception {
        var writer = runtime.writer(ds(), MONGO, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite(schema, collection, MongoMigrationFrame.FIELDS);
        List<Object[]> frames = new ArrayList<>();
        try (var connection = ds().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            for (String document : documents) frames.add(MongoMigrationFrame.detach(session, session.encode(document)));
        }
        assertEquals(documents.length, writer.write(MongoMigrationFrame.FIELDS, frames));
    }
    private List<String> documents(String collection) throws Exception {
        var reader = runtime.reader(ds(), MONGO); reader.setFetchSize(31);
        var docs = new ArrayList<String>();
        try (var result = reader.queryTableData(schema, collection, MongoMigrationFrame.FIELDS, List.of("_id"))) {
            var rows = result.getResultset();
            try (var connection = ds().getConnection()) {
                var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
                while (rows.next()) docs.add(normalize(session.canonicalJson(MongoMigrationFrame.document(session,
                        new Object[]{rows.getObject(1), rows.getObject(2)}))));
            }
        }
        Collections.sort(docs); return docs;
    }
    /** Sorted-key JSON: BSON field order is insertion order and must not leak into document equality. */
    private static String normalize(String canonical) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(canonical));
    }
    private long count(String collection) throws Exception { return MongoReplies.integer(command("{\"count\":\"" + collection + "\"}").path("n")); }
    private JsonNode command(String command) throws Exception {
        try (var connection = ds().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            return MongoReplies.success(session, session.command(schema, session.encode(command)));
        }
    }
    private DataSource ds() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return open(); }
            @Override public Connection getConnection(String user, String password) throws SQLException { return open(); }
        };
    }
    private Connection open() throws SQLException {
        return runtime.openConnection(MONGO, null, url(), "q11root", System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
    }
    private static String url() { return System.getProperty("datahub.test.mongodb.url"); }

    /** The second bounded native command on one borrowed connection is the batch write: lose its reply after it ran. */
    private DataSource losingAck(java.util.concurrent.atomic.AtomicBoolean fired) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = open();
                var access = actual.unwrap(cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.ConnectionAccess.class);
                var counter = new AtomicInteger();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("unwrap") && arguments[0] == cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.ConnectionAccess.class)
                        return new cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.ConnectionAccess() {
                            @Override public Object unwrapNative(String typeName) throws SQLException { return access.unwrapNative(typeName); }
                            @Override public Object invokeNative(java.util.concurrent.Callable<?> operation, long timeoutMillis) throws SQLException {
                                Object result = access.invokeNative(operation, timeoutMillis);
                                if (counter.incrementAndGet() == 2) { fired.set(true); throw new SQLException("fixture lost acknowledgement after the native write"); }
                                return result;
                            }
                        };
                    try { return method.invoke(actual, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
}
