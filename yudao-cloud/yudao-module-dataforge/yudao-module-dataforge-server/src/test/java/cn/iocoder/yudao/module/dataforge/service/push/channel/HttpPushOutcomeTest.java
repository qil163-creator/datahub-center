package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.iocoder.yudao.module.dataforge.service.push.HttpPushUrlGuard;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HttpPushOutcomeTest {
    @Test void acceptedRequestWithServerErrorMustNotBeSentAgain() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            var channel = new HttpPushChannel();
            var guard = mock(HttpPushUrlGuard.class);
            when(guard.validate(anyString())).thenReturn(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/push"));
            ReflectionTestUtils.setField(channel, "urlGuard", guard);
            assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload("test", List.of(Map.of("id", 1))),
                    "{\"http\":{\"url\":\"https://example.invalid/push\",\"retryTimes\":2,\"retryIntervalMs\":1}}", new StringBuilder()));
            assertEquals(1, requests.get(), "远端可能已处理但响应失败，禁止在同次运行内重投");
        } finally { server.stop(0); }
    }
}
