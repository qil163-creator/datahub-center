package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionProbes;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.product.milvus.MilvusConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb2.Influxdb2ConnectionProbe;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.net.InetSocketAddress;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立连接契约与真实 HTTP 交互测试，不把协议夹具当成数据库产品认证。 */
class IndependentConnectionProbeTest {
    private static final Set<ProductTypeEnum> CONNECTION_ONLY = EnumSet.of(ProductTypeEnum.GBASE8C,
            ProductTypeEnum.GAUSSDB, ProductTypeEnum.PHOENIX, ProductTypeEnum.SAPHANA, ProductTypeEnum.PRESTO,
            ProductTypeEnum.ODPS, ProductTypeEnum.NEO4J, ProductTypeEnum.MILVUS, ProductTypeEnum.INFLUXDB2);

    @Test void newProductsExposeOnlyConnectAndNeverBorrowMetadataOrWriterFactories() {
        var registry = DbSwitchProductRegistry.discover();
        for (var product : CONNECTION_ONLY) {
            var descriptor = registry.product(product);
            assertNotNull(descriptor.connectionProbe());
            for (var operation : DatabaseOperation.values()) {
                if (operation == DatabaseOperation.CONNECT) descriptor.require(operation);
                else {
                    assertEquals(OperationCapability.Status.UNSUPPORTED, descriptor.capabilities().get(operation).status());
                    assertThrows(UnsupportedOperationException.class, () -> descriptor.require(operation));
                }
            }
            assertThrows(UnsupportedOperationException.class, () -> registry.metadata(product));
        }
    }

    @Test void bundledJdbcConnectorsContainTheirDeclaredDriverEntries() throws Exception {
        for (var product : EnumSet.of(ProductTypeEnum.GAUSSDB, ProductTypeEnum.SAPHANA, ProductTypeEnum.PRESTO)) {
            assertNotNull(Class.forName(product.getDriveClassName(), false, getClass().getClassLoader()));
        }
    }

    @Test void legacyDescriptorConstructorKeepsExistingOperationsWhenConnectIsAbsent() {
        var capabilities = new EnumMap<DatabaseOperation, OperationCapability>(DatabaseOperation.class);
        for (var operation : DatabaseOperation.values()) {
            if (operation != DatabaseOperation.CONNECT) capabilities.put(operation, OperationCapability.unsupported("fixture"));
        }
        var descriptor = new DbSwitchProductDescriptor(ProductTypeEnum.MYSQL, null, null, Map.of(), null, capabilities);
        assertEquals(OperationCapability.Status.UNSUPPORTED, descriptor.capabilities().get(DatabaseOperation.CONNECT).status());
    }

    @Test void runtimeCanProbeAProductWithoutAnyMetadataOrJdbcDriver() {
        var descriptor = DbSwitchProductDescriptor.builder(ProductTypeEnum.NEO4J).connect((request, opener) -> {}).build();
        try (var runtime = new DbSwitchRuntime(new DbSwitchProductRegistry(List.of(descriptor)), DbSwitchOptions.defaults(), null)) {
            assertTrue(runtime.probeConnection(request(ProductTypeEnum.NEO4J, null, Map.of(), "secret")).connectionSuccessful());
        }
    }

    @Test void requestBoundsTimeoutAndNeverPrintsCredentialsOrEndpoint() {
        var request = request(ProductTypeEnum.NEO4J, "bolt://private.example", Map.of("token", "secret"), "secret");
        assertFalse(request.toString().contains("secret"));
        assertFalse(request.toString().contains("private.example"));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionProbeRequest(ProductTypeEnum.NEO4J,
                null, null, null, null, null, null, null, Map.of(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionProbeRequest(ProductTypeEnum.NEO4J,
                null, null, null, null, null, null, null, Map.of(), Duration.ofSeconds(31)));
    }

    @Test void jdbcFixedQueryClosesAllOwnedResourcesAndDoesNotReadMetadata() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(rows); when(rows.next()).thenReturn(true);
        var request = new ConnectionProbeRequest(ProductTypeEnum.GAUSSDB, null, "jdbc:postgresql://localhost/db",
                null, null, null, "db", null, Map.of(), Duration.ofSeconds(2));
        JdbcConnectionProbe.standard().probe(request, () -> connection);
        verify(connection, never()).getMetaData(); verify(statement).setQueryTimeout(2);
        verify(rows).close(); verify(statement).close(); verify(connection).close();
    }

    @Test void jdbcEmptyResponseFailsAndStillClosesResources() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery("SELECT 1")).thenReturn(rows);
        var request = new ConnectionProbeRequest(ProductTypeEnum.GAUSSDB, null, "jdbc:postgresql://localhost/db",
                null, null, null, "db", null, Map.of(), Duration.ofSeconds(2));
        assertThrows(SQLException.class, () -> JdbcConnectionProbe.standard().probe(request, () -> connection));
        verify(rows).close(); verify(statement).close(); verify(connection).close();
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"GBASE8C", "PHOENIX"})
    void missingDriverReturnsSafeDependencyFailureRatherThanSocketSuccess(ProductTypeEnum product) {
        try (var runtime = DbSwitchRuntime.standalone()) {
            String url = product == ProductTypeEnum.PHOENIX
                    ? "jdbc:phoenix:thin:url=http://localhost:8765;serialization=PROTOBUF" : "jdbc:gbase8c://localhost/db";
            var request = new ConnectionProbeRequest(product, null, url,
                    null, null, "never-log-this", "db", null, Map.of(), Duration.ofSeconds(2));
            var result = runtime.probeConnection(request);
            assertFalse(result.connectionSuccessful());
            assertEquals("SKIPPED", result.status());
            assertTrue(result.checks().getFirst().message().startsWith("MISSING_DEPENDENCY"));
            assertFalse(result.toString().contains("never-log-this"));
        }
    }

    @Test void nativeFailuresNeverExposeSdkMessagesAndTimeoutInterruptsOwnedWork() {
        try (var probes = new ConnectionProbes()) {
            var request = new ConnectionProbeRequest(ProductTypeEnum.NEO4J, null, null, null,
                    null, "secret", null, null, Map.of(), Duration.ofSeconds(1));
            var failure = probes.probe(request, (ignored, opener) -> { throw new SQLException("secret"); }, () -> null);
            assertFalse(failure.connectionSuccessful()); assertFalse(failure.toString().contains("secret"));
            var ended = new CountDownLatch(1);
            var result = probes.probe(request, (ignored, opener) -> {
                try { new CountDownLatch(1).await(); } finally { ended.countDown(); }
            }, () -> null);
            assertFalse(result.connectionSuccessful()); assertTrue(result.checks().getFirst().message().startsWith("CONNECTION_TIMEOUT"));
            assertTrue(ended.await(2, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
    }

    @Test void milvusRequiresAuthenticatedBusinessSuccessAndHonorsTokenMode() throws Exception {
        var authorization = new AtomicReference<String>(); var body = new AtomicReference<String>();
        var reply = new AtomicReference<>("{\"code\":0,\"data\":[]}");
        var server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            respond(exchange, 200, reply.get());
        });
        try (var probes = new ConnectionProbes()) {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var request = request(ProductTypeEnum.MILVUS, endpoint, Map.of(), "api-token");
            assertTrue(probes.probe(request, new MilvusConnectionProbe(), () -> null).connectionSuccessful());
            assertEquals("Bearer api-token", authorization.get()); assertTrue(body.get().contains("target"));
            reply.set("{\"code\":1800,\"message\":\"api-token\"}");
            var denied = probes.probe(request, new MilvusConnectionProbe(), () -> null);
            assertFalse(denied.connectionSuccessful()); assertFalse(denied.toString().contains("api-token"));
            reply.set("{\"data\":[]}");
            assertFalse(probes.probe(request, new MilvusConnectionProbe(), () -> null).connectionSuccessful());
        } finally { server.stop(0); }
    }

    @Test void influxChecksTokenOrganizationAndBucketRatherThanHealth() throws Exception {
        var seen = new ArrayList<String>(); var bucket = new AtomicReference<>("target");
        var server = server(exchange -> {
            synchronized (seen) { seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()); }
            assertEquals("Token api-token", exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestURI().getPath().equals("/api/v2/orgs")) {
                respond(exchange, 200, "{\"orgs\":[{\"id\":\"org-id\",\"name\":\"org-name\"}]}");
            } else respond(exchange, 200, "{\"buckets\":[{\"name\":\"" + bucket.get() + "\",\"orgID\":\"org-id\"}]}");
        });
        try (var probes = new ConnectionProbes()) {
            var request = request(ProductTypeEnum.INFLUXDB2, "http://127.0.0.1:" + server.getAddress().getPort(),
                    Map.of("org", "org-name", "bucket", "target"), "api-token");
            assertTrue(probes.probe(request, new Influxdb2ConnectionProbe(), () -> null).connectionSuccessful());
            bucket.set("other");
            assertFalse(probes.probe(request, new Influxdb2ConnectionProbe(), () -> null).connectionSuccessful());
            assertEquals(List.of("GET /api/v2/orgs", "GET /api/v2/buckets", "GET /api/v2/orgs", "GET /api/v2/buckets"), seen);
        } finally { server.stop(0); }
    }

    @Test void neo4jVerifiesConnectivityThenChecksConfiguredDatabaseAndClosesBothResources() throws Exception {
        var driver = mock(org.neo4j.driver.Driver.class);
        var session = mock(org.neo4j.driver.Session.class);
        var result = mock(org.neo4j.driver.Result.class);
        var record = mock(org.neo4j.driver.Record.class);
        when(driver.session(any(org.neo4j.driver.SessionConfig.class))).thenReturn(session);
        when(session.run(eq("RETURN 1 AS connected"), any(org.neo4j.driver.TransactionConfig.class))).thenReturn(result);
        when(result.single()).thenReturn(record);
        when(record.get("connected")).thenReturn(org.neo4j.driver.Values.value(1));
        try (var factory = mockStatic(org.neo4j.driver.GraphDatabase.class)) {
            factory.when(() -> org.neo4j.driver.GraphDatabase.driver(any(java.net.URI.class),
                    any(org.neo4j.driver.AuthToken.class), any(org.neo4j.driver.Config.class))).thenReturn(driver);
            var request = new ConnectionProbeRequest(ProductTypeEnum.NEO4J, null, null, "bolt://127.0.0.1:7687",
                    "fixture-user", "fixture-password", "target", null, Map.of(), Duration.ofSeconds(2));
            new cn.iocoder.yudao.framework.dbswitch.product.neo4j.Neo4jConnectionProbe().probe(request, () -> null);
            var config = org.mockito.ArgumentCaptor.forClass(org.neo4j.driver.SessionConfig.class);
            var order = inOrder(driver, session);
            order.verify(driver).verifyConnectivity(); order.verify(driver).session(config.capture());
            order.verify(session).run(eq("RETURN 1 AS connected"), any(org.neo4j.driver.TransactionConfig.class));
            assertEquals("target", config.getValue().database().orElseThrow());
            verify(session).close(); verify(driver).close();
        }
    }

    @Test void odpsUsesSdkSignedGetAndParsesRealProjectReloadWithoutSqlJobs() throws Exception {
        var authorization = new AtomicReference<String>(); var method = new AtomicReference<String>();
        var uri = new AtomicReference<String>(); var project = new AtomicReference<>("fixture_project");
        var server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod()); uri.set(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.getResponseHeaders().add("x-odps-owner", "fixture-owner");
            exchange.getResponseHeaders().add("x-odps-creation-time", "Mon, 19 Oct 2026 00:00:00 GMT");
            exchange.getResponseHeaders().add("Last-Modified", "Mon, 19 Oct 2026 00:00:00 GMT");
            respond(exchange, 200, "<Project><Name>" + project.get() + "</Name><Properties/></Project>");
        });
        try (var probes = new ConnectionProbes()) {
            var request = new ConnectionProbeRequest(ProductTypeEnum.ODPS, null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api", "fixture-ak", "fixture-secret",
                    null, null, Map.of("project", "fixture_project"), Duration.ofSeconds(5));
            new cn.iocoder.yudao.framework.dbswitch.product.odps.OdpsConnectionProbe().probe(request, () -> null);
            var result = probes.probe(request, new cn.iocoder.yudao.framework.dbswitch.product.odps.OdpsConnectionProbe(), () -> null);
            assertTrue(result.connectionSuccessful(), result.toString());
            assertEquals("GET", method.get()); assertEquals("/api/projects/fixture_project", uri.get());
            assertNotNull(authorization.get()); assertFalse(authorization.get().contains("fixture-secret"));
            project.set("wrong_project");
            assertFalse(probes.probe(request, new cn.iocoder.yudao.framework.dbswitch.product.odps.OdpsConnectionProbe(), () -> null).connectionSuccessful());
        } finally { server.stop(0); }
    }

    @Test void httpProbeRejectsCredentialUrlsAndUnboundedRedirects() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HttpConnectionProbeSupport.endpoint("http://user:secret@127.0.0.1"));
        var server = server(exchange -> { exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/private"); respond(exchange, 302, "{}"); });
        try (var probes = new ConnectionProbes()) {
            var request = request(ProductTypeEnum.MILVUS, "http://127.0.0.1:" + server.getAddress().getPort(), Map.of(), "token");
            assertFalse(probes.probe(request, new MilvusConnectionProbe(), () -> null).connectionSuccessful());
        } finally { server.stop(0); }
    }

    private static ConnectionProbeRequest request(ProductTypeEnum product, String endpoint, Map<String,String> props, String password) {
        return new ConnectionProbeRequest(product, null, null, endpoint, null, password, "target", null, props, Duration.ofSeconds(3));
    }
    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler); server.start(); return server;
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
        exchange.close();
    }
}
