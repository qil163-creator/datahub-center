package cn.iocoder.yudao.framework.dolphinscheduler.api;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 受管运行状态观测共用的有界 HTTPS 传输；不跟随跳转，也不返回远端正文作为异常。 */
final class AuthenticatedObservationClient implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final HttpClient client;

    AuthenticatedObservationClient(SSLContext sslContext) {
        client = HttpClient.newBuilder().sslContext(Objects.requireNonNull(sslContext, "需要 SSL 上下文"))
                .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    byte[] get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Accept", "application/json").header("Accept-Encoding", "identity").GET().build();
        var responseFuture = client.sendAsync(request, ignored -> new BoundedBodySubscriber());
        try {
            // 请求超时同时覆盖响应头和完整响应体。
            HttpResponse<byte[]> response = responseFuture.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !contentType.toLowerCase(java.util.Locale.ROOT)
                    .matches("application/json(?:\\s*;.*)?")
                    || !response.headers().firstValue("Content-Encoding").orElse("identity")
                    .equalsIgnoreCase("identity")) {
                throw new IllegalStateException("运行状态观测不可用");
            }
            return response.body();
        } catch (InterruptedException ex) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("运行状态观测被中断");
        } catch (ExecutionException | TimeoutException ex) {
            responseFuture.cancel(true);
            // 不附加可能泄露凭据、内部路径或响应正文的异常原因。
            throw new IllegalStateException("运行状态观测不可用");
        }
    }

    static URI validateEndpoint(URI endpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || endpoint.getRawPath().contains("%") || !endpoint.normalize().equals(endpoint)
                || endpoint.getPath().contains("//")) {
            throw new IllegalArgumentException("运行状态观测需要可信 HTTPS 端点");
        }
        return endpoint;
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();

        private Flow.Subscription subscription;

        private int received;

        private boolean failed;

        /**
         * 返回请求体。
         */
        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        /**
         * 保存订阅对象并请求当前处理器允许的数据量。
         */
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        /**
         * 接收上游数据并更新当前消费状态。
         */
        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) {
                return;
            }
            long count = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (count > MAX_RESPONSE_BYTES - received) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("运行状态响应超出观测上限"));
                return;
            }
            received += (int) count;
            delegate.onNext(buffers);
        }

        /**
         * 记录上游异常并结束当前消费流程。
         */
        @Override
        public void onError(Throwable error) {
            if (!failed) {
                delegate.onError(error);
            }
        }

        /**
         * 记录上游完成并结束当前消费流程。
         */
        @Override
        public void onComplete() {
            if (!failed) {
                delegate.onComplete();
            }
        }
    }

    @Override
    public void close() {
        // 不无限等待错误端点未结束的响应体。
        client.shutdownNow();
    }
}
