package cn.iocoder.yudao.framework.etl.core.executor.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EtlManagedHttpTest {
    @Test void explicitTimeoutBoundsTheEntireSlowResponseAndPreservesEndpointAuthorization() throws Exception {
        var release = new CountDownLatch(1); var headers = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('a'); exchange.getResponseBody().flush(); headers.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
            long started = System.nanoTime();
            assertThrows(IllegalArgumentException.class, () -> EtlManagedHttp.exchange(endpoint, Set.of(endpoint), "GET", Map.of(), Map.of(), new byte[0], 1024, 600));
            assertEquals(0, headers.getCount());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
            assertThrows(IllegalArgumentException.class, () -> EtlManagedHttp.exchange(endpoint, Set.of(), "GET", Map.of(), Map.of(), new byte[0], 1024, 600));
            assertThrows(IllegalArgumentException.class, () -> EtlManagedHttp.exchange(endpoint, Set.of(endpoint), "GET", Map.of(), Map.of(), new byte[0], 1024, 99));
        } finally { release.countDown(); server.stop(0); }
    }
}
