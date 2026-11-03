package cn.iocoder.yudao.framework.etl.core.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EtlBoundedHttpTest {
    @Test void deadlineIncludesBodyAfterHeadersHaveArrived() throws Exception {
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
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow")).build();
            assertThrows(HttpTimeoutException.class, () -> EtlBoundedHttp.send(HttpClient.newHttpClient(), request, 1024, 600));
            assertEquals(0, headers.getCount(), "必须确实收到响应头和首字节后才触发整个响应体超时");
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void refusesOversizedBodyAndReturnsExactBoundedBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/body", exchange -> { try (exchange) { exchange.sendResponseHeaders(200, 5); exchange.getResponseBody().write(new byte[]{1, 2, 3, 4, 5}); } });
        server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/body")).build();
            var client = HttpClient.newHttpClient();
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, EtlBoundedHttp.send(client, request, 5, 3000).body());
            assertThrows(IOException.class, () -> EtlBoundedHttp.send(client, request, 4, 3000));
        } finally { server.stop(0); }
    }
}
