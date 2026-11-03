package cn.iocoder.yudao.framework.etl.core.executor.support;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 为现有 CSV/JSON 输入节点提供有界文件下载和解压。 */
public final class EtlFileInput {

    /**
     * 初始化 {@code EtlFileInput}。
     */
    private EtlFileInput() {
    }

    /**
     * 从远端资源下载响应内容。
     */
    public static void download(String url, Path destination, long maxBytes) throws IOException, InterruptedException {
        download(url, destination, maxBytes, Duration.ofSeconds(30));
    }

    /**
     * 从远端资源下载响应内容。
     */
    static void download(String url, Path destination, long maxBytes,
            Duration timeout) throws IOException, InterruptedException {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("File byte limit must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("File download timeout must be positive");
        }
        long timeoutNanos = timeout.toNanos();
        URI uri = URI.create(url);
        if (!ListHolder.SCHEMES.contains(uri.getScheme())) {
            throw new IllegalArgumentException("ETL input requires HTTP or HTTPS");
        }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
             var deadline = new DownloadDeadline(client, timeoutNanos)) {
            try {
                var response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (var input = bounded(response.body(), maxBytes)) {
                    deadline.attach(response.body());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IOException("ETL input download failed");
                    }
                    try (var output = Files.newOutputStream(destination)) {
                        input.transferTo(output);
                    }
                }
                deadline.complete();
            } catch (IOException failure) {
                if (deadline.expired()) {
                    throw new IOException("ETL input download exceeded its deadline");
                }
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    /** HttpRequest.timeout 在响应头返回后结束；此处还限制停滞或缓慢传输的响应体。 */
    private static final class DownloadDeadline implements AutoCloseable {

        private enum State {ACTIVE, COMPLETE, EXPIRED}

        private final HttpClient client;

        private final long expiresAt;

        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

        private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "etl-file-deadline");
            thread.setDaemon(true);
            return thread;
        });

        private final ScheduledFuture<?> alarm;

        private volatile InputStream body;

        /**
         * 初始化 {@code DownloadDeadline}。
         */
        private DownloadDeadline(HttpClient client, long timeoutNanos) {
            this.client = client;
            expiresAt = System.nanoTime() + timeoutNanos;
            alarm = watchdog.schedule(this::expire, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        /**
         * 登记当前下载输入流，并在期限已过时立即关闭。
         */
        private void attach(InputStream input) throws IOException {
            body = input;
            if (expired()) {
                input.close();
                throw new IOException("ETL input download exceeded its deadline");
            }
        }

        /**
         * 返回当前过期。
         */
        private boolean expired() {
            if (System.nanoTime() - expiresAt >= 0) {
                expire();
            }
            return state.get() == State.EXPIRED;
        }

        /**
         * 标记当前作用域过期并触发终止或清理。
         */
        private void expire() {
            if (!state.compareAndSet(State.ACTIVE, State.EXPIRED)) {
                return;
            }
            try {
                var input = body;
                if (input != null) {
                    input.close(); // 取消订阅，并唤醒实际阻塞的读取器。
                }
            } catch (IOException ignored) {
                // 下面的 shutdownNow 也会取消请求交互；不暴露远端诊断信息。
            } finally {
                client.shutdownNow();
            }
        }

        /**
         * 记录当前操作完成并发布最终结果。
         */
        private void complete() throws IOException {
            if (expired() || !state.compareAndSet(State.ACTIVE, State.COMPLETE)) {
                throw new IOException("ETL input download exceeded its deadline");
            }
        }

        /**
         * 关闭 {@code EtlFileInput} 当前持有的资源。
         */
        @Override
        public void close() {
            state.compareAndSet(State.ACTIVE, State.COMPLETE);
            alarm.cancel(false);
            watchdog.shutdownNow();
            watchdog.close();
        }
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    public static InputStream bounded(InputStream source, long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("File byte limit must be positive");
        }
        return new FilterInputStream(source) {
            private long remaining = maxBytes;

            /**
             * 累计当前输入已消费的字节数，并检查中断及读取上限。
             */
            private void consumed(int count) throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("ETL input interrupted");
                }
                if (count > remaining) {
                    throw new IOException("ETL input exceeds its byte limit");
                }
                if (count > 0) {
                    remaining -= count;
                }
            }

            /**
             * 按文件格式和读取配置解析表格数据。
             */
            @Override
            public int read() throws IOException {
                int value = in.read();
                consumed(value < 0 ? 0 : 1);
                return value;
            }

            /**
             * 按文件格式和读取配置解析表格数据。
             */
            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                java.util.Objects.checkFromIndexSize(off, len, bytes.length);
                if (len == 0) {
                    return 0;
                }
                int size = (int) Math.min(len, Math.min(Integer.MAX_VALUE - 1L, remaining) + 1L);
                int count = in.read(bytes, off, size);
                consumed(Math.max(count, 0));
                return count;
            }
        };
    }

    private static final class ListHolder {

        private static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
    }
}
