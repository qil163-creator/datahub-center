package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.InfluxSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.InfluxdbDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb.InfluxUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real InfluxDB3 Core typed cursor: non-snapshot (time, tag...) keyset, pinned database generation, field-merge target. */
@Timeout(240)
@EnabledIfSystemProperty(named = "datahub.test.influx.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named = "datahub.test.influx.driver", matches = ".+flight-sql-jdbc-driver.*")
@EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = "apiv3_.+")
class MigrationCursorInfluxTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BatchLimits LIMITS = new BatchLimits(3, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0);
    private static final String PROJECTION = "CAST(\"time\" AS BIGINT) AS \"time\",\"host\",\"region\",\"v\",\"note\"";
    private static final String ORDER = "\"time\",\"host\",\"region\"";
    /** Recent nanosecond epoch inside the default retention window, with sub-microsecond digits. */
    private static final long T0 = (System.currentTimeMillis() / 1000 - 7200) * 1_000_000_000L + 123_456_789L;
    private static final List<String> FAMILY = List.of("time", "host", "region", "v", "note");
    private static final List<String> FAMILY_KEYS = List.of("host", "region", "time");
    private String source, target;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private HttpClient http;
    private ConfiguredJdbcDrivers drivers;
    private HikariDataSource sourcePool, targetPool;
    private LossProxy proxy;
    private final List<String> databases = new ArrayList<>();
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = database("cur_" + id + "_s"); target = database("cur_" + id + "_t");
        drivers = drivers();
        sourcePool = pool(URI.create(System.getProperty("datahub.test.influx.http")).getPort(), source);
        targetPool = pool(URI.create(System.getProperty("datahub.test.influx.http")).getPort(), target);
        try (var c = sourcePool.getConnection()) { pin = InfluxSourceGeneration.initialize(sourcePool, c, source, 15); }
        runtime = DbSwitchRuntime.standalone();
    }
    @AfterEach void cleanup() throws Exception {
        try {
            if (runtime != null) runtime.close();
            if (proxy != null) proxy.close();
            for (var p : List.of(sourcePool, targetPool)) if (p != null) close(p);
            if (drivers != null) drivers.close();
        } finally {
            if (http != null) for (String db : databases) try { json("DELETE", "/api/v3/configure/database?db=" + db, ""); } catch (Exception ignored) { }
        }
    }

    @Test void keysetPagesSplitEqualTimestampGroupsAndReplayTheAppliedTimestampGroup() throws Exception {
        family(source, "m"); family(target, "m");
        write(source, "m,host=B,region=r2 v=3i,note=\"B\" " + T0 + "\nm,host=a,region=r1 v=1i,note=\"a\" " + T0
                + "\nm,host=b,region=r1 v=2i,note=\"b\" " + T0 + "\nm,host=a,region=r1 v=4i,note=\"a4\" " + (T0 + 1));
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of(Long.toString(T0 + 1), "a", "r1"), saved.get().applied().values().stream().map(value -> value.value()).toList());
        assertEquals("TIMESTAMP(NS)", saved.get().applied().values().getFirst().typeName());
        awaitRows(target, "m", familyRows(source));
        // Equal-watermark rows of other tag sets that became visible later are replayed; a newer row follows.
        write(source, "m,host=A,region=r9 v=5i,note=\"late\" " + (T0 + 1) + "\nm,host=b,region=r9 v=6i,note=\"late\" " + (T0 + 1)
                + "\nm,host=a,region=r1 v=7i,note=\"a5\" " + (T0 + 5));
        // Older than the applied watermark: outside the cursor window by contract (needs DIFF or an explicit rewind).
        // The same (tagset, time) key is therefore overwritten in the source but must stay untouched in the target.
        write(source, "m,host=a,region=r1 v=8i,note=\"old\" " + T0);
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        var expected = List.of(
                List.of(Long.toString(T0), "B", "r2", "3", "B"),
                List.of(Long.toString(T0), "a", "r1", "1", "a"),
                List.of(Long.toString(T0), "b", "r1", "2", "b"),
                List.of(Long.toString(T0 + 1), "A", "r9", "5", "late"),
                List.of(Long.toString(T0 + 1), "a", "r1", "4", "a4"),
                List.of(Long.toString(T0 + 1), "b", "r9", "6", "late"),
                List.of(Long.toString(T0 + 5), "a", "r1", "7", "a5"));
        awaitRows(target, "m", expected);
        long sequence = saved.get().sequence();
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        awaitRows(target, "m", expected); assertTrue(saved.get().sequence() > sequence, "The applied timestamp group is replayed idempotently");
    }

    @Test void fixedUpperBoundIgnoresRowsArrivingDuringTheRunUntilTheNextRun() throws Exception {
        family(source, "m");
        write(source, "m,host=a,region=r1 v=1i " + T0 + "\nm,host=a,region=r1 v=2i " + (T0 + 1));
        var operator = new InfluxdbDatabaseOperator(sourcePool); operator.configure(OPTIONS); operator.setFetchSize(1);
        var read = new ArrayList<String>();
        try (var cursor = operator.openCursor(new CursorReadRequest("iox", "m", FAMILY, "time", FAMILY_KEYS, null, null, null, LIMITS, false, pin))) {
            assertEquals(List.of(Long.toString(T0 + 1), "a", "r1"), cursor.upperBound().values().stream().map(value -> value.value()).toList());
            while (cursor.next()) {
                read.add(cursor.row()[1] + "@" + cursor.row()[0]);
                if (read.size() == 1) {
                    write(source, "m,host=A,region=r9 v=3i " + (T0 + 1) + "\nm,host=A,region=r9 v=4i " + (T0 + 9));
                    // Ingest is eventually visible; the boundary row must be queryable before its page is read.
                    long deadline = System.currentTimeMillis() + 10000;
                    while (countWhere(source, "m", "host='A' AND region='r9' AND CAST(\"time\" AS BIGINT)=" + (T0 + 1)) == 0
                            && System.currentTimeMillis() < deadline) Thread.sleep(200);
                    assertTrue(countWhere(source, "m", "host='A' AND region='r9' AND CAST(\"time\" AS BIGINT)=" + (T0 + 1)) > 0);
                }
            }
        }
        // A@T0+1 sorts before the frozen (T0+1, a, r1) bound and became visible before its page; T0+9 waits for the next run.
        assertEquals(List.of("a@" + T0, "A@" + (T0 + 1), "a@" + (T0 + 1)), read);
    }

    @Test void completedEmptyWindowRemainsFrozenUntilAnExplicitNextRun() throws Exception {
        family(source, "m"); family(target, "m");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("m", "m", FAMILY, FAMILY_KEYS, 2, value -> calls.incrementAndGet() == 1 && save(value)));
        assertNull(saved.get().fixedUpper()); assertFalse(saved.get().complete());
        write(source, "m,host=a,region=r1 v=1i " + T0);
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        assertEquals(0, count(target, "m")); assertTrue(saved.get().complete());
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        awaitCount(target, "m", 1);
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysAsAnIdempotentOverwrite() throws Exception {
        family(source, "m"); family(target, "m");
        for (int i = 0; i < 5; i++) write(source, "m,host=a,region=r1 v=" + (i + 1) + "i,note=\"n" + i + "\" " + (T0 + i));
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("m", "m", FAMILY, FAMILY_KEYS, 2,
                value -> calls.incrementAndGet() != 3 && save(value)));
        awaitCount(target, "m", 4); assertFalse(saved.get().complete()); assertEquals(1, saved.get().sequence());
        // A stale value at an applied key is overwritten by the replay, not duplicated.
        write(target, "m,host=a,region=r1 v=99i,note=\"stale\" " + (T0 + 1));
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        assertTrue(saved.get().complete());
        awaitRows(target, "m", familyRows(source));
    }

    @Test void lostWriteAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        var endpoint = URI.create(System.getProperty("datahub.test.influx.http"));
        proxy = new LossProxy(endpoint.getHost(), endpoint.getPort());
        close(targetPool); targetPool = pool(proxy.port(), target);
        family(source, "m"); family(target, "m");
        write(source, "m,host=a,region=r1 v=1i " + T0 + "\nm,host=a,region=r1 v=2i " + (T0 + 1));
        proxy.arm("POST /api/v3/write_lp");
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, sourcePool, targetPool, ProductTypeEnum.INFLUXDB3,
                ProductTypeEnum.INFLUXDB3, "iox", "m", "iox", "m", FAMILY, FAMILY, FAMILY_KEYS, FAMILY_KEYS, "time",
                LIMITS, 1, binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(proxy.armed(), "The injected loss happened after a real write");
        awaitCount(target, "m", 1); assertEquals(1, count(target, "m"), "The acknowledged batch lost its response but landed");
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer("m", "m", FAMILY, FAMILY_KEYS, 1, this::save);
        awaitRows(target, "m", familyRows(source));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "TABLE_RECREATE", "TARGET_RECREATE", "DATABASE_DROP"})
    void resumeRejectsAChangedGenerationRelationOrShapeBeforeWriting(String change) throws Exception {
        family(source, "m"); family(target, "m");
        write(source, "m,host=a,region=r1 v=1i " + T0);
        transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
        awaitCount(target, "m", 1);
        switch (change) {
            case "PIN_ROTATE" -> { try (var c = sourcePool.getConnection()) { pin = InfluxSourceGeneration.rotate(sourcePool, c, pin, UUID.randomUUID().toString(), 15); } }
            case "TABLE_RECREATE" -> {
                // A recreation with an identical shape is not observable through read-only catalog evidence; the
                // documented operational response is pin rotation. This case recreates with a changed shape.
                json("DELETE", "/api/v3/configure/table?db=" + source + "&table=m", "");
                json("POST", "/api/v3/configure/table", tableBody(source, "m", List.of("host", "extra"), List.of("v", "note")));
                write(source, "m,host=a,extra=e1 v=1i " + T0);
            }
            case "TARGET_RECREATE" -> { json("DELETE", "/api/v3/configure/table?db=" + target + "&table=m", ""); json("POST", "/api/v3/configure/table", tableBody(target, "m", List.of("host", "extra"), List.of("v", "note"))); }
            case "DATABASE_DROP" -> { json("DELETE", "/api/v3/configure/database?db=" + source, ""); databases.remove(source); }
            default -> { }
        }
        if (!change.equals("DATABASE_DROP")) write(source, "m,host=a,region=r1 v=2i " + (T0 + 1));
        long before = count(target, "m");
        if (change.equals("NONE")) {
            // Control: the unchanged generation, relation and shape resume and deliver the new row.
            transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save);
            awaitCount(target, "m", before + 1); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("m", "m", FAMILY, FAMILY_KEYS, 2, this::save));
        assertEquals(before, count(target, "m"));
    }

    @Test void unsupportedSourceShapesAndMissingGenerationFailBeforeReading() throws Exception {
        family(source, "m"); family(target, "m");
        write(source, "m,host=a,region=r1 v=1i " + T0);
        SourceGenerationPin foreign; try (var c = targetPool.getConnection()) { foreign = InfluxSourceGeneration.initialize(targetPool, c, target, 15); }
        var operator = new InfluxdbDatabaseOperator(sourcePool); operator.configure(OPTIONS); operator.setFetchSize(2);
        var rejected = List.<CursorReadRequest>of(
                new CursorReadRequest("iox", "m", FAMILY, "time", FAMILY_KEYS, null, null, null, LIMITS, false, null),
                new CursorReadRequest("iox", "m", FAMILY, "time", FAMILY_KEYS, null, null, null, LIMITS, false, foreign),
                new CursorReadRequest("iox", "m", FAMILY, "v", FAMILY_KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest("iox", "m", FAMILY, "time", List.of("time"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest("iox", "m", FAMILY, "time", List.of("region", "time"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest("iox", "absent", FAMILY, "time", FAMILY_KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest("iox", "m", List.of("time", "host", "region", "extra"), "time", FAMILY_KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest("public", "m", FAMILY, "time", FAMILY_KEYS, null, null, null, LIMITS, false, pin));
        for (var request : rejected) assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request), () -> String.valueOf(request.primaryKeys()));
        // Controls on the same fixture: only the rejected shape, key, watermark, schema or pin differs.
        try (var cursor = operator.openCursor(new CursorReadRequest("iox", "m", FAMILY, "time", FAMILY_KEYS, null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
        }
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, sourcePool, targetPool, ProductTypeEnum.INFLUXDB3,
                ProductTypeEnum.INFLUXDB3, "iox", "m", "iox", "m", FAMILY, FAMILY, FAMILY_KEYS, FAMILY_KEYS, "time", LIMITS, 2,
                new CursorRunBinding("e".repeat(64), null, this::save, foreign), new DbSwitchTableResult(), () -> { }));
        assertEquals(0, count(target, "m")); assertNull(saved.get());
    }

    @Test void nullTagValueRowsFailBeforeThatBatchIsSent() throws Exception {
        family(source, "m"); family(target, "m");
        write(source, "m,host=a,region=r1 v=1i " + T0 + "\nm,region=r1 v=2i " + (T0 + 1) + "\nm,host=b,region=r1 v=3i " + (T0 + 2));
        assertThrows(IllegalStateException.class, () -> transfer("m", "m", FAMILY, FAMILY_KEYS, 1, this::save));
        awaitCount(target, "m", 1); assertEquals(1, count(target, "m"), "Only the batch before the NULL-key row was sent");
        assertEquals(List.of(Long.toString(T0), "a", "r1"), saved.get().applied().values().stream().map(value -> value.value()).toList());
        assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
    }

    @Test void writerRequiresExactNativeKeysAndRejectsDuplicateOrNullKeyRowsBeforeAnySend() throws Exception {
        family(target, "m");
        var writer = new InfluxUpsertWriter(targetPool); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert("iox", "m", FAMILY, List.of("time")));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert("iox", "m", List.of("time", "v"), FAMILY_KEYS));
        writer.prepareUpsert("iox", "m", FAMILY, FAMILY_KEYS);
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FAMILY, List.of(
                new Object[]{T0, "a", "r1", 1L, "x"}, new Object[]{T0, "a", "r1", 2L, "y"})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FAMILY, List.<Object[]>of(new Object[]{T0, null, "r1", 1L, "x"})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FAMILY, List.<Object[]>of(new Object[]{null, "a", "r1", 1L, "x"})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FAMILY, List.<Object[]>of(new Object[]{"T0", "a", "r1", 1L, "x"})));
        assertEquals(0, count(target, "m"), "No rejected batch may reach the server");
        assertEquals(2, writer.writeWithReceipt(FAMILY, List.of(
                new Object[]{T0, "a", "r1", 1L, "x"}, new Object[]{T0 + 1, "a", "r1", 2L, "y"})).acknowledgedRows());
        assertEquals(2, count(target, "m"));
    }

    @Test void upsertOverwriteMergesFieldsAtTheNativeKeyExactlyAsTheServerDoes() throws Exception {
        family(target, "m");
        write(target, "m,host=a,region=r1 v=1i,note=\"first\" " + T0);
        var writer = new InfluxUpsertWriter(targetPool); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert("iox", "m", FAMILY, FAMILY_KEYS);
        // Writing an existing key replaces exactly the written fields; the omitted NULL note keeps its prior value.
        assertEquals(1, writer.writeWithReceipt(FAMILY, List.<Object[]>of(new Object[]{T0, "a", "r1", 2L, null})).acknowledgedRows());
        awaitRows(target, "m", List.of(List.of(Long.toString(T0), "a", "r1", "2", "first")));
        assertEquals(1, writer.writeWithReceipt(FAMILY, List.<Object[]>of(new Object[]{T0, "a", "r1", 2L, "third"})).acknowledgedRows());
        awaitRows(target, "m", List.of(List.of(Long.toString(T0), "a", "r1", "2", "third")));
    }

    @Test void publicServiceRunsAPinnedCursorAndReplaysTheAppliedTimestamp() throws Exception {
        json("POST", "/api/v3/configure/table", tableBody(source, "s", List.of("host", "region"), List.of("v", "note")));
        json("POST", "/api/v3/configure/table", tableBody(target, "t", List.of("host", "region"), List.of("v", "note")));
        write(source, "s,host=a,region=r1 v=1i,note=\"n0\" " + T0 + "\ns,host=a,region=r1 v=2i,note=\"n1\" " + (T0 + 7));
        var request = request("t");
        try (var service = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers)) {
            var result = new MigrationService(request, ignored -> { }, null, service).run();
            assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
            assertTrue(saved.get().complete());
            awaitRows(target, "t", rows(source, "s"));
            write(source, "s,host=b,region=r1 v=3i,note=\"n2\" " + (T0 + 7));
            request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
            assertTrue(new MigrationService(request, ignored -> { }, null, service).run().getIfAllSuccess().get());
            awaitRows(target, "t", rows(source, "s"));
        }
    }

    private MigrationRequest request(String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.INFLUXDB3); input.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        input.setUrl(url(source)); input.setPassword(token()); input.setPoolSize(1); input.setFetchSize(2);
        input.setSourceSchema("iox"); input.setSourceIncludes("s"); input.setIncreaseColumnName("time");
        input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setRegexTableMapper(List.of(new PatternMapper("^s$", targetTable))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        output.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName()); output.setUrl(url(target));
        output.setPassword(token()); output.setPoolSize(1); output.setTargetSchema("iox");
        output.setBatchLimits(LIMITS); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("s", binding(this::save)));
        return request;
    }
    private void transfer(String sourceTable, String targetTable, List<String> fields, List<String> keys, int fetch, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, sourcePool, targetPool, ProductTypeEnum.INFLUXDB3, ProductTypeEnum.INFLUXDB3, "iox", sourceTable, "iox", targetTable,
                fields, fields, keys, keys, "time", LIMITS, fetch, binding(store), new DbSwitchTableResult(), () -> { });
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint value) { saved.set(value); return true; }

    private String database(String name) throws Exception {
        json("POST", "/api/v3/configure/database", "{\"db\":\"" + name + "\"}"); databases.add(name); return name;
    }
    private void family(String db, String table) throws Exception { json("POST", "/api/v3/configure/table", tableBody(db, table, List.of("host", "region"), List.of("v", "note"))); }
    private static String tableBody(String db, String table, List<String> tags, List<String> fields) {
        var body = new StringBuilder("{\"db\":\"").append(db).append("\",\"table\":\"").append(table).append("\",\"tags\":[");
        for (int i = 0; i < tags.size(); i++) body.append(i > 0 ? "," : "").append('"').append(tags.get(i)).append('"');
        body.append("],\"fields\":[");
        for (int i = 0; i < fields.size(); i++) body.append(i > 0 ? "," : "").append("{\"name\":\"").append(fields.get(i))
                .append("\",\"type\":\"").append(fields.get(i).equals("note") ? "utf8" : "int64").append("\"}");
        return body.append("]}").toString();
    }
    private List<List<String>> familyRows(String db) throws Exception { return rows(db, "m"); }
    private List<List<String>> rows(String db, String table) throws Exception {
        var rows = json("POST", "/api/v3/query_sql", JSON.createObjectNode().put("db", db)
                .put("q", "SELECT " + PROJECTION + " FROM \"" + table + "\" ORDER BY " + ORDER).put("format", "json").toString());
        var result = new ArrayList<List<String>>();
        for (var row : rows) {
            var values = new ArrayList<String>(); var names = row.fieldNames();
            while (names.hasNext()) values.add(text(row.get(names.next())));
            result.add(values);
        }
        return result;
    }
    private static String text(JsonNode value) { return value == null || value.isNull() ? "null" : value.isTextual() ? value.textValue() : value.toString(); }
    private static long count(String db, String table) throws Exception { return countWhere(db, table, null); }
    private static long countWhere(String db, String table, String predicate) throws Exception {
        var client = HttpClient.newHttpClient();
        var body = JSON.createObjectNode().put("db", db)
                .put("q", "SELECT COUNT(*) AS c FROM \"" + table + "\"" + (predicate == null ? "" : " WHERE " + predicate)).put("format", "json").toString();
        var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http") + "/api/v3/query_sql"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token()).header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
        var rows = JSON.readTree(response.body());
        return rows.isEmpty() ? 0 : rows.get(0).get("c").longValue();
    }
    private void awaitCount(String db, String table, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (count(db, table) != expected) {
            if (System.currentTimeMillis() > deadline) fail("Influx row count never converged to " + expected + ": " + count(db, table));
            Thread.sleep(200);
        }
    }
    private void awaitRows(String db, String table, List<List<String>> expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!rows(db, table).equals(expected)) {
            if (System.currentTimeMillis() > deadline) fail("Influx rows never converged: expected " + expected + " but was " + rows(db, table));
            Thread.sleep(200);
        }
    }
    private void write(String db, String lp) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http") + "/api/v3/write_lp?db=" + db + "&precision=nanosecond&accept_partial=false"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token()).header("Content-Type", "text/plain")
                .method("POST", HttpRequest.BodyPublishers.ofString(lp)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, () -> "Influx write status=" + response.statusCode() + " body=" + response.body());
    }
    private JsonNode json(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http") + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, () -> "Influx fixture status=" + response.statusCode() + " body=" + response.body());
        return response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body());
    }
    private HikariDataSource pool(int port, String database) {
        var p = new TargetDataSourceProperties(); p.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        p.setUrl("jdbc:arrow-flight-sql://127.0.0.1:" + port + "/?useEncryption=0&database=" + database);
        p.setPassword(token()); p.setPoolSize(1);
        return DataSourceUtils.createTargetDataSource(p, null, null, drivers.find(ProductTypeEnum.INFLUXDB3).orElseThrow(), drivers.runtimes());
    }
    private static String url(String database) {
        return "jdbc:arrow-flight-sql://127.0.0.1:" + URI.create(System.getProperty("datahub.test.influx.http")).getPort() + "/?useEncryption=0&database=" + database;
    }
    private static void close(HikariDataSource pool) throws Exception {
        var exit = DriverPoolFactory.physicalTermination(pool); pool.close(); exit.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    private static ConfiguredJdbcDrivers drivers() throws Exception {
        var path = Path.of(Objects.requireNonNull(System.getProperty("datahub.test.influx.driver"))); var sha = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) { byte[] bytes = new byte[8192]; int length; while ((length = input.read(bytes)) != -1) sha.update(bytes, 0, length); }
        var spec = new DbSwitchProperties.Driver(); spec.setPath(path.toAbsolutePath().toString()); spec.setSha256(HexFormat.of().formatHex(sha.digest()));
        spec.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        return new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3, spec));
    }
    private static String token() { return System.getenv("DATAHUB_INFLUX_TEST_TOKEN"); }

    /** Byte-forwarding TCP proxy that forwards Flight gRPC transparently and can drop one armed HTTP response after
     *  the upstream has processed the request, proving a lost write acknowledgement on the real server. */
    private static final class LossProxy implements AutoCloseable {
        private final ServerSocket server; private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final String host; private final int port; private volatile String armed;
        LossProxy(String host, int port) throws IOException {
            this.host = host; this.port = port;
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            var accept = new Thread(() -> { while (!server.isClosed()) { try { var socket = server.accept(); sockets.add(socket); new Thread(() -> handle(socket)).start(); } catch (IOException closed) { return; } } });
            accept.setDaemon(true); accept.start();
        }
        int port() { return server.getLocalPort(); }
        boolean armed() { return armed != null; }
        void arm(String requestLinePrefix) { armed = requestLinePrefix; }
        @Override public void close() throws IOException {
            server.close();
            for (var socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        }
        private void handle(Socket client) {
            try (client) {
                var head = head(client.getInputStream());
                String firstLine = new String(head, 0, Math.min(head.length, 512), StandardCharsets.ISO_8859_1).split("\r\n", 2)[0];
                boolean http = firstLine.startsWith("GET ") || firstLine.startsWith("POST ") || firstLine.startsWith("DELETE ") || firstLine.startsWith("PUT ");
                if (!http) { var upstream = new Socket(host, port); upstream.getOutputStream().write(head); pump(client, upstream); return; }
                boolean drop = false;
                if (armed != null && firstLine.startsWith(armed)) { armed = null; drop = true; }
                try (var upstream = new Socket(host, port)) {
                    upstream.getOutputStream().write(closeConnection(head));
                    int content = contentLength(head); var input = client.getInputStream();
                    for (int remaining = content; remaining > 0; ) {
                        byte[] chunk = input.readNBytes(Math.min(remaining, 8192)); if (chunk.length == 0) return;
                        upstream.getOutputStream().write(chunk); remaining -= chunk.length;
                    }
                    if (drop) {
                        // Drain the upstream response first so the server finishes processing the request and no
                        // pending data turns the close into an RST; only the client's copy of the response is lost.
                        upstream.setSoTimeout(10000);
                        var upstreamIn = upstream.getInputStream();
                        var response = head(upstreamIn);
                        int body = contentLength(response);
                        if (body >= 0) upstreamIn.readNBytes(body); else upstreamIn.transferTo(java.io.OutputStream.nullOutputStream());
                        return;
                    }
                    var response = head(upstream.getInputStream());
                    client.getOutputStream().write(response);
                    int body = contentLength(response); var upstreamIn = upstream.getInputStream();
                    if (body >= 0) for (int remaining = body; remaining > 0; ) {
                        byte[] chunk = upstreamIn.readNBytes(Math.min(remaining, 8192)); if (chunk.length == 0) return;
                        client.getOutputStream().write(chunk); remaining -= chunk.length;
                    } else for (byte[] chunk = upstreamIn.readNBytes(8192); chunk.length > 0; chunk = upstreamIn.readNBytes(8192)) client.getOutputStream().write(chunk);
                }
            } catch (Exception ignored) { }
        }
        private static byte[] head(InputStream input) throws IOException {
            var buffer = new ByteArrayOutputStream(); int state = 0; int value;
            while ((value = input.read()) != -1) {
                buffer.write(value);
                state = switch (state) { case 0 -> value == '\r' ? 1 : 0; case 1 -> value == '\n' ? 2 : 0; case 2 -> value == '\r' ? 3 : 0; case 3 -> value == '\n' ? 4 : 2; default -> 4; };
                if (state == 4 || buffer.size() > 65536) return buffer.toByteArray();
            }
            return buffer.toByteArray();
        }
        private static byte[] closeConnection(byte[] head) {
            String text = new String(head, StandardCharsets.ISO_8859_1);
            for (String line : text.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("connection:")) {
                return text.replace(line, "Connection: close").getBytes(StandardCharsets.ISO_8859_1);
            }
            return (text.substring(0, text.length() - 4) + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        }
        private static int contentLength(byte[] head) {
            for (String line : new String(head, StandardCharsets.ISO_8859_1).split("\r\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) try { return Integer.parseInt(line.substring("content-length:".length()).trim()); } catch (NumberFormatException invalid) { return -2; }
            }
            return -1;
        }
        private void pump(Socket left, Socket right) {
            try (left; right) {
                var l2r = new Thread(() -> copy(left, right)); var r2l = new Thread(() -> copy(right, left));
                l2r.setDaemon(true); r2l.setDaemon(true); l2r.start(); r2l.start(); l2r.join(30000); r2l.join(30000);
            } catch (Exception ignored) { }
        }
        private static void copy(Socket from, Socket to) {
            try { from.getInputStream().transferTo(to.getOutputStream()); } catch (IOException ignored) { }
            try { to.shutdownOutput(); } catch (IOException ignored) { }
        }
    }
}
