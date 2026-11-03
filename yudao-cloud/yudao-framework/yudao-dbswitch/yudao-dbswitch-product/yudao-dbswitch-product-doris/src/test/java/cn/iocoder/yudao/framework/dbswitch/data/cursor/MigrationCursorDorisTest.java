package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DorisDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadHelper;
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
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real Doris 4.0.8 typed cursor: pinned non-snapshot keyset with identity recheck, and the UNIQUE merge-on-write UPSERT. */
@Timeout(300)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationCursorDorisTest {
    private static final BatchLimits LIMITS = new BatchLimits(8, 65536, 16384, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0);
    private static final String TABLE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT,d DECIMAL(38,18),big LARGEINT,note VARCHAR(50),at DATETIME(6),day DATE) ENGINE=OLAP UNIQUE KEY(k,id)"
            + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')";
    private static final String SIMPLE = "(k BIGINT NOT NULL,id BIGINT NOT NULL,note VARCHAR(50)) ENGINE=OLAP UNIQUE KEY(k,id)"
            + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')";
    private static final List<String> FIELDS = List.of("k", "id", "v", "d", "big", "note", "at", "day");
    private static final List<String> SIMPLE_FIELDS = List.of("k", "id", "note");
    private static final List<String> KEYS = List.of("k", "id");
    private static final BigDecimal MAX_LARGEINT = new BigDecimal("170141183460469231731687303715884105727");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private Connection control;
    private String source, target;
    private SourceGenerationPin pin;
    private DbSwitchRuntime runtime;
    private final List<String> databases = new ArrayList<>();
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        control = DriverManager.getConnection(url(), "root", "");
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = database("dcur_s_" + id); target = database("dcur_t_" + id);
        pin = DorisSourceGeneration.initialize(control, source, 15);
        runtime = new DbSwitchRuntime(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover(), OPTIONS, null);
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally {
            try (var owned = control) {
                for (String database : databases) sql("DROP DATABASE " + database + " FORCE");
            }
        }
    }

    @Test void keysetPagesDeliverExactValuesAndResumeReplaysTheAppliedWatermarkGroup() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + TABLE); sql("CREATE TABLE " + table(target, "t") + TABLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,10,1,12.340000000000000000," + MAX_LARGEINT + ",'一''\\\\','2026-10-03 12:34:56.123456','2026-10-01'),"
                + "(1,20,2,-0.000000000000000001," + MAX_LARGEINT.negate() + ",'','0001-01-01 00:00:00.000001','9999-12-31'),"
                + "(2,5,3,NULL,NULL,NULL,NULL,NULL),(2,6,4,0,0,'max','2026-01-01 00:00:00','2026-02-01'),(3,1,5,1,1,'last','9999-12-31 23:59:59.999999','2026-03-01')");
        // Page size 2 splits the (2,*) watermark group across pages and proves the ORDER BY matches the model.
        transfer("s", "t", FIELDS, 2, this::save);
        assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        assertEquals(List.of("3", "1"), saved.get().applied().values().stream().map(v -> v.value()).toList());
        assertEquals(List.of("3", "1"), saved.get().fixedUpper().values().stream().map(v -> v.value()).toList());
        assertEquals(rows(source, "s"), rows(target, "t"));
        // Equal-watermark rows that became visible later are replayed by the next run; a newer watermark row follows.
        sql("INSERT INTO " + table(source, "s") + " VALUES (3,0,6,2.000000000000000000,2,'late','2026-10-04 00:00:00','2026-10-04'),(4,9,7,3,3,'new',NULL,NULL)");
        // Older than the applied watermark: outside the cursor window by contract (needs DIFF or an explicit rewind).
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,99,8,4,4,'old',NULL,NULL)");
        transfer("s", "t", FIELDS, 2, this::save);
        var expected = rows(source, "s"); expected.removeIf(row -> row.get(0).equals("1") && row.get(1).equals("99"));
        assertEquals(expected, rows(target, "t"));
        long sequence = saved.get().sequence();
        transfer("s", "t", FIELDS, 2, this::save);
        assertEquals(expected, rows(target, "t")); assertTrue(saved.get().sequence() > sequence, "The applied watermark group is replayed idempotently");
    }

    @Test void fixedUpperBoundIgnoresRowsBeyondItUntilTheNextRun() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(2,1,'b')");
        var operator = new DorisDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(1);
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
        assertEquals(rows(source, "s"), rows(target, "t"));
    }

    @Test void checkpointFailureAfterAcknowledgedBatchReplaysAsAnIdempotentOverwrite() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(1,2,'b'),(2,1,'c'),(2,2,'d')");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer("s", "t", SIMPLE_FIELDS, 1,
                value -> calls.incrementAndGet() != 3 && save(value)));
        awaitCount(target, "t", 2, 10); assertFalse(saved.get().complete()); assertEquals(1, saved.get().sequence());
        // A stale value at an applied key is overwritten by the replay, not duplicated.
        sql("INSERT INTO " + table(target, "t") + " VALUES (1,2,'stale')");
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertTrue(saved.get().complete());
        assertEquals(rows(source, "s"), rows(target, "t"));
    }

    @Test void lostInsertAcknowledgementIsUnknownAndNeverAdvancesTheCheckpoint() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'a'),(2,1,'b')");
        var fault = new AtomicBoolean(true);
        assertThrows(PartialBatchWriteException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(fault), ProductTypeEnum.DORIS,
                ProductTypeEnum.DORIS, source, "s", target, "t", SIMPLE_FIELDS, SIMPLE_FIELDS, KEYS, KEYS, "k",
                LIMITS, 2, binding(this::save), new DbSwitchTableResult(), () -> { }));
        assertFalse(fault.get()); awaitCount(target, "t", 2, 10);
        assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer("s", "t", SIMPLE_FIELDS, 1, this::save);
        assertEquals(rows(source, "s"), rows(target, "t"));
    }

    @ParameterizedTest @ValueSource(strings = {"NONE", "PIN_ROTATE", "DATABASE_RECREATE", "TABLE_RECREATE", "TRUNCATE", "TARGET_RECREATE"})
    void resumeRejectsAChangedGenerationRelationOrTargetBeforeWriting(String change) throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        transfer("s", "t", SIMPLE_FIELDS, 2, this::save);
        assertEquals(1, count(target, "t"));
        switch (change) {
            case "PIN_ROTATE" -> pin = DorisSourceGeneration.rotate(control, pin, UUID.randomUUID().toString(), 15);
            case "DATABASE_RECREATE" -> { sql("DROP DATABASE " + source + " FORCE"); databases.remove(source); source = database(source);
                sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TABLE_RECREATE" -> { sql("DROP TABLE " + table(source, "s")); sql("CREATE TABLE " + table(source, "s") + SIMPLE);
                sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TRUNCATE" -> { sql("TRUNCATE TABLE " + table(source, "s")); sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')"); }
            case "TARGET_RECREATE" -> { sql("DROP TABLE " + table(target, "t")); sql("CREATE TABLE " + table(target, "t") + SIMPLE); }
            default -> { }
        }
        sql("INSERT INTO " + table(source, "s") + " VALUES (2,1,'new')");
        long before = count(target, "t");
        if (change.equals("NONE")) {
            transfer("s", "t", SIMPLE_FIELDS, 2, this::save);
            assertEquals(before + 1, count(target, "t")); return;
        }
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "t", SIMPLE_FIELDS, 2, this::save));
        assertEquals(before, count(target, "t"));
    }

    @Test void partitionPlanChangesRejectTheOldResumeBeforeAnyWrite() throws Exception {
        String shape = "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT) ENGINE=OLAP UNIQUE KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('10'))"
                + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')";
        sql("CREATE TABLE " + table(source, "s") + shape); sql("CREATE TABLE " + table(target, "t") + shape);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,1)");
        transfer("s", "t", List.of("k", "id", "v"), 2, this::save);
        assertEquals(1, count(target, "t"));
        sql("ALTER TABLE " + table(source, "s") + " ADD PARTITION p2 VALUES LESS THAN ('20')");
        sql("INSERT INTO " + table(source, "s") + " VALUES (11,2,2)");
        assertThrows(IllegalArgumentException.class, () -> transfer("s", "t", List.of("k", "id", "v"), 2, this::save));
        assertEquals(1, count(target, "t"));
    }

    @Test void unsupportedSourceShapesAndMissingGenerationFailBeforeReading() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,1,'x')");
        sql("CREATE TABLE " + table(source, "dup") + "(k INT NOT NULL,v INT) ENGINE=OLAP DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("CREATE TABLE " + table(source, "agg") + "(k INT NOT NULL,s BIGINT SUM) ENGINE=OLAP AGGREGATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1')");
        // Doris refuses FLOAT/DOUBLE key columns in DDL, so an approximate key cannot even exist: the cursor would
        // reject it through the key-domain whitelist, which the pure contract test covers.
        sql("CREATE TABLE " + table(source, "txt") + "(w VARCHAR(20) NOT NULL,v INT) ENGINE=OLAP UNIQUE KEY(w) DISTRIBUTED BY HASH(w) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
        sql("INSERT INTO " + table(source, "txt") + " VALUES ('a',1)");
        SourceGenerationPin foreign = DorisSourceGeneration.initialize(control, target, 15);
        var operator = new DorisDatabaseOperator(ds(null)); operator.configure(OPTIONS); operator.setFetchSize(2);
        var rejected = List.<CursorReadRequest>of(
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, null),
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "k", KEYS, null, null, null, LIMITS, false, foreign),
                new CursorReadRequest(source, "s", SIMPLE_FIELDS, "note", KEYS, null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "dup", List.of("k", "v"), "k", List.of("k"), null, null, null, LIMITS, false, pin),
                new CursorReadRequest(source, "agg", List.of("k", "s"), "k", List.of("k"), null, null, null, LIMITS, false, pin));
        for (var request : rejected) assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request), request.table());
        // Text keys replay only under the identical native contract: the same product with the same type is accepted.
        try (var cursor = operator.openCursor(new CursorReadRequest(source, "txt", List.of("w", "v"), "w", List.of("w"), null, null, null, LIMITS, false, pin))) {
            assertTrue(cursor.next());
        }
        sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.DORIS, ProductTypeEnum.DORIS,
                source, "s", target, "t", SIMPLE_FIELDS, SIMPLE_FIELDS, KEYS, KEYS, "k", LIMITS, 2,
                new CursorRunBinding("e".repeat(64), null, this::save, foreign), new DbSwitchTableResult(), () -> { }));
        assertEquals(0, count(target, "t")); assertNull(saved.get());
    }

    @Test void upsertWriterReplacesFullRowsAndRejectsPartialDuplicateOrUnsupportedBatches() throws Exception {
        sql("CREATE TABLE " + table(target, "t") + TABLE);
        sql("INSERT INTO " + table(target, "t") + " VALUES (1,1,9,9,9,'stale',NULL,NULL)");
        var writer = new DorisUpsertWriter(ds(null)); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(target, "t", FIELDS, KEYS);
        assertTrue(writer.targetSchemaFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(ProductTypeEnum.DORIS, writer.keyContract().product());
        var replaced = List.<Object[]>of(new Object[]{1L, 1L, 1, new BigDecimal("12.340000000000000000"), MAX_LARGEINT, "一'\\", AT, DAY});
        assertEquals(1, writer.writeWithReceipt(FIELDS, replaced).acknowledgedRows());
        assertEquals(List.of(List.of("1", "1", "1", "12.340000000000000000", "170141183460469231731687303715884105727", "一'\\", "2026-10-03T12:34:56.123456", "2026-10-01")), rows(target, "t"));
        assertEquals(1, writer.writeWithReceipt(FIELDS, replaced).acknowledgedRows(), "Full-row upsert replaces the existing key and converges");
        assertEquals(1, count(target, "t"));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FIELDS, List.of(
                new Object[]{2L, 2L, 1, null, null, "a", null, null}, new Object[]{2L, 2L, 2, null, null, "b", null, null})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(List.of("k", "id", "v"), List.<Object[]>of(new Object[]{1L, 1L, 1})));
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FIELDS, List.<Object[]>of(new Object[]{3L, null, 1, null, null, "a", null, null})));
        assertEquals(1, count(target, "t"));
        sql("CREATE TABLE " + table(target, "partial") + "(k BIGINT NOT NULL,id BIGINT NOT NULL,v INT) ENGINE=OLAP UNIQUE KEY(k,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(target, "partial", List.of("k", "id", "v"), List.of("k")));
        sql("CREATE TABLE " + table(target, "mor") + "(k BIGINT NOT NULL,v INT) ENGINE=OLAP UNIQUE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='false')");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(target, "mor", List.of("k", "v"), List.of("k")));
        sql("CREATE TABLE " + table(target, "seq") + "(k BIGINT NOT NULL,s BIGINT,v INT) ENGINE=OLAP UNIQUE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true','function_column.sequence_col'='s')");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(target, "seq", List.of("k", "s", "v"), List.of("k")));
    }

    @Test void streamLoadReceiptPathReplacesRowsAndNeverRewritesAnUnconfirmedLabel() throws Exception {
        sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(target, "t") + " VALUES (1,1,'before')");
        var routes = new HashMap<String, InetSocketAddress>();
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SHOW FRONTENDS")) {
            assertTrue(rows.next()); routes.put("127.0.0.1:" + rows.getInt("HttpPort"), new InetSocketAddress("127.0.0.1", Integer.getInteger("datahub.test.doris.fe.http.port", rows.getInt("HttpPort"))));
        }
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SHOW BACKENDS")) {
            assertTrue(rows.next()); routes.put(rows.getString("Host") + ":" + rows.getInt("HttpPort"), new InetSocketAddress("127.0.0.1", Integer.getInteger("datahub.test.doris.be.http.port", rows.getInt("HttpPort"))));
        }
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(url()); pool.setUsername("root"); pool.setPassword(""); pool.setMaximumPoolSize(3);
            var receipts = new AtomicInteger();
            var writer = new DorisUpsertWriter(pool);
            writer.configure(new DbSwitchOptions(65536, false, true, 20, 0, 0, 250, 0)); writer.configureBatchLimits(LIMITS);
            writer.prepareUpsert(target, "t", SIMPLE_FIELDS, KEYS);
            var helper = (OlapStreamLoadHelper) ReflectionTestUtils.getField(writer, "stream");
            assertNotNull(helper, "JDBC fallback cannot certify Stream Load");
            Function<RequestConfig, CloseableHttpClient> factory = config -> {
                var socketFactory = new PlainConnectionSocketFactory() {
                    @Override public Socket connectSocket(int timeout, Socket socket, HttpHost host, InetSocketAddress remote, InetSocketAddress local, HttpContext context) throws IOException {
                        InetSocketAddress mapped = routes.get(host.getHostName() + ":" + host.getPort());
                        if (mapped == null) throw new IOException("Unknown fixture route");
                        return super.connectSocket(timeout, socket, host, mapped, local, context);
                    }
                };
                var registry = RegistryBuilder.<ConnectionSocketFactory>create().register("http", socketFactory).build();
                var manager = new BasicHttpClientConnectionManager(registry, null, null, host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
                HttpResponseInterceptor interceptor = (response, context) -> {
                    if (response.getStatusLine().getStatusCode() != 200) return;
                    byte[] bytes = EntityUtils.toByteArray(response.getEntity());
                    var receipt = new ObjectMapper().readTree(bytes);
                    assertEquals("Success", receipt.path("Status").asText(), "A real BE receipt is required");
                    assertTrue(receipt.path("TxnId").asLong() > 0); receipts.incrementAndGet();
                    response.setEntity(new org.apache.http.entity.ByteArrayEntity(bytes));
                };
                return HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config).disableAutomaticRetries()
                        .disableRedirectHandling().disableContentCompression().addInterceptorLast(interceptor).build();
            };
            ReflectionTestUtils.setField(helper, "clientFactory", factory);
            assertEquals(1, writer.writeWithReceipt(SIMPLE_FIELDS, List.<Object[]>of(new Object[]{1L, 1L, "replaced"})).acknowledgedRows());
            assertEquals(1, receipts.get());
            assertEquals(List.of(List.of("1", "1", "replaced")), rows(target, "t"));
            // A lost response after the real BE acknowledgement stays UNKNOWN: no second request, no rewrite.
            var lost = new DorisUpsertWriter(pool);
            lost.configure(new DbSwitchOptions(65536, false, true, 20, 0, 0, 250, 0)); lost.configureBatchLimits(LIMITS);
            lost.prepareUpsert(target, "t", SIMPLE_FIELDS, KEYS);
            var lostHelper = (OlapStreamLoadHelper) ReflectionTestUtils.getField(lost, "stream");
            ReflectionTestUtils.setField(lostHelper, "clientFactory", (Function<RequestConfig, CloseableHttpClient>) config -> {
                var socketFactory = new PlainConnectionSocketFactory() {
                    @Override public Socket connectSocket(int timeout, Socket socket, HttpHost host, InetSocketAddress remote, InetSocketAddress local, HttpContext context) throws IOException {
                        InetSocketAddress mapped = routes.get(host.getHostName() + ":" + host.getPort());
                        if (mapped == null) throw new IOException("Unknown fixture route");
                        return super.connectSocket(timeout, socket, host, mapped, local, context);
                    }
                };
                var registry = RegistryBuilder.<ConnectionSocketFactory>create().register("http", socketFactory).build();
                var manager = new BasicHttpClientConnectionManager(registry, null, null, host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
                HttpResponseInterceptor interceptor = (response, context) -> {
                    if (response.getStatusLine().getStatusCode() != 200) return;
                    EntityUtils.toByteArray(response.getEntity());
                    throw new IOException("controlled response loss after real backend acknowledgement");
                };
                return HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config).disableAutomaticRetries()
                        .disableRedirectHandling().disableContentCompression().addInterceptorLast(interceptor).build();
            });
            var failure = assertThrows(UnconfirmedWriteException.class, () -> lost.writeWithReceipt(SIMPLE_FIELDS, List.<Object[]>of(new Object[]{2L, 2L, "unknown"})));
            assertNull(failure.getCause());
            assertEquals(List.of(List.of("1", "1", "replaced"), List.of("2", "2", "unknown")), rows(target, "t"), "The real write landed; it is never rewritten");
        }
    }

    @Test void publicServiceRunsAPinnedCursorAndResumeDeliversNewRows() throws Exception {
        sql("CREATE TABLE " + table(source, "s") + SIMPLE); sql("CREATE TABLE " + table(target, "t") + SIMPLE);
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,2,'a'),(1,1,'b'),(2,1,'c')");
        var request = request(ProductTypeEnum.DORIS, target, "t");
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertTrue(saved.get().complete()); assertEquals(rows(source, "s"), rows(target, "t"));
        sql("INSERT INTO " + table(source, "s") + " VALUES (2,0,'d')");
        request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(rows(source, "s"), rows(target, "t"));
    }

    @Test @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void postgresTargetKeepsExactIntegerKeysWhileTemporalKeysAreRejected() throws Exception {
        Class.forName("org.postgresql.Driver");
        String pgUrl = System.getProperty("datahub.test.postgres.url"), pgUser = System.getProperty("datahub.test.postgres.user", "dataforge_test"),
                pgPassword = System.getProperty("datahub.test.postgres.password", "");
        sql("CREATE TABLE " + table(source, "s") + "(k BIGINT NOT NULL,id BIGINT NOT NULL,v BIGINT) ENGINE=OLAP UNIQUE KEY(k,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
        sql("INSERT INTO " + table(source, "s") + " VALUES (1,10,100),(1,20,200),(2,5,300)");
        String schema = "dcur_pg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), quoted = '"' + schema + '"';
        try (var pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
            pgSql(pg, "CREATE SCHEMA " + quoted);
            try {
                pgSql(pg, "CREATE TABLE " + quoted + ".t (k bigint NOT NULL, id bigint NOT NULL, v bigint NOT NULL, PRIMARY KEY (k, id))");
                CursorTransfer.execute(runtime, ds(null), new org.springframework.jdbc.datasource.DriverManagerDataSource(pgUrl, pgUser, pgPassword),
                        ProductTypeEnum.DORIS, ProductTypeEnum.POSTGRESQL, source, "s", schema, "t",
                        List.of("k", "id", "v"), List.of("k", "id", "v"), KEYS, KEYS, "k", LIMITS, 2, binding(this::save),
                        new DbSwitchTableResult(), () -> { });
                assertEquals(ProductTypeEnum.POSTGRESQL, saved.get().targetProduct()); assertTrue(saved.get().complete());
                assertEquals(List.of(List.of("1", "10", "100"), List.of("1", "20", "200"), List.of("2", "5", "300")), pgRows(pg, quoted + ".t"));
                // A civil Doris DATE watermark carries no proven instant/epoch: it never maps into a PostgreSQL key.
                sql("CREATE TABLE " + table(source, "d") + "(d DATE NOT NULL,id BIGINT NOT NULL,v INT) ENGINE=OLAP UNIQUE KEY(d,id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
                sql("INSERT INTO " + table(source, "d") + " VALUES ('2026-10-01',1,1)");
                saved.set(null);
                assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, ds(null),
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(pgUrl, pgUser, pgPassword),
                        ProductTypeEnum.DORIS, ProductTypeEnum.POSTGRESQL, source, "d", schema, "t",
                        List.of("d", "id", "v"), List.of("d", "id", "v"), List.of("d", "id"), List.of("k", "id"), "d", LIMITS, 2,
                        binding(this::save), new DbSwitchTableResult(), () -> { }));
                assertNull(saved.get()); assertEquals(3, pgRows(pg, quoted + ".t").size());
            } finally { pgSql(pg, "DROP SCHEMA " + quoted + " CASCADE"); }
        }
    }

    private MigrationRequest request(ProductTypeEnum targetProduct, String targetSchema, String targetTable) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.DORIS); input.setDriverClassName("com.mysql.cj.jdbc.Driver");
        input.setUrl(url()); input.setUsername("root"); input.setPassword(""); input.setPoolSize(2); input.setFetchSize(2);
        input.setSourceSchema(source); input.setSourceIncludes("s"); input.setIncreaseColumnName("k"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        input.setSourcePrimaryKeys(KEYS);
        input.setRegexTableMapper(List.of(new PatternMapper("^s$", targetTable))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(targetProduct); output.setDriverClassName("com.mysql.cj.jdbc.Driver");
        output.setUrl(url()); output.setUsername("root"); output.setPassword(""); output.setPoolSize(2); output.setTargetSchema(targetSchema);
        output.setBatchLimits(LIMITS); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("s", binding(this::save)));
        return request;
    }
    private void transfer(String sourceTable, String targetTable, List<String> fields, int fetch, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, ds(null), ds(null), ProductTypeEnum.DORIS, ProductTypeEnum.DORIS, source, sourceTable, target, targetTable,
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
    private static String canonical(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof LocalDateTime time) return time.toString();
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        return String.valueOf(value);
    }
    private long count(String schema, String table) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table(schema, table))) {
            result.next(); return result.getLong(1);
        }
    }
    /** OLAP visibility is eventually consistent across connections; acknowledged rows become visible within a bounded window. */
    private void awaitCount(String schema, String table, long expected, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long actual = -1;
        while (System.currentTimeMillis() < deadline) {
            actual = count(schema, table);
            if (actual == expected) return;
            Thread.sleep(200);
        }
        fail("Target row count is " + actual + ", expected " + expected);
    }
    private static List<List<String>> pgRows(Connection pg, String table) throws SQLException {
        try (var statement = pg.createStatement(); var result = statement.executeQuery("SELECT k::text,id::text,v::text FROM " + table + " ORDER BY k,id")) {
            var rows = new ArrayList<List<String>>();
            while (result.next()) rows.add(List.of(result.getString(1), result.getString(2), result.getString(3)));
            return rows;
        }
    }
    private static void pgSql(Connection pg, String text) throws SQLException { try (var statement = pg.createStatement()) { statement.setQueryTimeout(20); statement.execute(text); } }
    private static String url() { return System.getProperty("datahub.test.doris.url"); }
    private void sql(String text) throws Exception { try (var statement = control.createStatement()) { statement.setQueryTimeout(30); statement.execute(text); } }
    /** Owned connections that fail on JDBC transaction control; an armed fault loses one INSERT acknowledgement after the write. */
    private DataSource ds(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = DriverManager.getConnection(url(), "root", "");
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Unexpected Doris JDBC transaction control");
                    try {
                        Object value = method.invoke(real, args);
                        if (fault != null && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("INSERT INTO")) {
                            var statement = (PreparedStatement) value;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    Object outcome = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && fault.compareAndSet(true, false)) throw new SQLException("injected response loss after actual write");
                                    return outcome;
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
