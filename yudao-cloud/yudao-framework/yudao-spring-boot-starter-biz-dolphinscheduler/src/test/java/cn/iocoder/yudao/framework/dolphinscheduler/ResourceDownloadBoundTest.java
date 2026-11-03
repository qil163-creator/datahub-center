package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

class ResourceDownloadBoundTest {
    private HttpServer server;
    private DolphinSchedulerClient client;
    private DolphinSchedulerProperties properties;
    private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger redirected = new AtomicInteger();
    private boolean chunked;
    private boolean gzip;
    private volatile int dripMillis;
    private String query;
    private String token;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resources/download", exchange -> {
            requests.incrementAndGet();
            query = exchange.getRequestURI().getRawQuery();
            token = exchange.getRequestHeaders().getFirst("token");
            if (status.get() == 302) exchange.getResponseHeaders().add("Location", "/redirected");
            if (gzip) exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(status.get(), chunked ? 0 : body.get().length);
            try (var output = exchange.getResponseBody()) {
                if (dripMillis == 0) output.write(body.get());
                else for (byte value : body.get()) {
                    output.write(value); output.flush();
                    try { TimeUnit.MILLISECONDS.sleep(dripMillis); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            catch (IOException ignored) { /* Bound failures deliberately close the response early. */ }
        });
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, 1);
            exchange.getResponseBody().write(1);
            exchange.close();
        });
        server.start();
        properties = new DolphinSchedulerProperties();
        properties.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken("fixture-token");
        client = new DolphinSchedulerClient(properties);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void exactBoundUsesActualBytesAndCurrentFullNameRequestContract() {
        body.set("artifact bytes".getBytes(StandardCharsets.UTF_8));
        byte[] bytes = client.resource().downloadResource("file:////managed/fixture/resources/job.jar", body.get().length);
        assertThat(bytes).isEqualTo(body.get());
        assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo("fullName=file:////managed/fixture/resources/job.jar");
        assertThat(token).isEqualTo("fixture-token");
    }

    @Test void oversizedKnownLengthAndChunkedBodiesAreRejectedBeforeUnboundedBuffering() {
        body.set(new byte[50000]);
        assertThatThrownBy(() -> client.resource().downloadResource("/managed/job.jar", 31))
                .isInstanceOf(DolphinSchedulerException.class).hasMessageContaining("exceeds download limit");
        chunked = true;
        assertThatThrownBy(() -> client.resource().resourceSha256("/managed/job.jar", 31))
                .isInstanceOf(DolphinSchedulerException.class).hasMessageContaining("exceeds download limit");
    }

    @Test void checksumHashesActualStreamNotTheConfiguredArtifactLabel() throws Exception {
        body.set("first actual bytes".getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.get()));
        assertThat(client.resource().resourceSha256("/managed/job.jar", 500)).isEqualTo(expected);
        body.set("changed actual bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(client.resource().resourceSha256("/managed/job.jar", 500)).isNotEqualTo(expected);
        body.set(new byte[0]);
        assertThat(client.resource().resourceSha256("/managed/job.jar", 500))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0])));
    }

    @Test void redirectsNeverForwardAuthenticationAndHttpErrorsDoNotExposeResponseBodies() {
        body.set("sensitive error body".getBytes(StandardCharsets.UTF_8));
        status.set(302);
        assertThatThrownBy(() -> client.resource().resourceSha256("/managed/job.jar", 500))
                .isInstanceOf(DolphinSchedulerException.class).hasMessageNotContaining("sensitive");
        assertThat(redirected).hasValue(0);
        status.set(500);
        assertThatThrownBy(() -> client.resource().downloadResource("/managed/job.jar", 500))
                .isInstanceOf(DolphinSchedulerException.class).hasMessageNotContaining("sensitive");
    }

    @Test void invalidDownloadLimitsAndUntrustedNamesNeverSendARequest() {
        assertThatThrownBy(() -> client.resource().downloadResource("/managed/job.jar", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.resource().resourceSha256("/managed/job.jar", 1024L * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.resource().resourceSha256("/managed/../escape.jar", 500)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }

    @Test void compressedResponseLimitAppliesToDecodedBytes() throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var stream = new GZIPOutputStream(compressed)) { stream.write(new byte[50000]); }
        body.set(compressed.toByteArray());
        gzip = true;
        chunked = true;
        assertThatThrownBy(() -> client.resource().resourceSha256("/managed/job.jar", 300))
                .isInstanceOf(DolphinSchedulerException.class).hasMessageContaining("exceeds download limit");
    }

    @Test void completeDeadlineRejectsDrippedBodiesForDownloadAndChecksum() {
        properties.setTimeoutMillis(350);
        body.set(new byte[80]);
        chunked = true;
        dripMillis = 35; // Every chunk arrives well within the socket timeout; the complete body takes 2.8 seconds.
        for (boolean checksum : new boolean[]{false, true}) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> {
                if (checksum) client.resource().resourceSha256("/managed/job.jar", 500);
                else client.resource().downloadResource("/managed/job.jar", 500);
            }).isInstanceOf(DolphinSchedulerException.class).hasMessageContaining("deadline exceeded");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsed).isBetween(300L, 1500L);
        }
    }
}
