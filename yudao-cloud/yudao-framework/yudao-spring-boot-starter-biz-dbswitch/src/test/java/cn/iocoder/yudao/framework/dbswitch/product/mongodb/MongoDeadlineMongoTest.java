package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real server behind a bounded loopback TCP relay; packets stall without returning a server timeout. */
@EnabledIfSystemProperty(named = "datahub.test.mongodb.url", matches = "jdbc:mongodb://127[.]0[.]0[.]1:[0-9]+/admin\\?authSource=admin")
@EnabledIfSystemProperty(named = "datahub.test.mongodb.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_MONGODB_TEST_PASSWORD", matches = ".+")
@Timeout(30)
class MongoDeadlineMongoTest {
    private static ConfiguredJdbcDrivers drivers;
    private static DbSwitchProperties.Driver bundleConfig;
    private DbSwitchRuntime runtime;
    private String schema;
    @BeforeAll static void bundle() throws Exception {
        var jar = Path.of(System.getProperty("datahub.test.mongodb.driver")).toRealPath();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(jar)) { byte[] buffer = new byte[8192]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString()); config.setSha256(HexFormat.of().formatHex(digest.digest()));
        bundleConfig = config; drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.MONGODB, config));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(),
                new DbSwitchOptions(4L * 1024 * 1024, true, false, 1, 0, 300_000, 30_000, 0), drivers);
        schema = "deadline_" + UUID.randomUUID().toString().replace("-", "");
        command("{\"create\":\"target\"}");
    }
    @AfterEach void cleanup() throws Exception {
        try { command("{\"dropDatabase\":1}"); }
        finally { runtime.close(); }
        awaitNoPhysicalResources();
    }

    @Test void stalledNativeCommandReturnsByWallDeadlineAndClosesActualDriverResources() throws Exception {
        try (var relay = new Relay()) {
            Connection connection = source(relay.url()).getConnection();
            try {
                var session = MongoNativeSession.open(connection, 4L * 1024 * 1024, 1);
                MongoReplies.success(session, session.command(schema, session.encode("{\"ping\":1}")));
                relay.pauseRequests.set(true);
                long started = System.nanoTime();
                assertThrows(SQLTimeoutException.class, () -> session.command(schema, session.encode("{\"ping\":1}")));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
            } finally {
                try { connection.close(); } catch (SQLTimeoutException stillDraining) { /* Must remain charged until both exits. */ }
            }
            awaitNoPhysicalResources();
            assertTrue(connection.isClosed());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry.State.READY, drivers.runtimes().statuses().getFirst().state());
        }
    }

    @Test void lostInsertResponseIsUnknownAlthoughServerAcceptedRowsAndIsNeverReplayed() throws Exception {
        try (var relay = new Relay()) {
            var writer = runtime.writer(source(relay.url()), ProductTypeEnum.MONGODB, WriteMode.INSERT);
            writer.prepareWrite(schema, "target", List.of("_id", "value"));
            relay.stallInsertResponse.set(true);
            long started = System.nanoTime();
            assertThrows(UnconfirmedWriteException.class, () -> writer.write(List.of("_id", "value"),
                    List.of(new Object[]{1L, "first"}, new Object[]{2L, "second"})));
            assertTrue(relay.insertForwarded.get());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3500);
            awaitNoPhysicalResources();
            assertEquals(2, MongoReplies.integer(command("{\"count\":\"target\"}").path("n")));
            assertEquals(1, relay.insertCount.get(), "No retry may be inferred from a missing response");
        }
    }

    @Test void actualMongoClientSettingsUseTheShorterUrlOrPropertyLimitAndDisableRetries() throws Exception {
        var properties = new Properties(); properties.setProperty("SocketTimeoutMS", "1200");
        properties.setProperty("connectTimeoutMS", "999999"); properties.setProperty("retryReads", "true");
        String url = System.getProperty("datahub.test.mongodb.url")
                + "&socketTimeoutMS=1500&SOCKETTIMEOUTMS=0&%73erverSelectionTimeoutMS=0&retryWrites=true";
        try (var owner = new cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource(
                drivers.find(ProductTypeEnum.MONGODB).orElseThrow().acquire(), url, "q11root",
                System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"), properties); var connection = owner.getConnection()) {
            Object physical = cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess.unwrap(connection, "com.gitee.jdbc.mongodb.MongoConnection");
            var clientField = physical.getClass().getDeclaredField("client"); assertTrue(clientField.trySetAccessible());
            Object wrapped = clientField.get(physical); var nativeField = wrapped.getClass().getDeclaredField("mongoClient"); assertTrue(nativeField.trySetAccessible());
            Object client = nativeField.get(wrapped); Object settings = client.getClass().getMethod("getSettings").invoke(client);
            Object sockets = settings.getClass().getMethod("getSocketSettings").invoke(settings);
            assertEquals(1200, sockets.getClass().getMethod("getReadTimeout", TimeUnit.class).invoke(sockets, TimeUnit.MILLISECONDS));
            assertEquals(10000, sockets.getClass().getMethod("getConnectTimeout", TimeUnit.class).invoke(sockets, TimeUnit.MILLISECONDS));
            Object cluster = settings.getClass().getMethod("getClusterSettings").invoke(settings);
            assertEquals(30000L, cluster.getClass().getMethod("getServerSelectionTimeout", TimeUnit.class).invoke(cluster, TimeUnit.MILLISECONDS));
            assertEquals(false, settings.getClass().getMethod("getRetryReads").invoke(settings));
            assertEquals(false, settings.getClass().getMethod("getRetryWrites").invoke(settings));
        }
        awaitNoPhysicalResources();
    }

    @Test void pooledConnectionCannotReuseTimedOutNativeWorkAndReleasesItsLeaseAfterPoolClose() throws Exception {
        try (var relay = new Relay()) {
            var properties = new cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties();
            properties.setTargetProductType(ProductTypeEnum.MONGODB); properties.setUrl(relay.url());
            properties.setUsername("q11root"); properties.setPassword(System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
            properties.setPoolSize(1); properties.setConnectionTimeout(5000L);
            try (var pool = cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils.createTargetDataSource(properties,
                    null, null, drivers.find(ProductTypeEnum.MONGODB).orElseThrow())) {
                try (var connection = pool.getConnection()) {
                    var session = MongoNativeSession.open(connection, 4L * 1024 * 1024, 1);
                    MongoReplies.success(session, session.command(schema, session.encode("{\"ping\":1}")));
                    relay.pauseRequests.set(true);
                    assertThrows(SQLTimeoutException.class, () -> session.command(schema, session.encode("{\"ping\":1}")));
                }
                awaitNoPhysicalResources(); relay.pauseRequests.set(false);
                try (var replacement = pool.getConnection()) {
                    var session = MongoNativeSession.open(replacement, 4L * 1024 * 1024, 1);
                    assertEquals(1, MongoReplies.integer(MongoReplies.success(session,
                            session.command(schema, session.encode("{\"ping\":1}"))).path("ok")));
                }
            }
        }
        awaitNoPhysicalResources(); assertEquals(0, drivers.runtimes().statuses().getFirst().leases());
    }

    @Test void registryShutdownNeverReopensProcessProfileAfterLateNativeTermination() throws Exception {
        try (var isolated = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.MONGODB, bundleConfig)); var relay = new Relay()) {
            var binding = isolated.find(ProductTypeEnum.MONGODB).orElseThrow();
            var connection = cn.iocoder.yudao.framework.dbswitch.driver.DriverConnections.open(ProductTypeEnum.MONGODB,
                    binding, isolated.runtimes(), relay.url(), "q11root", System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
            try {
                var session = MongoNativeSession.open(connection, 4L * 1024 * 1024, 1);
                MongoReplies.success(session, session.command(schema, session.encode("{\"ping\":1}")));
                relay.pauseRequests.set(true);
                assertThrows(SQLTimeoutException.class, () -> session.command(schema, session.encode("{\"ping\":1}")));
                isolated.runtimes().close();
            } finally { try { connection.close(); } catch (SQLTimeoutException stillDraining) { } }
            awaitNoPhysicalResources(isolated);
            assertEquals(cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry.State.DRAINING, isolated.runtimes().statuses().getFirst().state());
            assertThrows(IllegalStateException.class, binding::acquire);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode command(String command) throws Exception {
        try (var connection = source(System.getProperty("datahub.test.mongodb.url")).getConnection()) {
            var session = MongoNativeSession.open(connection, 4L * 1024 * 1024, 5);
            return MongoReplies.success(session, session.command(schema, session.encode(command)));
        }
    }
    private AbstractDataSource source(String url) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return runtime.openConnection(ProductTypeEnum.MONGODB, null, url, "q11root", System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Fixture credentials are fixed"); }
        };
    }
    private static void awaitNoPhysicalResources() throws Exception {
        awaitNoPhysicalResources(drivers);
    }
    private static void awaitNoPhysicalResources(ConfiguredJdbcDrivers observedDrivers) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (observedDrivers.runtimes().connections().snapshot().workload() + observedDrivers.runtimes().connections().snapshot().metadata() != 0
                && System.nanoTime() < deadline) Thread.sleep(10);
        var observed = observedDrivers.runtimes().connections().snapshot();
        assertEquals(0, observed.workload()); assertEquals(0, observed.metadata()); assertEquals(0, observed.quarantined());
    }

    /** At most eight sockets pairs and seventeen workers; closing the test always closes all sockets. */
    private static final class Relay implements AutoCloseable {
        private final ServerSocket listener;
        private final int backend;
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(17, 17, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon(true).name("mongo-deadline-relay-", 0).factory());
        private final Set<Pair> pairs = ConcurrentHashMap.newKeySet();
        private final Semaphore slots = new Semaphore(8);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean pauseRequests = new AtomicBoolean(), pauseResponses = new AtomicBoolean();
        private final AtomicBoolean stallInsertResponse = new AtomicBoolean(), insertForwarded = new AtomicBoolean();
        private final AtomicInteger insertCount = new AtomicInteger();
        Relay() throws Exception {
            var uri = URI.create(System.getProperty("datahub.test.mongodb.url").substring(5)); backend = uri.getPort();
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> {
                while (!closed.get()) try {
                    Socket client = listener.accept();
                    if (!slots.tryAcquire()) { client.close(); continue; }
                    Socket server = new Socket(); server.connect(new InetSocketAddress("127.0.0.1", backend), 1000);
                    Pair pair = new Pair(client, server); pairs.add(pair);
                    workers.execute(() -> copy(pair, true)); workers.execute(() -> copy(pair, false));
                } catch (Exception stopped) { if (!closed.get()) closed.set(true); }
            });
        }
        String url() { return "jdbc:mongodb://127.0.0.1:" + listener.getLocalPort() + "/admin?authSource=admin&socketTimeoutMS=1500"; }
        private void copy(Pair pair, boolean request) {
            try {
                var input = (request ? pair.client : pair.server).getInputStream();
                var output = (request ? pair.server : pair.client).getOutputStream();
                while (!closed.get() && !pair.finished.get()) {
                    byte[] header = input.readNBytes(16); if (header.length != 16) return;
                    int size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    if (size < 16 || size > 1024 * 1024) return;
                    byte[] body = input.readNBytes(size - 16); if (body.length != size - 16) return;
                    boolean insert = request && contains(body, new byte[]{'i','n','s','e','r','t',0});
                    if (insert) { insertCount.incrementAndGet(); if (stallInsertResponse.get()) pauseResponses.set(true); }
                    while (!closed.get() && !pair.finished.get() && (request ? pauseRequests.get() : pauseResponses.get())) Thread.sleep(5);
                    if (closed.get() || pair.finished.get()) return;
                    output.write(header); output.write(body); output.flush();
                    if (insert) insertForwarded.set(true);
                }
            } catch (Exception stopped) { /* This fixture deliberately breaks TCP reads. */ }
            finally { pair.close(); }
        }
        private static boolean contains(byte[] input, byte[] needle) {
            outer: for (int i = 0; i <= input.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) if (input[i + j] != needle[j]) continue outer;
                return true;
            }
            return false;
        }
        @Override public void close() throws Exception {
            closed.set(true); listener.close(); for (var pair : pairs) pair.close();
            workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS), "Relay worker sockets must terminate");
        }
        private final class Pair {
            final Socket client, server; final AtomicBoolean finished = new AtomicBoolean();
            Pair(Socket client, Socket server) { this.client = client; this.server = server; }
            void close() {
                if (!finished.compareAndSet(false, true)) return;
                try { client.close(); } catch (Exception ignored) { }
                try { server.close(); } catch (Exception ignored) { }
                pairs.remove(this); slots.release();
            }
        }
    }
}
