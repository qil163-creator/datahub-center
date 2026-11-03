package cn.iocoder.yudao.framework.common.util.http;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.SocketFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.common.util.http.SafeHttpExecutor.FailureReason.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeHttpExecutorTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "0.8.1.2", "10.0.0.1", "100.64.0.1", "100.100.100.200",
            "100.127.255.254", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.254",
            "192.0.0.192", "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.19.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "168.63.129.16", "::", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1",
            "64:ff9b::a00:1", "2001::1", "2001:2::1", "2001:db8::1", "2002:a00:1::1", "3fff::1"})
    void rejectsNonPublicAddress(String value) throws Exception {
        assertFalse(SafeHttpExecutor.isPublicAddress(InetAddress.getByName(value)));
        AtomicInteger calls = new AtomicInteger();
        SafeHttpExecutor.SafeHttpException failure = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                () -> SafeHttpExecutor.execute(publicRequest("http://public.example/check"), host -> {
                    calls.incrementAndGet();
                    return List.of(InetAddress.getByName(value));
                }));
        assertEquals(TARGET_REJECTED, failure.reason());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.1.1", "8.8.8.8", "100.63.255.254", "100.128.0.1", "172.15.0.1",
            "172.32.0.1", "2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450:4000::1"})
    void recognizesPublicAddress(String value) throws Exception {
        assertTrue(SafeHttpExecutor.isPublicAddress(InetAddress.getByName(value)));
    }

    @Test
    void rejectsDnsAnswerContainingBothPublicAndPrivateAddresses() {
        SafeHttpExecutor.SafeHttpException failure = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                () -> SafeHttpExecutor.execute(publicRequest("http://public.example/check"), host -> List.of(
                        InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))));
        assertEquals(TARGET_REJECTED, failure.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/path", "http://localhost./path", "http://sub.localhost/path",
            "http://metadata.google.internal/path", "http://[::ffff:8.8.8.8]/path",
            "http://[::ffff:127.0.0.1]/path", "http://[0:0:0:0:0:ffff:8.8.8.8]/path", "http://user:secret@public.example/path",
            "http://public.example/path#fragment", "http://public.example/path#"})
    void rejectsUnsafeUrlBeforeResolving(String value) {
        AtomicInteger calls = new AtomicInteger();
        SafeHttpExecutor.SafeHttpException failure = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                () -> SafeHttpExecutor.execute(publicRequest(value), host -> {
                    calls.incrementAndGet();
                    return List.of(InetAddress.getByName("8.8.8.8"));
                }));
        assertEquals(TARGET_REJECTED, failure.reason());
        assertEquals(0, calls.get());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/test", "ftp://public.example/test", "http://public.example/a\\b",
            "http://public.example/a\r\nInjected: true", "http://public.example/a b"})
    void rejectsMalformedAndUnsupportedUrls(String value) {
        assertEquals(INVALID_REQUEST, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                () -> SafeHttpExecutor.execute(publicRequest(value))).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "Content-Length", "Transfer-Encoding", "Connection", "Proxy-Authorization",
            "Proxy-Anything", "tenant-id", "login-user", "visit-tenant-id", "data-permission-enable"})
    void rejectsTransportAndInternalIdentityHeaders(String name) {
        SafeHttpExecutor.Request request = new SafeHttpExecutor.Request("http://public.example/check", "GET",
                List.of(new SafeHttpExecutor.Header(name, "value")), null, 5, false, SafeHttpExecutor.publicOnly());
        assertEquals(INVALID_REQUEST, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                () -> SafeHttpExecutor.execute(request)).reason());
    }

    @Test
    void rejectsHeaderInjectionOversizedBodyAndUnsupportedMethods() {
        for (SafeHttpExecutor.Request request : List.of(
                request("http://public.example/check", "GET", List.of(new SafeHttpExecutor.Header("X-Test", "a\r\nb")),
                        null, 5, false, SafeHttpExecutor.publicOnly()),
                request("http://public.example/check", "POST", List.of(), new byte[SafeHttpExecutor.MAX_REQUEST_BYTES + 1],
                        5, false, SafeHttpExecutor.publicOnly()),
                request("http://public.example/check", "HEAD", List.of(), new byte[]{1}, 5, false, SafeHttpExecutor.publicOnly()),
                request("http://public.example/check", "GET", List.of(), new byte[]{1}, 5, false, SafeHttpExecutor.publicOnly()),
                request("http://public.example/check", "CONNECT", List.of(), null, 5, false, SafeHttpExecutor.publicOnly()),
                request("http://public.example/check", "GET", List.of(), null, 121, false, SafeHttpExecutor.publicOnly()))) {
            assertEquals(INVALID_REQUEST, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request)).reason());
        }
    }

    @Test
    void bindsConnectionToValidatedDnsAndCapturesRealResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(418).setBody("实际响应")
                    .addHeader("X-Test", "first").addHeader("X-Test", "second").addHeader("Set-Cookie", "session=response"));
            String url = "http://unresolvable.safe-http.invalid:" + server.getPort() + "/check?a=1&a=2";
            AtomicInteger lookups = new AtomicInteger();
            byte[] body = "request-body".getBytes(StandardCharsets.UTF_8);
            SafeHttpExecutor.Request request = request(url, "POST", List.of(
                    new SafeHttpExecutor.Header("Authorization", "Bearer explicitly-supplied"),
                    new SafeHttpExecutor.Header("Cookie", "session=explicitly-supplied"),
                    new SafeHttpExecutor.Header("Content-Type", "text/plain")), body, 5, false,
                    SafeHttpExecutor.fixedTarget(url));
            SafeHttpExecutor.Result result = SafeHttpExecutor.execute(request, host -> {
                // 后续解析若改变为其它地址，连接仍必须使用第一次校验结果。
                return List.of(InetAddress.getByName(lookups.incrementAndGet() == 1 ? "127.0.0.1" : "127.0.0.2"));
            });
            assertEquals(418, result.statusCode());
            assertEquals("实际响应", new String(result.body(), StandardCharsets.UTF_8));
            assertEquals(2, result.headers().stream().filter(h -> h.name().equalsIgnoreCase("X-Test")).count());
            assertTrue(result.headers().stream().anyMatch(h -> h.name().equalsIgnoreCase("Set-Cookie")));
            assertEquals(1, lookups.get());
            RecordedRequest captured = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(captured);
            assertEquals("POST", captured.getMethod());
            assertEquals("/check?a=1&a=2", captured.getPath());
            assertEquals("Bearer explicitly-supplied", captured.getHeader("Authorization"));
            assertEquals("session=explicitly-supplied", captured.getHeader("Cookie"));
            assertEquals("identity", captured.getHeader("Accept-Encoding"));
            assertEquals("request-body", captured.getBody().readUtf8());
            assertFalse(result.truncated());
        }
    }

    @Test
    void trustedTargetRejectsDifferentOriginAndPathWithoutSending() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = server.url("/allowed").toString();
            SafeHttpExecutor.TargetPolicy policy = SafeHttpExecutor.fixedTarget(url);
            for (String rejected : List.of(server.url("/admin-api").toString(),
                    "http://localhost:" + (server.getPort() + 1) + "/allowed")) {
                assertEquals(TARGET_REJECTED, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                        () -> SafeHttpExecutor.execute(request(rejected, "GET", List.of(), null, 5, false, policy))).reason());
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    void followsTrustedSamePathRedirectWithoutAdoptingResponseCookies() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(303).addHeader("Location", "/allowed?second=1")
                    .addHeader("Set-Cookie", "session=remote"));
            server.enqueue(new MockResponse().setBody("done"));
            String url = server.url("/allowed?first=1").toString();
            AtomicInteger lookups = new AtomicInteger();
            SafeHttpExecutor.Result result = SafeHttpExecutor.execute(request(url, "POST",
                    List.of(new SafeHttpExecutor.Header("Content-Type", "application/json")), new byte[]{1}, 5,
                    true, SafeHttpExecutor.fixedTarget(url)), host -> {
                lookups.incrementAndGet();
                return List.of(InetAddress.getByName("127.0.0.1"));
            });
            assertEquals(2, lookups.get());
            assertEquals(1, result.redirectChain().size());
            assertEquals(303, result.redirectChain().getFirst().statusCode());
            assertEquals(server.url("/allowed?second=1").toString(), result.url());
            assertEquals("POST", server.takeRequest().getMethod());
            RecordedRequest second = server.takeRequest();
            assertEquals("GET", second.getMethod());
            assertNull(second.getHeader("Cookie"));
            assertNull(second.getHeader("Content-Type"));
            assertEquals(0, second.getBodySize());
        }
    }

    @Test
    void redirectCannotEscapeTrustedPathOrOrigin() throws Exception {
        for (String target : List.of("/admin-api", "http://127.0.0.1:1/allowed", "http://user:secret@localhost/allowed",
                "/allowed#fragment", "file:///tmp/private")) {
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", target));
                String url = server.url("/allowed").toString();
                assertEquals(TARGET_REJECTED, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                        () -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5, true,
                                SafeHttpExecutor.fixedTarget(url)))).reason());
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    void stripsAllUserHeadersAcrossAnyOriginChange() {
        List<SafeHttpExecutor.Header> credentials = List.of(new SafeHttpExecutor.Header("Authorization", "secret"),
                new SafeHttpExecutor.Header("Cookie", "session=secret"), new SafeHttpExecutor.Header("X-Custom-Key", "secret"),
                new SafeHttpExecutor.Header("Content-Type", "application/json"));
        HttpUrl origin = HttpUrl.get("https://first.example/allowed");
        assertEquals(credentials, SafeHttpExecutor.redirectHeaders(credentials, origin,
                HttpUrl.get("https://first.example/next")));
        for (String changed : List.of("http://first.example/next", "https://second.example/next",
                "https://first.example:8443/next")) {
            assertTrue(SafeHttpExecutor.redirectHeaders(credentials, origin, HttpUrl.get(changed)).isEmpty());
        }
    }

    @Test
    void publicRedirectResolvesAgainAndRejectsPrivateDestinationBeforeConnecting() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = "http://first.safe-http.invalid:" + server.getPort() + "/allowed";
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location",
                    "http://second.safe-http.invalid:" + server.getPort() + "/allowed"));
            List<InetSocketAddress> connected = new ArrayList<>();
            AtomicInteger lookups = new AtomicInteger();
            SafeHttpExecutor.SafeHttpException failure = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5, true,
                            SafeHttpExecutor.publicOnly()), host -> {
                        lookups.incrementAndGet();
                        return List.of(InetAddress.getByName(host.startsWith("first") ? "8.8.8.8" : "127.0.0.1"));
                    }, localTestSockets(connected)));
            assertEquals(TARGET_REJECTED, failure.reason());
            assertEquals(2, lookups.get());
            assertEquals(1, connected.size());
            assertEquals("8.8.8.8", connected.getFirst().getAddress().getHostAddress());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void publicCrossOriginRedirectActuallyRemovesEveryUserHeader() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = "http://first.safe-http.invalid:" + server.getPort() + "/allowed";
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location",
                    "http://second.safe-http.invalid:" + server.getPort() + "/next"));
            server.enqueue(new MockResponse().setBody("done"));
            List<InetSocketAddress> connected = new ArrayList<>();
            AtomicInteger lookups = new AtomicInteger();
            SafeHttpExecutor.Result result = SafeHttpExecutor.execute(request(url, "GET", List.of(
                    new SafeHttpExecutor.Header("Authorization", "secret"),
                    new SafeHttpExecutor.Header("Cookie", "session=secret"),
                    new SafeHttpExecutor.Header("X-Custom-Key", "secret")), null, 5, true,
                    SafeHttpExecutor.publicOnly()), host -> {
                lookups.incrementAndGet();
                return List.of(InetAddress.getByName("8.8.8.8"));
            }, localTestSockets(connected));
            assertEquals(200, result.statusCode());
            assertEquals(2, lookups.get());
            assertEquals(2, connected.size());
            assertTrue(connected.stream().allMatch(endpoint -> endpoint.getAddress().getHostAddress().equals("8.8.8.8")));
            assertEquals("secret", server.takeRequest().getHeader("Authorization"));
            RecordedRequest second = server.takeRequest();
            assertNull(second.getHeader("Authorization"));
            assertNull(second.getHeader("Cookie"));
            assertNull(second.getHeader("X-Custom-Key"));
            assertEquals("second.safe-http.invalid:" + server.getPort(), second.getHeader("Host"));
        }
    }

    @Test
    void neverForwardsNonEmptyBodyAcrossOriginsUnlessRedirectDropsBody() throws Exception {
        for (int status : new int[]{301, 302, 307, 308}) {
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                String url = "http://first.safe-http.invalid:" + server.getPort() + "/allowed";
                server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location",
                        "http://second.safe-http.invalid:" + server.getPort() + "/next"));
                List<InetSocketAddress> connected = new ArrayList<>();
                assertEquals(TARGET_REJECTED, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                        () -> SafeHttpExecutor.execute(request(url, "PATCH", List.of(), new byte[]{1}, 5, true,
                                SafeHttpExecutor.publicOnly()), host -> List.of(InetAddress.getByName("8.8.8.8")),
                                localTestSockets(connected))).reason());
                assertEquals(1, connected.size());
                assertEquals(1, server.getRequestCount());
            }
        }
        for (int status : new int[]{301, 302, 303}) {
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                String url = "http://first.safe-http.invalid:" + server.getPort() + "/allowed";
                server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location",
                        "http://second.safe-http.invalid:" + server.getPort() + "/next"));
                server.enqueue(new MockResponse().setBody("done"));
                List<InetSocketAddress> connected = new ArrayList<>();
                SafeHttpExecutor.Result result = SafeHttpExecutor.execute(request(url, "POST", List.of(), new byte[]{1},
                        5, true, SafeHttpExecutor.publicOnly()), host -> List.of(InetAddress.getByName("8.8.8.8")),
                        localTestSockets(connected));
                assertEquals(200, result.statusCode());
                assertEquals("POST", server.takeRequest().getMethod());
                RecordedRequest next = server.takeRequest();
                assertEquals("GET", next.getMethod());
                assertEquals(0, next.getBodySize());
            }
        }
    }

    @Test
    void preservesRawRedirectWhenFollowingIsDisabledAndBoundsRedirects() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = server.url("/allowed").toString();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/admin-api").setBody("redirect"));
            SafeHttpExecutor.Result raw = SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5, false,
                    SafeHttpExecutor.fixedTarget(url)));
            assertEquals(302, raw.statusCode());
            assertEquals("redirect", new String(raw.body(), StandardCharsets.UTF_8));
            assertTrue(raw.redirectChain().isEmpty());
            for (int i = 0; i <= SafeHttpExecutor.MAX_REDIRECTS; i++) {
                server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/allowed?hop=" + i));
            }
            assertEquals(TOO_MANY_REDIRECTS, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5, true,
                            SafeHttpExecutor.fixedTarget(url)))).reason());
            assertEquals(SafeHttpExecutor.MAX_REDIRECTS + 2, server.getRequestCount());
        }
    }

    @Test
    void truncatesOnlyResponsesExceedingLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = server.url("/allowed").toString();
            for (int size : new int[]{SafeHttpExecutor.MAX_RESPONSE_BYTES, SafeHttpExecutor.MAX_RESPONSE_BYTES + 1}) {
                server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[size])));
                SafeHttpExecutor.Result result = SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5,
                        false, SafeHttpExecutor.fixedTarget(url)));
                assertEquals(SafeHttpExecutor.MAX_RESPONSE_BYTES, result.body().length);
                assertEquals(size > SafeHttpExecutor.MAX_RESPONSE_BYTES, result.truncated());
            }
        }
    }

    @Test
    void boundsDnsResolutionByWholeRequestDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        long started = System.nanoTime();
        try {
            assertEquals(TIMEOUT, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request("http://public.example/check", "GET", List.of(), null,
                            1, false, SafeHttpExecutor.publicOnly()), host -> {
                        // 模拟忽略中断的平台 DNS；调用方仍必须按截止时间返回。
                        while (release.getCount() != 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                            }
                        }
                        return List.of(InetAddress.getByName("8.8.8.8"));
                    })).reason());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
        } finally {
            release.countDown();
        }
    }

    @Test
    void limitsConcurrentExecutionsAndReleasesCapacityAfterCompletion() throws Exception {
        CountDownLatch releaseDns = new CountDownLatch(1);
        // 反射只用于等待全部请求已获执行资格，不修改信号量。
        var semaphoreField = SafeHttpExecutor.class.getDeclaredField("EXECUTIONS");
        semaphoreField.setAccessible(true);
        var permits = (java.util.concurrent.Semaphore) semaphoreField.get(null);
        try (MockWebServer server = new MockWebServer();
             var workers = java.util.concurrent.Executors.newFixedThreadPool(16)) {
            server.start();
            String url = server.url("/allowed").toString();
            for (int i = 0; i < 17; i++) {
                server.enqueue(new MockResponse().setBody("ok"));
            }
            List<java.util.concurrent.Future<SafeHttpExecutor.Result>> calls = new ArrayList<>();
            try {
                for (int i = 0; i < 16; i++) {
                    calls.add(workers.submit(() -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null,
                            10, false, SafeHttpExecutor.fixedTarget(url)), host -> {
                        // 暂停解析以同时占满执行资格，释放后仍发送真实 HTTP。
                        try {
                            releaseDns.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new java.net.UnknownHostException("测试 DNS 被中断");
                        }
                        return List.of(InetAddress.getLoopbackAddress());
                    })));
                }
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (permits.availablePermits() != 0 && System.nanoTime() < waitDeadline) {
                    Thread.sleep(5);
                }
                assertEquals(0, permits.availablePermits());
                assertEquals(CONCURRENCY_LIMIT, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                        () -> SafeHttpExecutor.execute(publicRequest("https://public.example/blocked"))).reason());
            } finally {
                releaseDns.countDown();
            }
            for (var call : calls) {
                assertEquals(200, call.get(5, TimeUnit.SECONDS).statusCode());
            }
            assertEquals(200, SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5,
                    false, SafeHttpExecutor.fixedTarget(url))).statusCode());
        }
    }

    @Test
    void timesOutNetworkAndWholeRedirectChainWithoutLeakingSecrets() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = server.url("/allowed?token=secret").toString();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            SafeHttpExecutor.SafeHttpException failure = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 1, false,
                            SafeHttpExecutor.fixedTarget(url))));
            assertEquals(TIMEOUT, failure.reason());
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = server.url("/allowed").toString();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/allowed?next=1")
                    .setHeadersDelay(650, TimeUnit.MILLISECONDS));
            server.enqueue(new MockResponse().setBody("too-late").setHeadersDelay(650, TimeUnit.MILLISECONDS));
            long started = System.nanoTime();
            assertEquals(TIMEOUT, assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 1, true,
                            SafeHttpExecutor.fixedTarget(url)))).reason());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2200);
        }
    }

    @Test
    void handlesDnsFailureAndDisconnectWithSanitizedErrors() throws Exception {
        SafeHttpExecutor.SafeHttpException dns = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                () -> SafeHttpExecutor.execute(publicRequest("http://public.example/path?token=secret"), host -> {
                    throw new java.net.UnknownHostException("secret");
                }));
        assertEquals(DNS_FAILED, dns.reason());
        assertNull(dns.getCause());
        assertFalse(dns.getMessage().contains("secret"));
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String url = server.url("/allowed").toString();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            SafeHttpExecutor.SafeHttpException failure = assertThrows(SafeHttpExecutor.SafeHttpException.class,
                    () -> SafeHttpExecutor.execute(request(url, "GET", List.of(), null, 5, false,
                            SafeHttpExecutor.fixedTarget(url))));
            assertEquals(TRANSPORT_FAILED, failure.reason());
            assertNull(failure.getCause());
        }
    }

    private static SafeHttpExecutor.Request publicRequest(String url) {
        return request(url, "GET", List.of(), null, 5, false, SafeHttpExecutor.publicOnly());
    }

    private static SocketFactory localTestSockets(List<InetSocketAddress> connected) {
        // 保留并断言客户端实际使用的公网 SocketAddress，只把测试传输路由到本机真实 HTTP 服务。
        return new SocketFactory() {
            @Override
            public Socket createSocket() {
                return new Socket() {
                    @Override
                    public void connect(SocketAddress endpoint, int timeout) throws IOException {
                        InetSocketAddress address = (InetSocketAddress) endpoint;
                        connected.add(address);
                        super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), address.getPort()), timeout);
                    }
                };
            }

            @Override
            public Socket createSocket(String host, int port) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress local, int localPort) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Socket createSocket(InetAddress host, int port) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static SafeHttpExecutor.Request request(String url, String method, List<SafeHttpExecutor.Header> headers,
                                                  byte[] body, int timeout, boolean redirects,
                                                  SafeHttpExecutor.TargetPolicy policy) {
        return new SafeHttpExecutor.Request(url, method, headers, body, timeout, redirects, policy);
    }
}
