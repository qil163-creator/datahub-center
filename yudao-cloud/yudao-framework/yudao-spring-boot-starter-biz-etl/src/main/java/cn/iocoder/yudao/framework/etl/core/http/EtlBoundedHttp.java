package cn.iocoder.yudao.framework.etl.core.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** 对响应头及整个响应体统一计时，超限立即取消订阅，不在异常中传播地址或认证内容。 */
public final class EtlBoundedHttp {
    private EtlBoundedHttp() { }

    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maximumBytes, int timeoutMillis)
            throws IOException, InterruptedException {
        if (maximumBytes < 1 || maximumBytes > 64 * 1024 * 1024 || timeoutMillis < 1 || timeoutMillis > 300_000)
            throw new IllegalArgumentException("HTTP响应预算无效");
        var subscriber = new LimitedBody(maximumBytes);
        var future = client.sendAsync(request, information -> subscriber);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            throw new HttpTimeoutException("HTTP完整响应超过时间预算");
        } catch (ExecutionException failure) {
            throw new IOException("HTTP响应失败或超过字节预算");
        } finally {
            if (!future.isDone()) {
                subscriber.cancel();
                future.cancel(true);
            }
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;

        private LimitedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (cancelled) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (cancelled) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    cancel(); result.completeExceptionally(new IOException("HTTP响应超过字节预算")); return;
                }
                byte[] block = new byte[buffer.remaining()]; buffer.get(block); bytes.writeBytes(block);
            }
            var active = subscription;
            if (!cancelled && active != null) active.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IOException("HTTP响应未完成")); }
        @Override public void onComplete() { if (!cancelled) result.complete(bytes.toByteArray()); }
        private void cancel() { cancelled = true; var active = subscription; if (active != null) active.cancel(); }
    }
}
