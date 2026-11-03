package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpConnectionProbeSupportTest {
    @Test
    void classifiesAuthenticationAuthorizationAndMissingTargetWithoutResponseBody() throws Exception {
        var statuses = Map.of(401, ConnectionProbeException.Reason.AUTHENTICATION,
                403, ConnectionProbeException.Reason.AUTHORIZATION,
                404, ConnectionProbeException.Reason.TARGET_NOT_FOUND);
        for (var status : statuses.entrySet()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = "sensitive-server-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.getKey(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            try {
                var request = new ConnectionProbeRequest(ProductTypeEnum.INFLUXDB2, null, null,
                        "http://127.0.0.1:" + server.getAddress().getPort(), null, null,
                        null, null, Map.of(), Duration.ofSeconds(2));
                var failure = assertThrows(ConnectionProbeException.class,
                        () -> HttpConnectionProbeSupport.json(request, "/", "GET", null, null));
                assertEquals(status.getValue(), failure.reason());
                assertFalse(failure.toString().contains("sensitive-server-response"));
                assertNull(failure.getCause());
            } finally { server.stop(0); }
        }
    }

    @Test
    void rejectsRedirectsAndUnexpectedStatusesWithoutCallingThemSuccessful() throws Exception {
        HttpConnectionProbeSupport.requireSuccess(200);
        for (int status : new int[]{201, 204, 301, 302, 429, 500}) {
            assertThrows(IOException.class, () -> HttpConnectionProbeSupport.requireSuccess(status));
        }
    }
}
