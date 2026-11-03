package cn.iocoder.yudao.framework.dbswitch.product.odps;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.aliyun.odps.commons.transport.Request;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OdpsCatalogMetadataTest {
    private static final String ENDPOINT = "https://odps.fixture.test/api";
    private static final String TABLE = """
            <Table><Name>measurements</Name><Schema><![CDATA[{
              "comment":"测量值", "columns":[
                {"name":"id","type":"bigint","comment":"编号","isNullable":false},
                {"name":"amount","type":"decimal(30,0)","comment":"精确值"},
                {"name":"name","type":"varchar(80)","isNullable":true}],
              "partitionKeys":[{"name":"ds","type":"string","comment":"分区"}]
            }]]></Schema></Table>
            """;

    private static ConnectionProbeRequest request() {
        return new ConnectionProbeRequest(ProductTypeEnum.ODPS, null, null, ENDPOINT,
                "fixture-access-id", "fixture-secret", "fixture_project", null,
                Map.of("project", "fixture_project"), Duration.ofSeconds(2));
    }

    @Test void officialSdkListsAllCatalogPagesWithoutTableReloadSqlOrTunnel() {
        var fixture = new Fixture();
        fixture.add(200, "<Tables><Table><Name>measurements</Name></Table><Marker>next-page</Marker></Tables>");
        fixture.add(200, "<Tables><Table><Name>archive</Name></Table><Marker></Marker></Tables>");
        var result = new OdpsCatalogMetadata(fixture).tables(request());
        assertEquals(List.of("measurements", "archive"), result.stream().map(item -> item.name()).toList());
        assertNull(result.getFirst().view());
        assertEquals(2, fixture.uris.size());
        assertTrue(fixture.uris.stream().allMatch(uri -> uri.getPath().equals("/api/projects/fixture_project/tables")));
        assertTrue(fixture.uris.get(1).getQuery().contains("marker=next-page"));
        fixture.assertReadOnlyClosed();
    }

    @Test void officialSdkDescribesOrderedNativeColumnsAndKeepsUnknownConstraintsNull() {
        var fixture = new Fixture(); fixture.add(200, TABLE);
        var result = new OdpsCatalogMetadata(fixture).describe(request(), "measurements");
        assertEquals("测量值", result.table().comment());
        assertEquals(List.of("id", "amount", "name", "ds"), result.columns().stream().map(item -> item.name()).toList());
        assertEquals("BIGINT", result.columns().getFirst().nativeType());
        assertEquals(Boolean.FALSE, result.columns().getFirst().nullable());
        assertNull(result.columns().getFirst().length());
        assertEquals("DECIMAL(30,0)", result.columns().get(1).nativeType());
        assertEquals(30, result.columns().get(1).precision());
        assertEquals(0, result.columns().get(1).scale());
        assertNull(result.columns().get(1).nullable());
        assertEquals(80, result.columns().get(2).length());
        assertTrue(result.columns().get(3).partition());
        assertEquals(1, fixture.uris.size());
        assertEquals("/api/projects/fixture_project/tables/measurements", fixture.uris.getFirst().getPath());
        fixture.assertReadOnlyClosed();
    }

    @Test void invalidIdentifiersTlsAndForeignProductsNeverOpenTransport() {
        var fixture = new Fixture(); var catalog = new OdpsCatalogMetadata(fixture);
        for (String table : List.of("../other", "project.table", "table?query", "a%2Fb", ""))
            assertThrows(IllegalArgumentException.class, () -> catalog.describe(request(), table));
        var plain = new ConnectionProbeRequest(ProductTypeEnum.ODPS, null, null, "http://odps.fixture.test", "id", "secret", null, null,
                Map.of("project", "fixture_project"), Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> catalog.tables(plain));
        var foreign = new ConnectionProbeRequest(ProductTypeEnum.MYSQL, null, null, ENDPOINT, "id", "secret", null, null,
                Map.of("project", "fixture_project"), Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> catalog.tables(foreign));
        assertTrue(fixture.uris.isEmpty());
    }

    @Test void unauthorizedRedirectOversizedMalformedAndEntityResponsesHaveSafeErrors() {
        for (var response : List.of(new Reply(403, "private-vendor-body fixture-secret"), new Reply(302, "redirect"),
                new Reply(200, "x".repeat(65537)), new Reply(200, "not xml"),
                new Reply(200, "<!DOCTYPE Tables [<!ENTITY x SYSTEM 'file:///private'>]><Tables/>"))) {
            var fixture = new Fixture(); fixture.replies.add(response);
            var error = assertThrows(IllegalStateException.class, () -> new OdpsCatalogMetadata(fixture).tables(request()));
            assertFalse(error.toString().contains("fixture-secret"));
            assertFalse(error.toString().contains("private-vendor-body"));
            assertNull(error.getCause());
            fixture.assertReadOnlyClosed();
        }
    }

    @Test void duplicatePagesAndUnexpectedReturnedIdentityAreRejected() {
        var duplicate = new Fixture();
        duplicate.add(200, "<Tables><Table><Name>same</Name></Table><Table><Name>same</Name></Table></Tables>");
        assertThrows(IllegalStateException.class, () -> new OdpsCatalogMetadata(duplicate).tables(request()));
        var foreign = new Fixture(); foreign.add(200, TABLE.replace("<Name>measurements</Name>", "<Name>other</Name>"));
        assertThrows(IllegalStateException.class, () -> new OdpsCatalogMetadata(foreign).describe(request(), "measurements"));
    }

    @Test void transportRejectsOriginPathQueryMethodBodyAndStreamingBeforeOpening() throws Exception {
        var fixture = new Fixture();
        var transport = new OdpsReadTransport(URI.create(ENDPOINT), Duration.ofSeconds(1),
                Set.of("/api/projects/fixture_project/tables"), Set.of("marker"), fixture);
        for (String url : List.of("https://foreign.fixture.test/api/projects/fixture_project/tables", ENDPOINT + "/instances",
                ENDPOINT + "/projects/other/tables", ENDPOINT + "/projects/fixture_project/tables?sql=SELECT",
                ENDPOINT + "/projects/fixture_project/tables?curr_project=foreign_project",
                ENDPOINT + "/projects/fixture_project/tables?marker=a&marker=b", ENDPOINT + "/projects/fixture_project/tables#fragment")) {
            var req = httpRequest(url);
            assertThrows(IOException.class, () -> transport.request(req));
        }
        var write = httpRequest(ENDPOINT + "/projects/fixture_project/tables"); write.setMethod(Request.Method.POST);
        assertThrows(IOException.class, () -> transport.request(write));
        var body = httpRequest(ENDPOINT + "/projects/fixture_project/tables"); body.setBody(InputStream.nullInputStream());
        assertThrows(IOException.class, () -> transport.request(body));
        assertThrows(IOException.class, () -> transport.connect(body));
        assertThrows(UnsupportedOperationException.class, () -> transport.setProxy(java.net.Proxy.NO_PROXY));
        assertTrue(fixture.uris.isEmpty());
    }

    @Test void transportHonorsCancellationAndClosesWhenReadingFails() {
        var fixture = new Fixture();
        var transport = new OdpsReadTransport(URI.create(ENDPOINT), Duration.ofSeconds(1), Set.of("/api/catalog"), Set.of(), fixture);
        Thread.currentThread().interrupt();
        try { assertThrows(java.net.SocketTimeoutException.class, () -> transport.request(httpRequest(ENDPOINT + "/catalog"))); }
        finally { Thread.interrupted(); }
        assertTrue(fixture.uris.isEmpty());
        fixture.add(200, "x".repeat(65537));
        assertThrows(IOException.class, () -> transport.request(httpRequest(ENDPOINT + "/catalog")));
        fixture.assertReadOnlyClosed();
    }

    @Test void trailingEndpointSlashUsesOneCanonicalCatalogPath() {
        var fixture = new Fixture(); fixture.add(200, "<Tables><Marker></Marker></Tables>");
        var original = request();
        var trailing = new ConnectionProbeRequest(original.product(), null, null, ENDPOINT + "/", original.username(),
                original.password(), original.database(), null, original.properties(), original.timeout());
        assertTrue(new OdpsCatalogMetadata(fixture).tables(trailing).isEmpty());
        assertEquals("/api/projects/fixture_project/tables", fixture.uris.getFirst().getPath());
        fixture.assertReadOnlyClosed();
    }

    @Test void catalogCannotPerformUnboundedPaginationRequests() throws Exception {
        var fixture = new Fixture();
        var transport = new OdpsReadTransport(URI.create(ENDPOINT), Duration.ofSeconds(2), Set.of("/api/catalog"), Set.of(), fixture);
        for (int index = 0; index < 100; index++) {
            fixture.add(200, "OK");
            assertEquals(2, transport.request(httpRequest(ENDPOINT + "/catalog")).getBody().length);
        }
        assertThrows(IOException.class, () -> transport.request(httpRequest(ENDPOINT + "/catalog")));
        assertEquals(100, fixture.uris.size());
        fixture.assertReadOnlyClosed();
    }

    @Test void transportClosesConnectionWhenHeaderConfigurationFails() throws Exception {
        FakeConnection connection = new FakeConnection(URI.create(ENDPOINT), new Reply(200, "OK")) {
            @Override public void setRequestProperty(String key, String value) { throw new IllegalArgumentException("invalid header"); }
        };
        var transport = new OdpsReadTransport(URI.create(ENDPOINT), Duration.ofSeconds(1), Set.of("/api/catalog"), Set.of(), uri -> connection);
        var request = httpRequest(ENDPOINT + "/catalog"); request.setHeader("X-Fixture", "value");
        assertThrows(IllegalArgumentException.class, () -> transport.request(request));
        assertTrue(connection.disconnected);
    }

    private static Request httpRequest(String uri) {
        var request = new Request(); request.setURI(URI.create(uri)); request.setMethod(Request.Method.GET); return request;
    }

    private record Reply(int status, String body) { }

    private static final class Fixture implements OdpsReadTransport.Opener {
        private final ArrayDeque<Reply> replies = new ArrayDeque<>();
        private final List<URI> uris = new ArrayList<>();
        private final List<FakeConnection> connections = new ArrayList<>();
        void add(int status, String body) { replies.add(new Reply(status, body)); }
        public HttpURLConnection open(URI uri) throws IOException {
            uris.add(uri);
            var connection = new FakeConnection(uri, replies.removeFirst()); connections.add(connection); return connection;
        }
        void assertReadOnlyClosed() {
            assertFalse(connections.isEmpty());
            for (var connection : connections) {
                assertEquals("GET", connection.getRequestMethod());
                assertFalse(connection.getInstanceFollowRedirects());
                assertTrue(connection.getConnectTimeout() > 0 && connection.getConnectTimeout() <= 2000);
                assertTrue(connection.disconnected);
            }
        }
    }

    private static class FakeConnection extends HttpURLConnection {
        private final Reply reply;
        private boolean disconnected;
        FakeConnection(URI uri, Reply reply) throws IOException { super(uri.toURL()); this.reply = reply; }
        @Override public int getResponseCode() { return reply.status(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(reply.body().getBytes(StandardCharsets.UTF_8)); }
        @Override public Map<String, List<String>> getHeaderFields() { return Map.of("Content-Type", List.of("application/xml")); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
