package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyColumnAcceptance;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.util.StarrocksSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorRunBinding;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorTransfer;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadHelper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductDescriptor;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.STARROCKS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Finite native public entry contracts on StarRocks 3.5.21 and Connector/J 9.7.0.
 * The SPI delegates every operation to the discovered production provider. Only the HTTP socket
 * destination is translated from exact SHOW FRONTENDS/BACKENDS endpoints to owned fixture ports;
 * URI, redirect validation, authentication, request bytes, native admission and receipt parsing remain real.
 */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationStarrocksStreamLoadContractTest {
    private static final List<String> FIELDS = List.of("k", "id", "v", "payload");
    private static final List<String> KEYS = List.of("k", "id");
    private static final String SHAPE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT,payload VARBINARY)"
            + " PRIMARY KEY(k,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";
    private final Map<String, InetSocketAddress> routes = new HashMap<>();
    private final List<BatchWriteResult> receipts = new CopyOnWriteArrayList<>();
    private final List<String> visibleLabels = new CopyOnWriteArrayList<>();
    private final AtomicBoolean loseNextReceipt = new AtomicBoolean();
    private final AtomicInteger plannedDefinitionChecks = new AtomicInteger();
    private HikariDataSource pool;
    private Connection control;
    private String schema;
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        pool = new HikariDataSource(); pool.setJdbcUrl(url(STARROCKS));
        pool.setUsername(user(STARROCKS)); pool.setPassword(password(STARROCKS));
        pool.setMinimumIdle(0); pool.setMaximumPoolSize(4); pool.setConnectionTimeout(10_000);
        control = pool.getConnection();
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT current_version()")) {
            assertTrue(r.next()); assertEquals("3.5.21-85fccf8", r.getString(1));
        }
        try (var s = control.createStatement(); var r = s.executeQuery("SHOW FRONTENDS")) {
            assertTrue(r.next()); assertTrue(r.getBoolean("Alive"));
            int port = r.getInt("HttpPort");
            routes.put("127.0.0.1:" + port, new InetSocketAddress("127.0.0.1", Integer.getInteger("datahub.test.starrocks.fe.http.port", port)));
            assertFalse(r.next(), "This fixture declares one exact frontend");
        }
        try (var s = control.createStatement(); var r = s.executeQuery("SHOW BACKENDS")) {
            assertTrue(r.next()); assertTrue(r.getBoolean("Alive"));
            int port = r.getInt("HttpPort");
            routes.put(r.getString("IP") + ":" + port, new InetSocketAddress("127.0.0.1", Integer.getInteger("datahub.test.starrocks.be.http.port", port)));
            assertFalse(r.next(), "This fixture declares one exact backend");
        }
        schema = "sr_contract_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + schema);
        assertTrue(DbSwitchOptions.defaults().olapStreamLoadEnabled());
        runtime = new DbSwitchRuntime(fixtureRegistry(), DbSwitchOptions.defaults(), null);
    }

    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally {
            try { if (control != null) try (var owned = control) { if (schema != null) sql("DROP DATABASE " + schema + " FORCE"); } }
            finally { if (pool != null) pool.close(); }
        }
    }

    @Test void defaultFullStreamLoadThenJdbcDifferenceAndRepeatConvergeExactly() throws Exception {
        create("source_rows"); create("target_rows"); seed("source_rows");
        var request = request(STARROCKS, "source_rows", "target_rows");
        var full = run(request); success(full);
        assertEquals(3, full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertFalse(receipts.isEmpty(), "Public service must obtain an explicit Stream Load writer receipt");
        assertTrue(receipts.stream().allMatch(r -> r.commitAcknowledgement() == CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE));
        assertFalse(visibleLabels.isEmpty()); assertEquals(rows("source_rows"), rows("target_rows"));
        sql("DELETE FROM " + table("source_rows") + " WHERE k=1 AND id=1");
        sql("UPDATE " + table("source_rows") + " SET v=-7,payload=x'00ff' WHERE k=1 AND id=9007199254740993");
        sql("INSERT INTO " + table("source_rows") + " VALUES(3,9223372036854775807,99,x'000102ff')");
        request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        success(run(request)); assertEquals(rows("source_rows"), rows("target_rows"));
        var repeated = run(request); success(repeated);
        assertEquals(0, repeated.getTableResultList().getFirst().getSyncCount().get());
        assertEquals(rows("source_rows"), rows("target_rows"));
    }

    @ParameterizedTest @ValueSource(strings = {"CREATE_NEW", "STRICT_COPY"})
    void explicitCreateModesRefuseExistingTargetBeforeAnyHttpOrOverwrite(String mode) throws Exception {
        create("source_rows"); create("target_rows"); seed("source_rows");
        sql("INSERT INTO " + table("target_rows") + " VALUES(77,77,77,x'cafe')");
        var sentinel = rows("target_rows"); var request = request(STARROCKS, "source_rows", "target_rows");
        request.getTarget().setWriteMode(mode); request.getTarget().setTargetDrop(true);
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(sentinel, rows("target_rows")); assertTrue(visibleLabels.isEmpty()); assertTrue(receipts.isEmpty());
    }

    @Test void replacingNativeTargetRequiresExplicitExclusiveDdlOwnership() throws Exception {
        create("source_rows"); create("target_rows"); seed("source_rows");
        sql("INSERT INTO " + table("target_rows") + " VALUES(77,77,77,x'cafe')");
        var sentinel = rows("target_rows"); var request = request(STARROCKS, "source_rows", "target_rows");
        request.getTarget().setTargetDrop(true);
        assertFalse(run(request).getIfAllSuccess().get());
        assertEquals(sentinel, rows("target_rows")); assertTrue(visibleLabels.isEmpty());
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
    void crossProductBinaryLossRequiresTheExactTargetColumnAcknowledgementBeforeDestructiveFlags() throws Exception {
        try (var pg = DriverManager.getConnection(url(POSTGRESQL), user(POSTGRESQL), password(POSTGRESQL));
             var s = pg.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            try {
                s.execute("CREATE TABLE " + schema + ".source_rows(id BIGINT PRIMARY KEY,payload BYTEA)");
                s.execute("INSERT INTO " + schema + ".source_rows VALUES(9007199254740993,decode('00ff','hex')),(2,NULL),(3,decode('','hex'))");
                sql("CREATE TABLE " + table("target_rows") + "(id BIGINT NOT NULL,payload VARBINARY) PRIMARY KEY(id)"
                        + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
                sql("INSERT INTO " + table("target_rows") + " VALUES(77,x'cafe')");
                var request = request(POSTGRESQL, "source_rows", "target_rows");
                request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
                request.getTarget().setExclusiveTargetDdlOwnership(true);
                assertFalse(run(request).getIfAllSuccess().get()); assertEquals(List.of("77|CAFE"), binaryRows());
                assertTrue(visibleLabels.isEmpty());
                try (var source = new HikariDataSource()) {
                    source.setJdbcUrl(url(POSTGRESQL)); source.setUsername(user(POSTGRESQL)); source.setPassword(password(POSTGRESQL));
                    source.setMaximumPoolSize(1); source.setMinimumIdle(0);
                    var metadata = runtime.metadata(source, POSTGRESQL);
                    var preview = runtime.products().typeMappings().plan(STARROCKS,
                            metadata.queryTableColumnMetaWithNativeEvidence(schema, "source_rows"),
                            metadata.queryTablePrimaryKeys(schema, "source_rows"), LossyColumnAcceptance.rejectAll());
                    assertEquals(TypeFidelity.LOSSY, preview.fidelity());
                    assertEquals(Set.of("BINARY_LENGTH_LIMIT"), preview.column("payload").lossCodes());
                    request.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(schema, "other_target", preview.column("payload"))));
                    assertFalse(run(request).getIfAllSuccess().get()); assertEquals(List.of("77|CAFE"), binaryRows());
                    assertTrue(visibleLabels.isEmpty());
                    request.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(schema, "target_rows", preview.column("payload"))));
                }
                request.getTarget().setTargetDrop(false); request.getTarget().setTargetTruncateData(false);
                request.getTarget().setExclusiveTargetDdlOwnership(false);
                sql("DELETE FROM " + table("target_rows") + " WHERE id=77");
                success(run(request));
                assertEquals(Arrays.asList("2|null", "3|", "9007199254740993|00FF"), binaryRows());
                assertTrue(receipts.stream().allMatch(r -> r.commitAcknowledgement() == CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE));
            } finally { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void publicCopyFactoryReturnsVisibleStreamAcknowledgement() throws Exception {
        create("target_rows");
        var writer = runtime.writer(pool, STARROCKS, WriteMode.COPY);
        writer.preflightRuntimeWrite(); writer.prepareWrite(schema, "target_rows", FIELDS);
        var receipt = writer.writeWithReceipt(FIELDS, List.<Object[]>of(new Object[]{1L, 1L, 5, new byte[]{0, -1}}));
        assertEquals(1, receipt.acknowledgedRows()); assertNull(receipt.receipt(), "No JDBC transaction receipt is fabricated");
        assertEquals(CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE, receipt.commitAcknowledgement());
        assertEquals(List.of("1|1|5|00FF"), rows("target_rows")); assertEquals(1, visibleLabels.size());
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
    void unsupportedPlannedJsonTypeIsRejectedBeforeExplicitDropOfTheExistingNativeTarget() throws Exception {
        try (var pg = DriverManager.getConnection(url(POSTGRESQL), user(POSTGRESQL), password(POSTGRESQL));
             var s = pg.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            try {
                s.execute("CREATE TABLE " + schema + ".source_rows(id BIGINT PRIMARY KEY,payload INT)");
                s.execute("INSERT INTO " + schema + ".source_rows VALUES(1,2)");
                sql("CREATE TABLE " + table("target_rows") + "(id BIGINT NOT NULL,payload INT) PRIMARY KEY(id)"
                        + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
                sql("INSERT INTO " + table("target_rows") + " VALUES(77,99)");
                var request = request(POSTGRESQL, "source_rows", "target_rows");
                request.getTarget().setTargetDrop(true); request.getTarget().setExclusiveTargetDdlOwnership(true);
                request.getTarget().setCustomDdlMap(Map.of("target_rows", "CREATE TABLE " + table("target_rows")
                        + "(id BIGINT NOT NULL,payload JSON) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')"));
                assertFalse(run(request).getIfAllSuccess().get());
                assertTrue(plannedDefinitionChecks.get() > 0, "The public service must invoke the writer's planned target admission before DROP");
                assertEquals(List.of("77|99"), strings("SELECT CONCAT(id,'|',payload) FROM " + table("target_rows")));
                assertEquals(List.of("int(11)"), strings("SELECT COLUMN_TYPE FROM information_schema.columns WHERE TABLE_SCHEMA='"
                        + schema + "' AND TABLE_NAME='target_rows' AND COLUMN_NAME='payload'"));
                assertTrue(visibleLabels.isEmpty()); assertTrue(receipts.isEmpty());
            } finally { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void lostVisibleHttpReceiptKeepsCursorUnappliedAndFreshReplayConverges() throws Exception {
        create("source_rows"); create("target_rows"); seed("source_rows");
        var pin = StarrocksSourceGeneration.initialize(control, schema, 15);
        var saved = new AtomicReference<CursorCheckpoint>(); var result = new DbSwitchTableResult();
        var limits = new BatchLimits(8, 65536, 16384, 100, 1000);
        loseNextReceipt.set(true);
        var binding = new CursorRunBinding("7".repeat(64), null, checkpoint -> { saved.set(checkpoint); return true; }, pin);
        assertThrows(UnconfirmedWriteException.class, () -> CursorTransfer.execute(runtime, pool, pool, STARROCKS, STARROCKS,
                schema, "source_rows", schema, "target_rows", FIELDS, FIELDS, KEYS, KEYS, "k", limits, 8, binding, result, () -> { }));
        assertFalse(loseNextReceipt.get()); assertEquals(rows("source_rows"), rows("target_rows"), "Real backend commit preceded deliberate response loss");
        assertNotNull(saved.get()); assertFalse(saved.get().complete()); assertNull(saved.get().applied());
        assertEquals(0, saved.get().sequence()); assertEquals(CommitAcknowledgement.NONE, saved.get().lastCommitAcknowledgement());
        assertEquals(0, result.getSyncCount().get()); assertEquals(1, visibleLabels.size()); assertTrue(receipts.isEmpty());
        var previous = saved.get();
        CursorTransfer.execute(runtime, pool, pool, STARROCKS, STARROCKS, schema, "source_rows", schema, "target_rows",
                FIELDS, FIELDS, KEYS, KEYS, "k", limits, 8,
                new CursorRunBinding("7".repeat(64), previous, checkpoint -> { saved.set(checkpoint); return true; }, pin),
                new DbSwitchTableResult(), () -> { });
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE, saved.get().lastCommitAcknowledgement());
        assertEquals(rows("source_rows"), rows("target_rows")); assertEquals(2, visibleLabels.size());
        assertNotEquals(visibleLabels.get(0), visibleLabels.get(1), "Fresh cursor invocation sends a new idempotent primary-key replacement");
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
    void unknownFullWriteNeverExecutesSourceOrTargetAfterScriptsAfterLocalWorkersExit() throws Exception {
        try (var pg = DriverManager.getConnection(url(POSTGRESQL), user(POSTGRESQL), password(POSTGRESQL)); var s = pg.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            try {
                var request = afterScriptRequest(pg); loseNextReceipt.set(true);
                var service = new MigrationService(request, ignored -> { }, null, runtime);
                var result = service.run();
                assertFalse(result.getIfAllSuccess().get()); assertEquals(1, result.getTableResultList().size());
                assertFalse(loseNextReceipt.get()); assertEquals(1, visibleLabels.size()); assertTrue(receipts.isEmpty());
                assertEquals(List.of("1|1", "2|2", "9007199254740993|3"), strings("SELECT CONCAT(id,'|',v) FROM " + table("target_rows") + " ORDER BY id"),
                        "The native StarRocks backend applied the PG source batch before the real receipt was lost");
                var progress = result.getTableResultList().getFirst().getBatchProgress();
                assertNotNull(progress); assertTrue(progress.terminated()); assertEquals(3, progress.unknownRows());
                assertEquals(0, progress.acknowledgedRows()); assertEquals(MigrationTermination.UNCONFIRMED, service.termination());
                assertAll("Local worker exit cannot authorize follow-up SQL while the remote write remains UNKNOWN",
                        () -> assertEquals(0, pgMarker(pg), "The supported PostgreSQL source-after UPDATE must not execute"),
                        () -> assertEquals(List.of("0"), strings("SELECT v FROM " + table("target_after_marker") + " WHERE id=1")),
                        () -> assertTrue(service.scriptExecutions().stream().noneMatch(hook -> hook.phase().equals("source-after") || hook.phase().equals("target-after")),
                                () -> service.scriptExecutions().toString()));
            } finally { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
    void knownRejectedFullRetainsSupportedSourceAndStarrocksTargetCleanup() throws Exception {
        try (var pg = DriverManager.getConnection(url(POSTGRESQL), user(POSTGRESQL), password(POSTGRESQL)); var s = pg.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            try {
                var request = afterScriptRequest(pg); request.getTarget().setWriteMode("CREATE_NEW");
                var service = new MigrationService(request, ignored -> { }, null, runtime); var result = service.run();
                assertFalse(result.getIfAllSuccess().get()); assertTrue(visibleLabels.isEmpty()); assertTrue(receipts.isEmpty());
                assertEquals(List.of("0"), strings("SELECT COUNT(*) FROM " + table("target_rows")), "Known preflight refusal sent no data batch");
                assertEquals(99, pgMarker(pg), "Known rejection retains the supported PostgreSQL cleanup hook");
                assertEquals(List.of("99"), strings("SELECT v FROM " + table("target_after_marker") + " WHERE id=1"));
                assertEquals(List.of("source-after", "target-after"), service.scriptExecutions().stream().map(MigrationService.ScriptExecution::phase).toList());
                assertEquals(MigrationService.ScriptState.SUCCEEDED, service.scriptExecutions().getFirst().state());
                assertEquals(1, service.scriptExecutions().getFirst().acknowledgedStatements());
                // The trusted hook parser admits StarRocks INSERT; PRIMARY KEY replaces this marker.
                // Unlike an UNKNOWN data receipt, a known preflight refusal retains both cleanup hooks.
                assertEquals(MigrationService.ScriptState.SUCCEEDED, service.scriptExecutions().getLast().state());
                assertEquals(1, service.scriptExecutions().getLast().acknowledgedStatements());
            } finally { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    private MigrationRequest afterScriptRequest(Connection pg) throws Exception {
        try (var s = pg.createStatement()) {
            s.execute("CREATE TABLE " + schema + ".source_rows(id BIGINT PRIMARY KEY,v INT)");
            s.execute("INSERT INTO " + schema + ".source_rows VALUES(1,1),(2,2),(9007199254740993,3)");
            s.execute("CREATE TABLE " + schema + ".source_after_marker(id BIGINT PRIMARY KEY,v INT)");
            s.execute("INSERT INTO " + schema + ".source_after_marker VALUES(1,0)");
        }
        for (String name : List.of("target_rows", "target_after_marker")) {
            sql("CREATE TABLE " + table(name) + "(id BIGINT NOT NULL,v INT) PRIMARY KEY(id)"
                    + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        }
        sql("INSERT INTO " + table("target_after_marker") + " VALUES(1,0)");
        var request = request(POSTGRESQL, "source_rows", "target_rows"); request.getSource().getFirst().setFetchSize(8);
        request.getSource().getFirst().setAfterSqlScripts("UPDATE " + schema + ".source_after_marker SET v=99 WHERE id=1");
        request.getTarget().setAfterSqlScripts("INSERT INTO " + table("target_after_marker") + " VALUES(1,99)");
        return request;
    }
    private int pgMarker(Connection pg) throws Exception {
        try (var s = pg.createStatement(); var r = s.executeQuery("SELECT v FROM " + schema + ".source_after_marker WHERE id=1")) {
            assertTrue(r.next()); int value = r.getInt(1); assertFalse(r.next()); return value;
        }
    }

    private DbSwitchProductRegistry fixtureRegistry() {
        var discovered = DbSwitchProductRegistry.discover(); var original = discovered.product(STARROCKS);
        var builder = DbSwitchProductDescriptor.builder(STARROCKS).metadata(original::metadata).reader(original::reader)
                .writer(WriteMode.INSERT, source -> intercepted(original.writer(source, WriteMode.INSERT), IDatabaseWriter.class))
                .writer(WriteMode.COPY, source -> intercepted(original.writer(source, WriteMode.COPY), IDatabaseWriter.class))
                .synchronizer(original::synchronizer)
                .upsert(source -> intercepted(original.upsertWriter(source), IDatabaseUpsertWriter.class))
                .cursorTargets(original.cursorTargets().toArray(ProductTypeEnum[]::new));
        original.typeMapper().ifPresent(builder::typeMapper);
        original.capabilities().forEach((operation, capability) -> {
            if (capability.status() == OperationCapability.Status.UNSUPPORTED) builder.unsupported(operation, capability.reason());
            else { assertEquals(OperationCapability.Status.DECLARED, capability.status()); builder.declare(operation, capability.reason()); }
        });
        var fixture = builder.build(); assertEquals(original.capabilities(), fixture.capabilities());
        return new DbSwitchProductRegistry(discovered.products().stream().map(p -> p.product() == STARROCKS ? fixture : p).toList());
    }

    private <T extends IDatabaseWriter> T intercepted(IDatabaseWriter delegate, Class<T> contract) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            try {
                if (method.getName().equals("preflightTargetDefinition")) plannedDefinitionChecks.incrementAndGet();
                Object value = method.invoke(delegate, args);
                if (method.getName().equals("prepareWrite") || method.getName().equals("prepareUpsert")) {
                    var helper = (OlapStreamLoadHelper) ReflectionTestUtils.getField(delegate, delegate instanceof IDatabaseUpsertWriter ? "stream" : "streamLoadHelper");
                    assertNotNull(helper, "The production prepare path must establish Stream Load; no fallback can pass this test");
                    Function<RequestConfig, CloseableHttpClient> clientFactory = this::mappedClient;
                    ReflectionTestUtils.setField(helper, "clientFactory", clientFactory);
                }
                if (method.getName().equals("writeWithReceipt")) receipts.add((BatchWriteResult) value);
                return value;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }));
    }

    private CloseableHttpClient mappedClient(RequestConfig config) {
        var sockets = new PlainConnectionSocketFactory() {
            @Override public Socket connectSocket(int timeout, Socket socket, HttpHost host, InetSocketAddress remote,
                                                  InetSocketAddress local, HttpContext context) throws IOException {
                var mapped = routes.get(host.getHostName() + ":" + host.getPort());
                if (mapped == null) throw new IOException("Undeclared isolated fixture route");
                return super.connectSocket(timeout, socket, host, mapped, local, context);
            }
        };
        var registry = RegistryBuilder.<ConnectionSocketFactory>create().register("http", sockets).build();
        var manager = new BasicHttpClientConnectionManager(registry, null, null, host -> {
            if (routes.keySet().stream().noneMatch(route -> route.startsWith(host + ":"))) throw new java.net.UnknownHostException("Undeclared fixture host");
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        });
        HttpResponseInterceptor interceptor = (response, context) -> {
            if (response.getStatusLine().getStatusCode() != 200) return;
            byte[] bytes = EntityUtils.toByteArray(response.getEntity()); var body = new ObjectMapper().readTree(bytes);
            if (body.path("Status").asText().equals("Success")) {
                visibleLabels.add(body.path("Label").asText());
                if (loseNextReceipt.compareAndSet(true, false)) throw new IOException("Owned fault after receiving the real native visible receipt");
            }
            response.setEntity(new org.apache.http.entity.ByteArrayEntity(bytes));
        };
        return HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config).disableAutomaticRetries()
                .disableRedirectHandling().disableContentCompression().addInterceptorLast(interceptor).build();
    }

    private MigrationRequest request(ProductTypeEnum from, String sourceTable, String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(from); input.setUrl(url(from)); input.setUsername(user(from)); input.setPassword(password(from));
        input.setSourceSchema(schema); input.setIncludeOrExclude(1); input.setSourceTableNames(List.of(sourceTable));
        input.setPoolSize(1); input.setFetchSize(2); input.setRegexTableMapper(List.of(new PatternMapper("^" + sourceTable + "$", targetTable)));
        request.getSource().add(input); var target = request.getTarget();
        target.setTargetProductType(STARROCKS); target.setUrl(url(STARROCKS)); target.setUsername(user(STARROCKS)); target.setPassword(password(STARROCKS));
        target.setTargetSchema(schema); target.setPoolSize(1); target.setWriterEngineInsert(true); return request;
    }
    private DbSwitchResult run(MigrationRequest request) throws Exception { return new MigrationService(request, ignored -> { }, null, runtime).run(); }
    private static void success(DbSwitchResult result) { assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
    private void create(String name) throws Exception { sql("CREATE TABLE " + table(name) + SHAPE); }
    private void seed(String name) throws Exception { sql("INSERT INTO " + table(name) + " VALUES(1,1,1,NULL),(1,9007199254740993,2,x''),(2,3,3,x'0002ff')"); }
    private List<String> rows(String name) throws Exception { return strings("SELECT CONCAT(k,'|',id,'|',v,'|',IFNULL(HEX(payload),'null')) FROM " + table(name) + " ORDER BY k,id"); }
    private List<String> binaryRows() throws Exception { return strings("SELECT CONCAT(id,'|',IFNULL(HEX(payload),'null')) FROM " + table("target_rows") + " ORDER BY id"); }
    private List<String> strings(String text) throws Exception { var result = new ArrayList<String>(); try (var s=control.createStatement(); var r=s.executeQuery(text)) { while (r.next()) result.add(r.getString(1)); } return result; }
    private void sql(String text) throws Exception { try (var s=control.createStatement()) { s.setQueryTimeout(30); s.execute(text); } }
    private String table(String name) { return schema + "." + name; }
    private static String key(ProductTypeEnum product) { return product == POSTGRESQL ? "postgres" : "starrocks"; }
    private static String url(ProductTypeEnum product) { return System.getProperty("datahub.test." + key(product) + ".url"); }
    private static String user(ProductTypeEnum product) { return System.getProperty("datahub.test." + key(product) + ".user", product == POSTGRESQL ? "dataforge_test" : "root"); }
    private static String password(ProductTypeEnum product) { return System.getProperty("datahub.test." + key(product) + ".password", ""); }
}
