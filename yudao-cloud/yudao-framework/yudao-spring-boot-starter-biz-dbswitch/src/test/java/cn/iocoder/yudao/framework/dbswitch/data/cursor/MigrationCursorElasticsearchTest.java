package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.ElasticsearchSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch.ElasticsearchUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real Elasticsearch 8.15.3 (legacy jest bridge) typed cursor: non-snapshot single-type watermark windows over search_after, deployed generation pin, _id index upsert target. */
@Timeout(300)
@EnabledIfSystemProperty(named = "datahub.test.elasticsearch.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named = "datahub.test.elasticsearch.driver", matches = ".+")
class MigrationCursorElasticsearchTest {
    private static final ProductTypeEnum ES = ProductTypeEnum.ELASTICSEARCH;
    private static final BatchLimits LIMITS = new BatchLimits(2, 262144, 131072, 65535, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(32L * 1024 * 1024, true, false, 15, 0, 300_000, 30_000, 0);
    private static final List<String> FIELDS = List.of("_id", "stamp", "v");
    private static final List<String> KEYS = List.of("_id");
    private static final String MAPPING = "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"long\"},\"v\":{\"type\":\"keyword\"}}}}";
    private static final String KW_MAPPING = "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"keyword\"},\"v\":{\"type\":\"keyword\"}}}}";
    private static final String NANOS_MAPPING = "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"date_nanos\"},\"v\":{\"type\":\"keyword\"}}}}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfiguredJdbcDrivers drivers;
    // Legacy Jest bridge requires credentials even when the isolated server disables security; fixture-only values.
    private static String fixtureUser;
    private static String fixturePassword;
    private DbSwitchRuntime runtime;
    private String sourceIndex;
    private String targetIndex;
    private final List<String> scratch = new ArrayList<>();
    private SourceGenerationPin pin;
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeAll static void verifiedBundle() throws Exception {
        fixtureUser = UUID.randomUUID().toString(); fixturePassword = UUID.randomUUID().toString();
        String supplied = System.getProperty("datahub.test.elasticsearch.driver");
        assertNotNull(supplied, "Enabled Elasticsearch fixture requires its explicit JDBC bridge artifact");
        Path jar = Path.of(supplied).toAbsolutePath(); assertTrue(Files.isRegularFile(jar));
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(jar)) { byte[] bytes = new byte[8192]; int count; while ((count = input.read(bytes)) != -1) hash.update(bytes, 0, count); }
        var spec = new DbSwitchProperties.Driver(); spec.setPath(jar.toString());
        spec.setSha256(HexFormat.of().formatHex(hash.digest())); spec.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.ELASTICSEARCH, spec));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers);
        String id = UUID.randomUUID().toString().replace("-", "");
        sourceIndex = "es_cur_s_" + id; targetIndex = "es_cur_t_" + id;
        request("PUT", "/" + sourceIndex, MAPPING); request("PUT", "/" + targetIndex, MAPPING);
        try (var connection = open()) { pin = ElasticsearchSourceGeneration.initialize(connection, sourceIndex, 15); }
    }
    @AfterEach void cleanup() throws Exception {
        try {
            for (String index : scratch) deleteQuiet(index);
            if (sourceIndex != null) deleteQuiet(sourceIndex);
            if (targetIndex != null) deleteQuiet(targetIndex);
        } finally { if (runtime != null) runtime.close(); }
        assertEquals(0, drivers.runtimes().connections().snapshot().active());
    }

    // ---- fixed window, equal watermarks, replay --------------------------------------------------------

    @Test void equalWatermarkGroupsMoveWithoutLossOrDuplicationAndReplayTheAppliedGroup() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 10L, "b", "3", 10L, "c", "4", 11L, "d", "5", 12L, "e");
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 1, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertNull(saved.get().lastCommit()); assertEquals(ES, saved.get().sourceProduct()); assertEquals(ES, saved.get().targetProduct());
        assertEquals(5, count(targetIndex)); assertEquals(documents(sourceIndex), documents(targetIndex));
        // The applied watermark group is replayed: a late row with the applied stamp and a lower _id must not be skipped.
        insert(sourceIndex, "0", 12L, "late equal stamp", "6", 13L, "next");
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 1, this::save);
        assertTrue(saved.get().complete()); assertEquals(7, count(targetIndex)); assertEquals(documents(sourceIndex), documents(targetIndex));
        long sequence = saved.get().sequence();
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 1, this::save);
        assertEquals(documents(sourceIndex), documents(targetIndex)); assertTrue(saved.get().sequence() > sequence, "Replay is idempotent and advances");
    }

    @Test void keywordWatermarkPagesInUtf8ByteOrderAndDateNanosStaysExact() throws Exception {
        scratch.add(kwIndex()); scratch.add(kwTarget());
        var kwSource = scratch.get(scratch.size() - 2); var kwTarget = scratch.get(scratch.size() - 1);
        insert(kwSource, "1", "A", "x", "2", "a", "x", "3", "ä", "x", "4", "中文", "x");
        try (var connection = open()) {
            SourceGenerationPin kwPin = ElasticsearchSourceGeneration.initialize(connection, kwSource, 15);
            var kwSaved = new AtomicReference<CursorCheckpoint>();
            CursorTransfer.execute(runtime, ds(), ds(), ES, ES, "es", kwSource, "es", kwTarget, FIELDS, FIELDS, KEYS, KEYS,
                    "stamp", LIMITS, 1, new CursorRunBinding("e".repeat(64), null, value -> { kwSaved.set(value); return true; }, kwPin),
                    new DbSwitchTableResult(), () -> { });
            assertEquals(List.of("中文"), appliedValues(kwSaved.get()));
        }
        assertEquals(List.of("A", "a", "ä", "中文"), watermarks(kwTarget));
        scratch.add(nanosIndex()); scratch.add(nanosTarget());
        var nanosSource = scratch.get(scratch.size() - 2); var nanosTarget = scratch.get(scratch.size() - 1);
        insert(nanosSource, "1", "2024-01-01T00:00:00.123456789Z", "x", "2", "2024-01-01T00:00:00.123456788Z", "x");
        try (var connection = open()) {
            SourceGenerationPin nanosPin = ElasticsearchSourceGeneration.initialize(connection, nanosSource, 15);
            var nanosSaved = new AtomicReference<CursorCheckpoint>();
            CursorTransfer.execute(runtime, ds(), ds(), ES, ES, "es", nanosSource, "es", nanosTarget, FIELDS, FIELDS, KEYS, KEYS,
                    "stamp", LIMITS, 1, new CursorRunBinding("e".repeat(64), null, value -> { nanosSaved.set(value); return true; }, nanosPin),
                    new DbSwitchTableResult(), () -> { });
            assertEquals(List.of("2024-01-01T00:00:00.123456789Z"), appliedValues(nanosSaved.get()));
        }
        assertEquals(List.of("2024-01-01T00:00:00.123456788Z", "2024-01-01T00:00:00.123456789Z"), watermarks(nanosTarget));
    }

    // ---- window freezing and fail-closed concurrency ----------------------------------------------------

    @Test void fixedUpperBoundIgnoresRowsArrivingAboveItUntilTheNextRun() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 11L, "b");
        var reader = runtime.reader(ds(), ES); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest("es", sourceIndex, FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals("11", cursor.upperBound().values().getFirst().value());
            int read = 0;
            while (cursor.next()) {
                read++;
                if (read == 1) insert(sourceIndex, "9", 99L, "above the frozen upper bound");
            }
            assertEquals(2, read);
        }
        assertEquals(3, count(sourceIndex));
        try (var cursor = reader.openCursor(new CursorReadRequest("es", sourceIndex, FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals("99", cursor.upperBound().values().getFirst().value());
        }
    }

    @Test void inWindowConcurrentInsertFailsTheRunClosedAtCompletion() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 12L, "b");
        var reader = runtime.reader(ds(), ES); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest("es", sourceIndex, FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
            // The insert sorts before the already advanced stream position: it cannot be delivered, and the
            // completion count recheck must fail the run instead of silently losing the row.
            insert(sourceIndex, "0", 5L, "concurrent in-window");
            assertThrows(SQLException.class, () -> { while (cursor.next()) { } });
        }
        assertEquals(3, count(sourceIndex));
    }

    @Test void inWindowConcurrentDeleteFailsTheRunClosedAtCompletion() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 11L, "b");
        var reader = runtime.reader(ds(), ES); reader.setFetchSize(2);
        try (var cursor = reader.openCursor(new CursorReadRequest("es", sourceIndex, FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
            request("DELETE", "/" + sourceIndex + "/_doc/2?refresh=true", null);
            assertThrows(SQLException.class, () -> { while (cursor.next()) { } });
        }
    }

    @Test void completedEmptyWindowRemainsFrozenUntilAnExplicitNextRun() throws Exception {
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2,
                next -> calls.incrementAndGet() == 1 && save(next)));
        assertNull(saved.get().fixedUpper()); assertNull(saved.get().applied()); assertFalse(saved.get().complete());
        insert(sourceIndex, "1", 10L, "a");
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(0, count(targetIndex)); assertTrue(saved.get().complete());
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(1, count(targetIndex)); assertEquals(documents(sourceIndex), documents(targetIndex));
    }

    // ---- recovery contract ------------------------------------------------------------------------------

    @Test void commitBeforeCheckpointCrashReplaysTheFrozenWindowThenAdvances() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 10L, "b", "3", 11L, "c");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2,
                next -> calls.incrementAndGet() != 3 && save(next)));
        // The freeze checkpoint, batch one and batch two were persisted or attempted; batch two was committed but
        // not recorded: only the first applied checkpoint survives.
        assertEquals(3, count(targetIndex)); assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        insert(sourceIndex, "0", 10L, "late same stamp", "4", 12L, "next window");
        // The resumed run keeps the crashed run's frozen upper bound: only the applied group and the window below it replay.
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(4, count(targetIndex)); assertTrue(saved.get().complete());
        // A fresh completed run re-captures the upper bound and moves the row that waited above the frozen window.
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(5, count(targetIndex)); assertEquals(documents(sourceIndex), documents(targetIndex));
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
        assertEquals(5, count(targetIndex));
    }

    @Test void lostBulkAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 11L, "b");
        var fired = new AtomicBoolean();
        try (var peer = new LostAckPeer(targetIndex, fired)) {
            var fake = new AtomicReference<CursorCheckpoint>();
            assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, ds(), peer.source(), ES, ES,
                    "es", sourceIndex, "es", targetIndex, FIELDS, FIELDS, KEYS, KEYS, "stamp", LIMITS, 2,
                    new CursorRunBinding("e".repeat(64), null, value -> { fake.set(value); return true; }, pin),
                    new DbSwitchTableResult(), () -> { }));
            assertTrue(fired.get()); assertEquals(1, peer.bulkRequests());
            assertNull(fake.get().applied()); assertEquals(0, fake.get().sequence()); assertFalse(fake.get().complete());
        }
        assertEquals(0, count(targetIndex), "The unconfirmed target may or may not hold rows; the real target received none");
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(2, count(targetIndex)); assertEquals(documents(sourceIndex), documents(targetIndex));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "SOURCE_RECREATE", "WATERMARK_FIELD_CHANGED", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationOrIndexBeforeWriting(String change) throws Exception {
        insert(sourceIndex, "1", 10L, "a");
        transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save); assertTrue(saved.get().complete());
        switch (change) {
            case "PIN_ROTATE" -> { try (var connection = open()) { pin = ElasticsearchSourceGeneration.rotate(connection, pin, UUID.randomUUID().toString(), 15); } }
            case "SOURCE_RECREATE" -> { request("DELETE", "/" + sourceIndex, null); request("PUT", "/" + sourceIndex, MAPPING); }
            case "WATERMARK_FIELD_CHANGED" -> {
                String update = "{\"properties\":{\"v\":{\"type\":\"keyword\",\"ignore_above\":4},\"extra\":{\"type\":\"long\"}}}";
                request("PUT", "/" + sourceIndex + "/_mapping", update);
            }
            case "TARGET_RECREATE" -> { request("DELETE", "/" + targetIndex, null); request("PUT", "/" + targetIndex, MAPPING); }
            default -> { }
        }
        insert(sourceIndex, "3", 11L, "new");
        long before = count(targetIndex); var checkpoint = saved.get();
        if (change.equals("NONE")) {
            transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save);
            assertEquals(before + 1, count(targetIndex)); assertTrue(saved.get().complete()); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer(sourceIndex, targetIndex, FIELDS, KEYS, "stamp", 2, this::save));
        assertEquals(before, count(targetIndex)); assertSame(checkpoint, saved.get());
    }

    // ---- watermark domain precheck ----------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = {"text", "float", "keyword_ignore_above", "missing", "id_watermark", "keyword_normalizer"})
    void watermarkDomainPrecheckRejectsUnreplayableShapesBeforeReading(String shape) throws Exception {
        String index = "es_cur_domain_" + UUID.randomUUID().toString().replace("-", ""); scratch.add(index);
        switch (shape) {
            case "text" -> request("PUT", "/" + index, "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"text\"},\"v\":{\"type\":\"keyword\"}}}}");
            case "float" -> request("PUT", "/" + index, "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"float\"},\"v\":{\"type\":\"keyword\"}}}}");
            case "keyword_ignore_above" -> request("PUT", "/" + index, "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"keyword\",\"ignore_above\":2},\"v\":{\"type\":\"keyword\"}}}}");
            case "keyword_normalizer" -> request("PUT", "/" + index, "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"keyword\",\"normalizer\":\"lowercase\"},\"v\":{\"type\":\"keyword\"}}},\"settings\":{\"analysis\":{\"normalizer\":{\"lowercase\":{\"type\":\"custom\",\"filter\":[\"lowercase\"]}}}}}");
            case "missing" -> request("PUT", "/" + index, "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"other\":{\"type\":\"long\"},\"v\":{\"type\":\"keyword\"}}}}");
            default -> { } // id_watermark uses the ordinary long mapping
        }
        try (var connection = open()) {
            SourceGenerationPin domainPin = ElasticsearchSourceGeneration.initialize(connection, index, 15);
            var reader = runtime.reader(ds(), ES); reader.setFetchSize(2);
            String watermark = shape.equals("id_watermark") ? "_id" : "stamp";
            List<String> fields = shape.equals("missing") ? List.of("_id", "stamp", "v") : FIELDS;
            assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest("es", index,
                    fields, watermark, KEYS, null, null, null, LIMITS, false, domainPin)), shape);
        }
        // Control: an exact long watermark opens and pages.
        insert(sourceIndex, "1", 10L, "x", "2", 11L, "y");
        try (var cursor = runtime.reader(ds(), ES).openCursor(new CursorReadRequest("es", sourceIndex, FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals("11", cursor.upperBound().values().getFirst().value());
            int read = 0; while (cursor.next()) read++; assertEquals(2, read);
        }
    }

    @Test void pinMustNameTheActualIndexBeforeTheFirstRead() throws Exception {
        insert(sourceIndex, "1", 10L, "a");
        var foreign = new SourceGenerationPin(sourceIndex, "11111111-1111-1111-1111-111111111111", UUID.randomUUID().toString());
        var reader = runtime.reader(ds(), ES); reader.setFetchSize(2);
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest("es", sourceIndex,
                FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, foreign)));
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest("es", sourceIndex,
                FIELDS, "stamp", KEYS, null, null, null, LIMITS, false, null)));
    }

    // ---- public service and registry --------------------------------------------------------------------

    @Test void publicServiceRunsACursorAndReplaysTheAppliedIdGroup() throws Exception {
        insert(sourceIndex, "1", 10L, "a", "2", 10L, "b", "3", 11L, "c");
        var request = request();
        var first = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(first.getIfAllSuccess().get(), () -> first.getTableResultList().toString());
        assertEquals(3, first.getTotalRowCount().get(), () -> first.getTableResultList().toString()); assertTrue(saved.get().complete());
        assertEquals(ES, saved.get().sourceProduct()); assertEquals(ES, saved.get().targetProduct()); assertEquals(2, saved.get().version());
        assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement()); assertNull(saved.get().lastCommit());
        insert(sourceIndex, "4", 12L, "d");
        request.getTarget().setCursorRunBindings(Map.of(sourceIndex, binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(4, count(targetIndex)); assertEquals(documents(sourceIndex), documents(targetIndex));
    }

    @Test void registryDeclaresCursorAndUpsertCapabilitiesWithExactScopes() {
        var registry = DbSwitchProductRegistry.discover();
        var product = registry.product(ES);
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "ELASTICSEARCH_PINNED_FIXED_UPPER_SINGLE_TYPE_WATERMARK_GROUPS_WITHOUT_SNAPSHOT"),
                product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "ELASTICSEARCH_SINGLE_ID_INDEX_UPSERT_BULK_ACKNOWLEDGED"),
                product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
        assertInstanceOf(ElasticsearchUpsertWriter.class, registry.upsertWriter(ds(), ES));
    }

    @Test void upsertOverwritesByIdAndRefusesNonIdKeysOrDuplicateIdentities() throws Exception {
        var writer = runtime.upsertWriter(ds(), ES);
        writer.prepareUpsert("es", targetIndex, FIELDS, KEYS);
        assertEquals(2, writer.write(FIELDS, List.<Object[]>of(new Object[]{"a", 1L, "one"}, new Object[]{"b", 2L, "two"})));
        assertEquals(1, writer.write(FIELDS, List.<Object[]>of(new Object[]{"a", 3L, "three"})));
        assertEquals(2, count(targetIndex));
        assertEquals("three", request("GET", "/" + targetIndex + "/_doc/a", null).path("_source").path("v").textValue());
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert("es", targetIndex, FIELDS, List.of("v")));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert("es", targetIndex, List.of("v"), KEYS));
        writer.prepareUpsert("es", targetIndex, FIELDS, KEYS);
        assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS, List.of(
                new Object[]{"same", 4L, "first"}, new Object[]{"same", 5L, "second"})));
        assertEquals(2, count(targetIndex));
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private void transfer(String source, String target, List<String> fields, List<String> keys, String watermark, int fetch,
                          CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(), ds(), ES, ES, "es", source, "es", target, fields, fields, keys, keys,
                watermark, LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) {
        return new CursorRunBinding("e".repeat(64), saved.get(), store, pin);
    }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }
    private MigrationRequest request() {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ES); input.setDriverClassName(ES.getDriveClassName());
        input.setUrl(url()); input.setUsername(fixtureUser); input.setPassword(fixturePassword);
        input.setPoolSize(1); input.setFetchSize(2); input.setSourceSchema("es"); input.setSourceIncludes(sourceIndex);
        input.setIncreaseColumnName("stamp"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^" + sourceIndex + "$", targetIndex))); request.getSource().add(input);
        var target = request.getTarget(); target.setTargetProductType(ES); target.setDriverClassName(ES.getDriveClassName());
        target.setUrl(url()); target.setUsername(fixtureUser); target.setPassword(fixturePassword);
        target.setPoolSize(1); target.setTargetSchema("es"); target.setTargetTable(targetIndex);
        target.setChangeDataSync(true); target.setChangeDataSyncType("INCREASE_COLUMN");
        target.setCursorRunBindings(Map.of(sourceIndex, binding(this::save)));
        return request;
    }
    private void insert(String index, Object... values) throws Exception {
        var writer = runtime.writer(ds(), ES, WriteMode.INSERT);
        writer.prepareWrite("es", index, FIELDS);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < values.length; i += 3) rows.add(new Object[]{values[i], values[i + 1], values[i + 2]});
        assertEquals(rows.size(), writer.write(FIELDS, rows));
    }
    private List<String> documents(String index) throws Exception {
        try (var connection = ds().getConnection()) {
            var session = ElasticsearchNativeSession.open(connection, 32L * 1024 * 1024, 15);
            var body = Map.of("size", 100, "sort", List.of(Map.of("stamp", "asc"), "_doc"), "query", Map.of("match_all", Map.of()));
            var result = session.request("POST", "/" + index + "/_search", ElasticsearchNativeSession.json(body)).requireSuccess();
            var docs = new ArrayList<String>();
            for (var hit : result.path("hits").path("hits")) {
                var normalized = new TreeMap<String, Object>();
                normalized.put("id", hit.path("_id").textValue());
                normalized.put("stamp", JSON.readTree(hit.path("_source").path("stamp").toString()));
                normalized.put("v", JSON.readTree(hit.path("_source").path("v").toString()));
                docs.add(JSON.writeValueAsString(normalized));
            }
            Collections.sort(docs); return docs;
        }
    }
    private List<String> watermarks(String index) throws Exception {
        try (var connection = ds().getConnection()) {
            var session = ElasticsearchNativeSession.open(connection, 32L * 1024 * 1024, 15);
            var body = Map.of("size", 100, "sort", List.of(Map.of("stamp", "asc"), "_doc"), "_source", List.of("stamp"),
                    "query", Map.of("match_all", Map.of()));
            var result = session.request("POST", "/" + index + "/_search", ElasticsearchNativeSession.json(body)).requireSuccess();
            var values = new ArrayList<String>();
            for (var hit : result.path("hits").path("hits")) values.add(hit.path("_source").path("stamp").textValue());
            return values;
        }
    }
    private List<String> appliedValues(CursorCheckpoint checkpoint) {
        var values = new ArrayList<String>();
        for (var value : checkpoint.applied().values()) values.add(value.value());
        return values;
    }
    private long count(String index) throws Exception {
        return request("POST", "/" + index + "/_count", "{\"query\":{\"match_all\":{}}}").path("count").longValue();
    }
    private void deleteQuiet(String index) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.elasticsearch.http") + "/" + index))
                    .timeout(Duration.ofSeconds(20)).DELETE().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300 || response.statusCode() == 404,
                    "Fixture cleanup failed: HTTP " + response.statusCode());
        }
    }
    private JsonNode request(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.elasticsearch.http") + path))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "Fixture operation failed: HTTP " + response.statusCode() + " " + response.body());
            return JSON.readTree(response.body());
        }
    }
    private String kwIndex() throws Exception { String index = "es_cur_kw_" + UUID.randomUUID().toString().replace("-", ""); request("PUT", "/" + index, KW_MAPPING); return index; }
    private String kwTarget() throws Exception { String index = "es_cur_kwt_" + UUID.randomUUID().toString().replace("-", ""); request("PUT", "/" + index, KW_MAPPING); return index; }
    private String nanosIndex() throws Exception { String index = "es_cur_ns_" + UUID.randomUUID().toString().replace("-", ""); request("PUT", "/" + index, NANOS_MAPPING); return index; }
    private String nanosTarget() throws Exception { String index = "es_cur_nst_" + UUID.randomUUID().toString().replace("-", ""); request("PUT", "/" + index, NANOS_MAPPING); return index; }
    private DataSource ds() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return open(); }
            @Override public Connection getConnection(String user, String password) throws SQLException { return open(); }
        };
    }
    private Connection open() throws SQLException {
        return runtime.openConnection(ES, null, url(), fixtureUser, fixturePassword);
    }
    private static String url() { URI endpoint = URI.create(System.getProperty("datahub.test.elasticsearch.http")); return "jdbc:jest://" + endpoint.getHost() + ":" + endpoint.getPort(); }

    /** A controlled HTTP peer: the target index mapping is served, and the bulk write may be accepted before its receipt is lost. */
    private static final class LostAckPeer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        private final AtomicInteger bulkRequests = new AtomicInteger();
        private final AtomicBoolean fired;
        private final String index;
        private final DbSwitchRuntime peerRuntime;
        LostAckPeer(String index, AtomicBoolean fired) throws IOException {
            this.index = index; this.fired = fired; this.fired.set(false);
            this.peerRuntime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    if (path.startsWith("/_bulk")) {
                        bulkRequests.incrementAndGet(); fired.set(true);
                        // A peer may have accepted a write before returning a malformed/truncated receipt.
                        byte[] truncated = "{\"truncated\"".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, truncated.length);
                        exchange.getResponseBody().write(truncated);
                        return;
                    }
                    String name = path.substring(1);
                    int end = name.length();
                    for (char separator : new char[]{'/', '?'}) { int at = name.indexOf(separator); if (at >= 0 && at < end) end = at; }
                    name = name.substring(0, end);
                    if (!name.equals(index)) { exchange.sendResponseHeaders(404, -1); return; }
                    String body = "{\"" + index + "\":{\"settings\":{\"index.uuid\":\"5BO3P_4oRw2UBeD1OqJj4g\",\"index.number_of_shards\":\"1\",\"index.number_of_replicas\":\"0\"},\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"stamp\":{\"type\":\"long\"},\"v\":{\"type\":\"keyword\"}}}}}";
                    if (path.equals("/" + index + "/_count")) body = "{\"count\":0,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}";
                    if (path.equals("/" + index + "/_refresh")) body = "{\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException expectedAbort) { /* The boundary test deliberately aborts the response. */ }
            });
            server.setExecutor(executor); server.start();
        }
        int bulkRequests() { return bulkRequests.get(); }
        AbstractDataSource source() {
            return new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    return peerRuntime.openConnection(ES, null, "jdbc:jest://127.0.0.1:" + server.getAddress().getPort(), fixtureUser, fixturePassword);
                }
                @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
            };
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); peerRuntime.close(); }
    }
}
