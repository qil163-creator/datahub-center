package cn.iocoder.yudao.framework.etl.core.executor.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EtlFileInputDeadlineTest {
    @TempDir Path directory;

    @AfterEach void watchdogHasActuallyExited() {
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("etl-file-deadline")),
                "Each download must release its watchdog, including timeout and interrupt paths");
    }

    @Test void maximumLongByteBudgetDoesNotOverflowTheReadLength() throws Exception {
        try (var input = EtlFileInput.bounded(new java.io.ByteArrayInputStream(new byte[]{1, 2}), Long.MAX_VALUE)) {
            var bytes = new byte[8];
            assertEquals(2, input.read(bytes, 1, 7));
            assertEquals(1, bytes[1]); assertEquals(2, bytes[2]);
            assertEquals(-1, input.read(bytes, 0, bytes.length));
        }
    }

    @Test void zeroLengthReadDoesNotConsumeOrProbePastTheBudget() throws Exception {
        try (var input = EtlFileInput.bounded(new java.io.ByteArrayInputStream(new byte[]{1, 2}), 1)) {
            var bytes = new byte[1];
            assertEquals(0, input.read(bytes, 1, 0));
            assertEquals(1, input.read());
            assertEquals(0, input.read(bytes, 1, 0));
            assertThrows(IOException.class, input::read);
        }
        try (var empty = EtlFileInput.bounded(new java.io.ByteArrayInputStream(new byte[0]), 1)) {
            assertEquals(0, empty.read(new byte[0], 0, 0));
            assertEquals(-1, empty.read());
        }
    }

    @Test void callerBufferBoundsAreCheckedBeforeBudgetClamping() throws Exception {
        try (var input = EtlFileInput.bounded(new java.io.ByteArrayInputStream(new byte[]{7}), 1)) {
            var bytes = new byte[3];
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(bytes, 0, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(bytes, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(bytes, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(bytes, 4, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(bytes, Integer.MAX_VALUE, Integer.MAX_VALUE));
            assertThrows(NullPointerException.class, () -> input.read(null, 0, 0));
            assertEquals(7, input.read(), "Invalid calls must not read data or consume the budget");
        }
    }

    @Test void headersAndPartialBodyCannotHideAStalledDownload() throws Exception {
        var release = new CountDownLatch(1);
        var bodySent = new CountDownLatch(1);
        try (var server = new Fixture(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('x'); exchange.getResponseBody().flush(); bodySent.countDown();
            release.await(5, TimeUnit.SECONDS);
        }, release)) {
            var target = directory.resolve("stalled.csv");
            assertTimeout(Duration.ofSeconds(4), () -> {
                var failure = assertThrows(IOException.class, () -> EtlFileInput.download(
                        server.url(), target, 1024, Duration.ofMillis(900)));
                assertEquals("ETL input download exceeded its deadline", failure.getMessage());
            });
            assertEquals(0, bodySent.getCount(), "Must exercise the body after headers, not a connect timeout");
            assertEquals("x", Files.readString(target));
            Files.delete(target); // Both file output and body have been released when download returns.
        }
    }

    @Test void regularSmallChunksDoNotResetTheAbsoluteDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var server = new Fixture(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            for (int i = 0; i < 200 && release.getCount() > 0; i++) {
                exchange.getResponseBody().write('x'); exchange.getResponseBody().flush(); writes.incrementAndGet();
                if (release.await(25, TimeUnit.MILLISECONDS)) break;
            }
        }, release)) {
            var target = directory.resolve("dripping.csv");
            assertTimeout(Duration.ofSeconds(4), () -> {
                var failure = assertThrows(IOException.class, () -> EtlFileInput.download(
                        server.url(), target, 1024, Duration.ofMillis(900)));
                assertEquals("ETL input download exceeded its deadline", failure.getMessage());
            });
            assertTrue(writes.get() >= 3, "The server must send repeated data before the fixed deadline");
            assertTrue(Files.size(target) > 1);
            Files.delete(target);
        }
    }

    @Test void completeBodyAtTheExactByteLimitSucceeds() throws Exception {
        try (var server = fixed("ordinary-data")) {
            var target = directory.resolve("normal.csv");
            EtlFileInput.download(server.url(), target, 13, Duration.ofSeconds(3));
            assertEquals("ordinary-data", Files.readString(target));
            Files.delete(target);
        }
    }

    @Test void byteBudgetStillRejectsBeforeTheDeadline() throws Exception {
        try (var server = fixed("ordinary-data")) {
            var target = directory.resolve("oversize.csv");
            var failure = assertThrows(IOException.class, () -> EtlFileInput.download(
                    server.url(), target, 4, Duration.ofSeconds(3)));
            assertEquals("ETL input exceeds its byte limit", failure.getMessage());
            assertTrue(Files.size(target) <= 4);
            Files.delete(target);
        }
    }

    @Test void interruptWakesTheActualBodyReaderAndReleasesResources() throws Exception {
        var release = new CountDownLatch(1);
        var bodySent = new CountDownLatch(1);
        try (var server = new Fixture(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('x'); exchange.getResponseBody().flush(); bodySent.countDown();
            release.await(5, TimeUnit.SECONDS);
        }, release)) {
            var target = directory.resolve("interrupted.csv");
            var error = new AtomicReference<Throwable>();
            var preserved = new AtomicBoolean();
            var worker = new Thread(() -> {
                try { EtlFileInput.download(server.url(), target, 1024, Duration.ofSeconds(20)); }
                catch (Throwable failure) { error.set(failure); preserved.set(Thread.currentThread().isInterrupted()); }
            }, "etl-file-test-reader");
            worker.setDaemon(true); worker.start();
            try {
                assertTrue(bodySent.await(3, TimeUnit.SECONDS));
                long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((!Files.exists(target) || Files.size(target) == 0) && System.nanoTime() < waitUntil)
                    Thread.sleep(10);
                assertEquals(1, Files.size(target), "Interrupt must occur after the body reader has consumed a byte");
                worker.interrupt(); worker.join(2000);
                assertFalse(worker.isAlive(), "Thread exit is required; Future cancellation is not evidence");
                assertInstanceOf(IOException.class, error.get());
                assertTrue(preserved.get());
                Files.delete(target);
            } finally {
                release.countDown(); worker.interrupt(); worker.join(3000);
                assertFalse(worker.isAlive());
            }
        }
    }

    @Test void invalidBudgetIsRejectedWithoutIssuingARequest() throws Exception {
        var requests = new AtomicInteger();
        try (var server = new Fixture(exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1);
        }, new CountDownLatch(0))) {
            assertThrows(IllegalArgumentException.class, () -> EtlFileInput.download(
                    server.url(), directory.resolve("invalid.csv"), 0, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> EtlFileInput.download(
                    server.url(), directory.resolve("invalid.csv"), 1, Duration.ZERO));
            assertEquals(0, requests.get());
        }
    }

    private Fixture fixed(String value) throws IOException {
        return new Fixture(exchange -> {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }, new CountDownLatch(0));
    }

    @FunctionalInterface private interface Response { void send(HttpExchange exchange) throws Exception; }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch release;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "etl-file-test-server"); thread.setDaemon(true); return thread;
        });

        private Fixture(Response response, CountDownLatch release) throws IOException {
            this.release = release;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/input", exchange -> {
                try (exchange) { response.send(exchange); }
                catch (IOException ignored) { /* The tested deadline/interrupt closes the client connection. */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            server.start();
        }

        private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/input"; }

        @Override public void close() throws InterruptedException {
            release.countDown(); server.stop(0); executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
