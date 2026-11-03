package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real verified legacy bridge against a controlled HTTP peer; no Elasticsearch service is required. */
@EnabledIfSystemProperty(named = "datahub.test.elasticsearch.driver", matches = ".+")
@Timeout(20)
class ElasticsearchNativeTransportTest {
    private static ConfiguredJdbcDrivers drivers;
    private static String user;
    private static String password;

    @BeforeAll static void verifiedBundle() throws Exception {
        user = UUID.randomUUID().toString(); password = UUID.randomUUID().toString();
        Path jar = Path.of(System.getProperty("datahub.test.elasticsearch.driver")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(jar)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) hash.update(bytes, 0, count);
        }
        var spec = new DbSwitchProperties.Driver(); spec.setPath(jar.toString());
        spec.setSha256(HexFormat.of().formatHex(hash.digest()));
        spec.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.ELASTICSEARCH, spec));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @AfterEach void budgetRecovered() { assertEquals(0, drivers.runtimes().connections().snapshot().active()); }

    @Test void jsonWithoutCharsetPreservesUtf8AndRestoresContextLoader() throws Exception {
        try (var peer = new Peer(exchange -> send(exchange, "{\"value\":\"中文😀\"}".getBytes(StandardCharsets.UTF_8), false));
             var connection = peer.open()) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            var value = ElasticsearchNativeSession.open(connection, 1024, 2).request("GET", "/", null).requireSuccess();
            assertEquals("中文😀", value.path("value").textValue());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            assertEquals(1, peer.requests.get());
        }
    }

    @Test void declaredAndChunkedOversizeResponsesAbortBeforeUnboundedMaterialization() throws Exception {
        for (boolean chunked : List.of(false, true)) {
            byte[] oversized = ("{\"private\":\"" + "secret-payload".repeat(200) + "\"}").getBytes(StandardCharsets.UTF_8);
            try (var peer = new Peer(exchange -> send(exchange, oversized, chunked)); var connection = peer.open()) {
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                SQLException failure = assertThrows(SQLException.class,
                        () -> ElasticsearchNativeSession.open(connection, 64, 2).request("GET", "/", null));
                assertFalse(failure.getMessage().contains("secret-payload")); assertNull(failure.getCause());
                assertSame(previous, Thread.currentThread().getContextClassLoader());
                assertEquals(1, peer.requests.get());
            }
        }
    }

    @Test void malformedUtf8DuplicateMembersAndTrailingJsonFailClosed() throws Exception {
        for (byte[] body : List.of(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, (byte) 0x28, '"', '}'},
                "{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8),
                "{\"x\":1} {\"x\":2}".getBytes(StandardCharsets.UTF_8))) {
            try (var peer = new Peer(exchange -> send(exchange, body, false)); var connection = peer.open()) {
                SQLException failure = assertThrows(SQLException.class,
                        () -> ElasticsearchNativeSession.open(connection, 1024, 2).request("GET", "/", null));
                assertNull(failure.getCause()); assertEquals(1, peer.requests.get());
            }
        }
    }

    @Test void stalledBodyHonorsSocketTimeoutAndReleasesControlledConnection() throws Exception {
        var release = new CountDownLatch(1);
        try (var peer = new Peer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }); var connection = peer.open()) {
            long started = System.nanoTime();
            assertThrows(SQLException.class, () -> ElasticsearchNativeSession.open(connection, 1024, 1).request("GET", "/", null));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000,
                    "Configured socket timeout must apply to response-body reads");
            assertEquals(1, peer.requests.get());
        } finally { release.countDown(); }
    }

    @Test void bulkResponseReadFailureIsUnknownAndNeverReplayed() throws Exception {
        var bulkRequests = new AtomicInteger();
        try (var peer = new Peer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/sample/_mapping")) {
                send(exchange, "{\"sample\":{\"mappings\":{\"properties\":{\"value\":{\"type\":\"long\"}}}}}"
                        .getBytes(StandardCharsets.UTF_8), false);
            } else {
                bulkRequests.incrementAndGet();
                // A peer may have accepted a write before returning a malformed/oversized receipt.
                send(exchange, ("{\"private\":\"" + "secret-payload".repeat(200) + "\"}").getBytes(StandardCharsets.UTF_8), true);
            }
        })) {
            var writer = peer.runtime.writer(peer.source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT);
            List<String> fields = List.of("_id", "value"); writer.prepareWrite("es", "sample", fields);
            var failure = assertThrows(UnconfirmedWriteException.class,
                    () -> writer.write(fields, List.<Object[]>of(new Object[]{"id", 9007199254740993L})));
            assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("secret-payload"));
            assertEquals(1, bulkRequests.get());
        }
    }

    private static void send(HttpExchange exchange, byte[] body, boolean chunked) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
        exchange.getResponseBody().write(body);
    }

    private static final class Peer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        private final AtomicInteger requests = new AtomicInteger();
        private final DbSwitchRuntime runtime;
        Peer(HttpHandler response) throws Exception {
            runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(),
                    new DbSwitchOptions(1024, true, false, 2, 0, 300_000, 30_000, 0), drivers);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try (exchange) { response.handle(exchange); }
                catch (IOException expectedAbort) { /* The boundary tests deliberately abort the response. */ }
            });
            server.setExecutor(executor); server.start();
        }
        Connection open() throws SQLException {
            return runtime.openConnection(ProductTypeEnum.ELASTICSEARCH, null,
                    "jdbc:jest://127.0.0.1:" + server.getAddress().getPort(), user, password);
        }
        AbstractDataSource source() {
            return new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException { return open(); }
                @Override public Connection getConnection(String user, String password) throws SQLException {
                    throw new SQLException("Fixture credentials are fixed");
                }
            };
        }
        @Override public void close() {
            server.stop(0); executor.shutdownNow(); runtime.close();
        }
    }
}
