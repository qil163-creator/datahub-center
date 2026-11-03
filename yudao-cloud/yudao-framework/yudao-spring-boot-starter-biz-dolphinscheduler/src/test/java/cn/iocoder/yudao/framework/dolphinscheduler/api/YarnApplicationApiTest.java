package cn.iocoder.yudao.framework.dolphinscheduler.api;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YarnApplicationApiTest {

    private static final String ID = "application_1720000000000_0001";
    @TempDir
    static Path temporary;
    private static SSLContext mutualTls;

    @BeforeAll
    static void certificates() throws Exception {
        Path storePath = temporary.resolve("observation.p12");
        String randomPassword = UUID.randomUUID().toString();
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        Process generator = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool" + suffix).toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", storePath.toString(), "-storepass", randomPassword, "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!generator.waitFor(30, TimeUnit.SECONDS)) {
            generator.destroyForcibly();
            fail("Temporary test certificate generation timed out");
        }
        assertEquals(0, generator.exitValue(), "Temporary test certificate generation failed");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(storePath)) {
            store.load(stream, randomPassword.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, randomPassword.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        mutualTls = SSLContext.getInstance("TLS");
        mutualTls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    }

    @Test
    void observesAuthenticatedExactApplicationWithoutReturningDiagnostics() throws Exception {
        try (Fixture fixture = new Fixture(200, observation("KILLED", "KILLED"), false, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            var application = client.query(ID);
            assertEquals(ID, application.id());
            assertEquals("tenant_worker", application.user());
            assertEquals("root.analytics", application.queue());
            assertEquals("dh_native_1_200", application.name());
            assertTrue(application.terminal());
            assertFalse(application.toString().contains("sensitive-output"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test
    void runningIsNotTerminal() throws Exception {
        try (Fixture fixture = new Fixture(200, observation("RUNNING", "UNDEFINED"), false, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            assertFalse(client.query(ID).terminal());
        }
    }

    @Test
    void rejectsConflictingStateAndMissingIdentity() {
        assertThrows(IllegalStateException.class, () -> YarnApplicationApi.parse(ID,
                observation("RUNNING", "SUCCEEDED").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> YarnApplicationApi.parse(ID,
                observation("KILLED", "SUCCEEDED").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> YarnApplicationApi.parse(ID,
                observation("FINISHED", "UNDEFINED").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> YarnApplicationApi.parse(ID,
                observation("FINISHED", "SUCCEEDED").replace("tenant_worker", "")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> YarnApplicationApi.parse("application_1_2",
                observation("FINISHED", "SUCCEEDED").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsNonHttpsAndUntrustedEndpointSyntax() {
        for (String endpoint : new String[]{"http://localhost", "https://user:secret@localhost", "https://localhost/?x=1",
                "https://localhost/#x", "https://localhost/a/../b", "https://localhost/%2e%2e/other",
                "https://localhost/a//b"}) {
            assertThrows(IllegalArgumentException.class, () -> YarnApplicationApi.validateEndpoint(URI.create(endpoint)));
        }
        assertEquals(URI.create("https://localhost/proxy"),
                YarnApplicationApi.validateEndpoint(URI.create("https://localhost/proxy")));
    }

    @Test
    void rejectsApplicationPathInjectionBeforeNetworkCall() throws Exception {
        try (Fixture fixture = new Fixture(200, observation("FINISHED", "SUCCEEDED"), false, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            for (String id : new String[]{"../application_1_1", "application_1_1?user.name=admin", "application_1_1/kill"}) {
                assertThrows(IllegalArgumentException.class, () -> client.query(id));
            }
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test
    void redirectsAndServerErrorsAreNeverTerminalEvidence() throws Exception {
        for (int status : new int[]{302, 404, 500}) {
            try (Fixture fixture = new Fixture(status, "sensitive-output", false, false);
                 YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
                var failure = assertThrows(IllegalStateException.class, () -> client.query(ID));
                assertFalse(failure.toString().contains("sensitive-output"));
                assertNull(failure.getCause());
                assertEquals(1, fixture.calls.get());
            }
        }
    }

    @Test
    void boundsChunkedResponseWithoutContentLength() throws Exception {
        try (Fixture fixture = new Fixture(200, "x".repeat(1_048_577), true, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            assertThrows(IllegalStateException.class, () -> client.query(ID));
        }
    }

    @Test
    void compressedResponseIsNotParsedAsTrustedJson() throws Exception {
        try (Fixture fixture = new Fixture(200, observation("FINISHED", "SUCCEEDED"), false, true);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            assertThrows(IllegalStateException.class, () -> client.query(ID));
        }
    }

    @Test
    void deadlineCoversSlowBodyAfterHeadersAndDisposesTheClient() throws Exception {
        try (Fixture fixture = new Fixture(200, observation("FINISHED", "SUCCEEDED"), false, false, true)) {
            long started = System.nanoTime();
            try (YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
                var failure = assertThrows(IllegalStateException.class, () -> client.query(ID));
                assertNull(failure.getCause());
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed >= 9_000 && elapsed < 15_000, "Complete body deadline must bound observation and close");
        }
    }

    @Test
    void findsExactNamesAndDoesNotTreatEmptyDiscoveryAsTermination() throws Exception {
        String item = observation("KILLED", "KILLED").substring(7);
        item = item.substring(0, item.length() - 1);
        try (Fixture fixture = new Fixture(200, "{\"apps\":{\"app\":[" + item + "]}}", false, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            var results = client.find("dh_native_1_200", "tenant_worker");
            assertEquals(1, results.size());
            assertEquals(ID, results.getFirst().id());
            assertTrue(results.getFirst().terminal());
        }
        try (Fixture fixture = new Fixture(200, "{\"apps\":null}", false, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            assertTrue(client.find("dh_native_1_200", "tenant_worker").isEmpty());
        }
    }

    @Test
    void filteredDiscoveryRejectsSpoofedIdentityAndTruncatedResults() throws Exception {
        String item = observation("KILLED", "KILLED").substring(7);
        item = item.substring(0, item.length() - 1);
        for (String body : new String[]{"{\"apps\":{\"app\":[" + item.replace("tenant_worker", "other_user") + "]}}",
                "{\"apps\":{\"app\":[" + item.replace("dh_native_1_200", "other_run") + "]}}",
                "{\"apps\":{\"app\":[" + String.join(",", java.util.Collections.nCopies(65, item)) + "]}}",
                "{\"apps\":{\"app\":[" + item + "," + item + "]}}"}) {
            try (Fixture fixture = new Fixture(200, body, false, false);
                 YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
                assertThrows(IllegalStateException.class, () -> client.find("dh_native_1_200", "tenant_worker"));
            }
        }
    }

    @Test
    void discoveryRejectsQueryInjectionBeforeNetworkCall() throws Exception {
        try (Fixture fixture = new Fixture(200, "{\"apps\":null}", false, false);
             YarnApplicationApi client = new YarnApplicationApi(fixture.endpoint(), mutualTls)) {
            assertThrows(IllegalArgumentException.class, () -> client.find("dh_native_1_200&user=root", "tenant_worker"));
            assertThrows(IllegalArgumentException.class, () -> client.find("dh_native_1_200", "tenant_worker&limit=1"));
            assertEquals(0, fixture.calls.get());
        }
    }

    private static String observation(String state, String finalStatus) {
        return "{\"app\":{\"id\":\"" + ID + "\",\"user\":\"tenant_worker\",\"queue\":\"root.analytics\","
                + "\"name\":\"dh_native_1_200\",\"state\":\"" + state + "\",\"finalStatus\":\""
                + finalStatus + "\",\"diagnostics\":\"sensitive-output\"}}";
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpsServer server;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch releaseBody = new CountDownLatch(1);

        private Fixture(int status, String body, boolean chunked, boolean compressed) throws Exception {
            this(status, body, chunked, compressed, false);
        }

        private Fixture(int status, String body, boolean chunked, boolean compressed, boolean slowBody) throws Exception {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(mutualTls) {
                @Override
                public void configure(HttpsParameters parameters) {
                    var settings = getSSLContext().getDefaultSSLParameters();
                    settings.setNeedClientAuth(true);
                    parameters.setSSLParameters(settings);
                }
            });
            server.createContext("/proxy/ws/v1/cluster/apps", exchange -> {
                calls.incrementAndGet();
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
                if (exchange.getRequestURI().getPath().equals("/proxy/ws/v1/cluster/apps")) {
                    assertEquals("name=dh_native_1_200&user=tenant_worker&limit=65", exchange.getRequestURI().getRawQuery());
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
                if (compressed) {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                }
                if (status == 302) {
                    exchange.getResponseHeaders().set("Location", endpoint() + "/redirect");
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                try (var output = exchange.getResponseBody()) {
                    if (slowBody) {
                        output.write(bytes, 0, 1);
                        output.flush();
                        try {
                            releaseBody.await(25, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        output.write(bytes, 1, bytes.length - 1);
                        return;
                    }
                    output.write(bytes);
                }
            });
            server.start();
        }

        private URI endpoint() {
            return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/proxy");
        }

        @Override
        public void close() {
            releaseBody.countDown();
            server.stop(0);
        }
    }
}
