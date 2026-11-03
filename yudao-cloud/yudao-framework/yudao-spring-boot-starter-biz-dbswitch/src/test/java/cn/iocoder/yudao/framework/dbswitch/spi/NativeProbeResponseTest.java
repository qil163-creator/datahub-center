package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb2.Influxdb2ConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.product.milvus.MilvusConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.product.odps.OdpsConnectionProbe;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 使用协议夹具验证分支与错误分类；真实产品验证另见显式启用的原生测试。 */
class NativeProbeResponseTest {
    @Test
    void influxOrganizationIdFallsBackAfterNameNotFoundAndVerifiesBucketOwner() throws Exception {
        var queries = new ArrayList<String>();
        String id = "1234567890abcdef";
        var bucketOrg = new AtomicReference<>(id);
        var server = server(exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            if (exchange.getRequestURI().getPath().endsWith("/orgs")) reply(exchange, 404, "{}");
            else reply(exchange, 200, "{\"buckets\":[{\"name\":\"bucket\",\"orgID\":\"" + bucketOrg.get() + "\"}]}");
        });
        try {
            var request = request(server, ProductTypeEnum.INFLUXDB2, Map.of("org", id, "bucket", "bucket"));
            new Influxdb2ConnectionProbe().probe(request, () -> null);
            assertEquals(List.of("org=" + id, "orgID=" + id + "&name=bucket"), queries);
            bucketOrg.set("other");
            assertEquals(ConnectionProbeException.Reason.TARGET_NOT_FOUND,
                    assertThrows(ConnectionProbeException.class, () -> new Influxdb2ConnectionProbe().probe(request, () -> null)).reason());
        } finally { server.stop(0); }
    }

    @Test
    void influxRestrictedTokenCanConnectByOrganizationIdWithoutOrgListPermission() throws Exception {
        String id = "1234567890abcdef";
        var server = server(exchange -> reply(exchange, 200,
                exchange.getRequestURI().getPath().endsWith("/orgs") ? "{\"orgs\":[]}"
                        : "{\"buckets\":[{\"name\":\"bucket\",\"orgID\":\"" + id + "\"}]}"));
        try {
            new Influxdb2ConnectionProbe().probe(request(server, ProductTypeEnum.INFLUXDB2,
                    Map.of("org", id, "bucket", "bucket")), () -> null);
        } finally { server.stop(0); }
    }

    @Test
    void influxHexOrganizationNameTakesPrecedenceOverAnOrganizationId() throws Exception {
        String hexName = "1234567890abcdef";
        var server = server(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/orgs")) {
                reply(exchange, 200, "{\"orgs\":[{\"name\":\"" + hexName + "\",\"id\":\"real-id\"}]}");
            } else {
                assertEquals("orgID=real-id&name=bucket", exchange.getRequestURI().getRawQuery());
                reply(exchange, 200, "{\"buckets\":[{\"name\":\"bucket\",\"orgID\":\"real-id\"}]}");
            }
        });
        try {
            new Influxdb2ConnectionProbe().probe(request(server, ProductTypeEnum.INFLUXDB2,
                    Map.of("org", hexName, "bucket", "bucket")), () -> null);
        } finally { server.stop(0); }
    }

    @Test
    void influxDoesNotFallbackAfterAuthenticationFailure() throws Exception {
        var paths = new ArrayList<String>();
        var server = server(exchange -> { paths.add(exchange.getRequestURI().getPath()); reply(exchange, 401, "{}"); });
        try {
            var request = request(server, ProductTypeEnum.INFLUXDB2, Map.of("org", "1234567890abcdef", "bucket", "bucket"));
            assertEquals(ConnectionProbeException.Reason.AUTHENTICATION,
                    assertThrows(ConnectionProbeException.class, () -> new Influxdb2ConnectionProbe().probe(request, () -> null)).reason());
            assertEquals(List.of("/api/v2/orgs"), paths);
        } finally { server.stop(0); }
    }

    @Test
    void milvusDoesNotTruncateAnOverflowBusinessCodeIntoSuccess() throws Exception {
        var server = server(exchange -> reply(exchange, 200, "{\"code\":4294967296,\"data\":[]}"));
        try {
            var request = request(server, ProductTypeEnum.MILVUS, Map.of());
            assertThrows(IOException.class, () -> new MilvusConnectionProbe().probe(request, () -> null));
        } finally { server.stop(0); }
    }

    @Test
    void odpsPreservesSafeAuthenticationAndAuthorizationCategoriesThroughSdkTransport() throws Exception {
        var expected = Map.of(401, ConnectionProbeException.Reason.AUTHENTICATION,
                403, ConnectionProbeException.Reason.AUTHORIZATION, 404, ConnectionProbeException.Reason.TARGET_NOT_FOUND);
        for (var status : expected.entrySet()) {
            var server = server(exchange -> reply(exchange, status.getKey(), "sensitive-response"));
            try {
                var request = request(server, ProductTypeEnum.ODPS, Map.of("project", "fixture_project"));
                var failure = assertThrows(ConnectionProbeException.class, () -> new OdpsConnectionProbe().probe(request, () -> null));
                assertEquals(status.getValue(), failure.reason());
                assertFalse(failure.toString().contains("sensitive-response"));
            } finally { server.stop(0); }
        }
    }

    private static ConnectionProbeRequest request(HttpServer server, ProductTypeEnum product, Map<String, String> properties) {
        return new ConnectionProbeRequest(product, null, null, "http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-user", "fixture-secret", "fixture-database", null, properties, Duration.ofSeconds(2));
    }

    private static HttpServer server(HttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
