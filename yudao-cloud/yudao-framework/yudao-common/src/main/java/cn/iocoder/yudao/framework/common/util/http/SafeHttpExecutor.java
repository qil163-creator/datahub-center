package cn.iocoder.yudao.framework.common.util.http;

import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.net.SocketFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 面向不可信输入的有界 HTTP 执行器；业务授权和可信目标配置由调用方负责。
 *
 * <p>公网模式每跳校验全部 DNS 结果，并把实际连接绑定到本次校验的地址。固定目标模式
 * 只允许服务端指定的 origin 和规范化路径，允许该目标使用内网地址。两种模式均不继承
 * 平台身份、代理或 Cookie 状态，也不会在错误中返回 URL、请求头或响应内容。</p>
 */
public final class SafeHttpExecutor {

    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_REDIRECTS = 5;

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> CONTROL_HEADERS = Set.of("host", "content-length", "transfer-encoding",
            "connection", "keep-alive", "te", "trailer", "upgrade", "expect", "accept-encoding",
            "login-user", "tenant-id", "visit-tenant-id", "data-permission", "data-permission-enable",
            "data-permission-enabled", "data-permission-disable", "data-permission-disabled");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Semaphore EXECUTIONS = new Semaphore(16);
    // 系统 DNS 在部分平台不可中断；固定线程数和有界队列防止超时查询无限占用资源。
    private static final ThreadPoolExecutor DNS_EXECUTOR = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false)
                    .name("safe-http-dns-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    private SafeHttpExecutor() {
    }

    public record Header(String name, String value) {
    }

    public record Redirect(String url, int statusCode) {
    }

    public record Request(String url, String method, List<Header> headers, byte[] body, int timeoutSeconds,
                          boolean followRedirects, TargetPolicy targetPolicy) {
        public Request {
            headers = headers == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(headers));
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }
    }

    public record Result(int statusCode, String statusText, List<Header> headers, byte[] body, long durationMs,
                         boolean truncated, String url, List<Redirect> redirectChain) {
        public Result {
            headers = List.copyOf(headers);
            body = body.clone();
            redirectChain = List.copyOf(redirectChain);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    public sealed interface TargetPolicy permits PublicOnlyPolicy, FixedTargetPolicy {
    }

    private enum PublicOnlyPolicy implements TargetPolicy {
        INSTANCE
    }

    private record FixedTargetPolicy(HttpUrl url) implements TargetPolicy {
    }

    public enum FailureReason {
        INVALID_REQUEST, TARGET_REJECTED, DNS_FAILED, TIMEOUT, CONCURRENCY_LIMIT, TRANSPORT_FAILED,
        TOO_MANY_REDIRECTS
    }

    public static final class SafeHttpException extends IOException {

        private final FailureReason reason;

        private SafeHttpException(FailureReason reason) {
            super(switch (reason) {
                case INVALID_REQUEST -> "HTTP 请求参数不合法";
                case TARGET_REJECTED -> "HTTP 目标不符合安全访问策略";
                case DNS_FAILED -> "HTTP 目标域名解析失败";
                case TIMEOUT -> "HTTP 请求超时";
                case CONCURRENCY_LIMIT -> "HTTP 执行资源繁忙，请稍后重试";
                case TRANSPORT_FAILED -> "HTTP 请求执行失败";
                case TOO_MANY_REDIRECTS -> "HTTP 重定向次数超过限制";
            });
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }

    public static TargetPolicy publicOnly() {
        return PublicOnlyPolicy.INSTANCE;
    }

    /**
     * 仅供调用方根据可信服务端配置创建；查询参数不参与目标授权。
     */
    public static TargetPolicy fixedTarget(String trustedUrl) throws SafeHttpException {
        return new FixedTargetPolicy(parseUrl(trustedUrl));
    }

    public static Result execute(Request request) throws SafeHttpException {
        return execute(request, Dns.SYSTEM);
    }

    // 包内注入 DNS 用于验证地址绑定和安全负例，生产入口始终使用系统解析器。
    static Result execute(Request request, Dns resolver) throws SafeHttpException {
        return execute(request, resolver, SocketFactory.getDefault());
    }

    // 测试可替换套接字连接位置；公开入口不允许覆盖传输机制或地址校验。
    static Result execute(Request request, Dns resolver, SocketFactory socketFactory) throws SafeHttpException {
        validateRequest(request);
        if (!EXECUTIONS.tryAcquire()) {
            throw failure(FailureReason.CONCURRENCY_LIMIT);
        }
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
        try {
            return executeChain(request, resolver, socketFactory, started, deadline);
        } finally {
            EXECUTIONS.release();
        }
    }

    private static Result executeChain(Request request, Dns resolver, SocketFactory socketFactory, long started, long deadline)
            throws SafeHttpException {
        HttpUrl url = parseUrl(request.url());
        String method = request.method().toUpperCase(Locale.ROOT);
        List<Header> headers = request.headers();
        byte[] body = request.body();
        if (Set.of("GET", "HEAD").contains(method)) {
            body = null;
        }
        List<Redirect> redirects = new ArrayList<>();
        while (true) {
            validateTarget(url, request.targetPolicy());
            List<InetAddress> addresses = resolve(url, request.targetPolicy(), resolver, deadline);
            long remaining = remainingNanos(deadline);
            HttpUrl currentUrl = url;
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .socketFactory(socketFactory)
                    .dns(host -> {
                        if (!host.equals(currentUrl.host())) {
                            throw new UnknownHostException("HTTP target rejected");
                        }
                        return addresses;
                    })
                    .cookieJar(CookieJar.NO_COOKIES)
                    .authenticator(Authenticator.NONE)
                    .proxyAuthenticator(Authenticator.NONE)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .protocols(List.of(Protocol.HTTP_1_1))
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                    .callTimeout(remaining, TimeUnit.NANOSECONDS)
                    .connectTimeout(remaining, TimeUnit.NANOSECONDS)
                    .readTimeout(remaining, TimeUnit.NANOSECONDS)
                    .writeTimeout(remaining, TimeUnit.NANOSECONDS)
                    .build();
            try {
                // 显式请求未压缩响应，避免客户端透明解压时改写真实响应头。
                okhttp3.Request.Builder outgoing = new okhttp3.Request.Builder().url(url).header("Accept-Encoding", "identity");
                for (Header header : headers) {
                    outgoing.addHeader(header.name(), header.value());
                }
                RequestBody outgoingBody = body == null ? null : RequestBody.create(body, null);
                if (outgoingBody == null && Set.of("POST", "PUT", "PATCH").contains(method)) {
                    outgoingBody = RequestBody.create(new byte[0], null);
                }
                outgoing.method(method, outgoingBody);
                try (Response response = client.newCall(outgoing.build()).execute()) {
                    String location = response.header("Location");
                    if (request.followRedirects() && isRedirect(response.code()) && location != null) {
                        if (redirects.size() >= MAX_REDIRECTS) {
                            throw failure(FailureReason.TOO_MANY_REDIRECTS);
                        }
                        HttpUrl next = resolveRedirect(url, location);
                        validateTarget(next, request.targetPolicy());
                        redirects.add(new Redirect(url.toString(), response.code()));
                        if ((response.code() == 303 && !"HEAD".equals(method))
                                || ((response.code() == 301 || response.code() == 302) && "POST".equals(method))) {
                            method = "GET";
                            body = null;
                            headers = withoutEntityHeaders(headers);
                        }
                        if (!sameOrigin(url, next) && body != null && body.length > 0) {
                            // 请求体也可能包含凭据；保留请求体的跨源跳转必须由用户重新明确发起。
                            throw failure(FailureReason.TARGET_REJECTED);
                        }
                        headers = redirectHeaders(headers, url, next);
                        url = next;
                        continue;
                    }
                    List<Header> responseHeaders = new ArrayList<>();
                    for (int i = 0; i < response.headers().size(); i++) {
                        responseHeaders.add(new Header(response.headers().name(i), response.headers().value(i)));
                    }
                    byte[] bytes = response.body() == null ? new byte[0]
                            : readBounded(response.body().byteStream(), deadline);
                    boolean truncated = bytes.length > MAX_RESPONSE_BYTES;
                    if (truncated) {
                        bytes = java.util.Arrays.copyOf(bytes, MAX_RESPONSE_BYTES);
                    }
                    return new Result(response.code(), response.message(), responseHeaders, bytes,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), truncated,
                            url.toString(), redirects);
                }
            } catch (SafeHttpException ex) {
                throw ex;
            } catch (IOException ex) {
                throw failure(System.nanoTime() >= deadline || ex instanceof java.io.InterruptedIOException
                        ? FailureReason.TIMEOUT : FailureReason.TRANSPORT_FAILED);
            } catch (IllegalArgumentException ex) {
                throw failure(FailureReason.INVALID_REQUEST);
            } finally {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
            }
        }
    }

    private static void validateRequest(Request request) throws SafeHttpException {
        if (request == null || request.targetPolicy() == null || request.method() == null
                || !METHODS.contains(request.method().toUpperCase(Locale.ROOT))
                || request.timeoutSeconds() < 1 || request.timeoutSeconds() > 120
                || request.headers().size() > 128
                || (request.body != null && request.body.length > MAX_REQUEST_BYTES)
                || (Set.of("GET", "HEAD").contains(request.method().toUpperCase(Locale.ROOT))
                && request.body != null && request.body.length > 0)) {
            throw failure(FailureReason.INVALID_REQUEST);
        }
        long headerBytes = 0;
        for (Header header : request.headers()) {
            if (header == null || header.name() == null || header.value() == null
                    || !HEADER_NAME.matcher(header.name()).matches()) {
                throw failure(FailureReason.INVALID_REQUEST);
            }
            String name = header.name().toLowerCase(Locale.ROOT);
            if (CONTROL_HEADERS.contains(name) || name.startsWith("proxy-") || name.startsWith("data-permission-")) {
                throw failure(FailureReason.INVALID_REQUEST);
            }
            headerBytes += (long) header.name().length() + header.value().length();
            if (headerBytes > 32 * 1024 || header.value().chars().anyMatch(c -> (c < 32 && c != '\t') || c >= 127)) {
                throw failure(FailureReason.INVALID_REQUEST);
            }
        }
        parseUrl(request.url());
    }

    private static HttpUrl parseUrl(String value) throws SafeHttpException {
        if (value == null || value.length() > 16 * 1024 || value.indexOf('\\') >= 0
                || value.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw failure(FailureReason.INVALID_REQUEST);
        }
        try {
            URI raw = URI.create(value);
            HttpUrl url = HttpUrl.get(value);
            if (raw.getRawUserInfo() != null || raw.getRawFragment() != null
                    || !url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null
                    || url.host().contains("%")
                    || (raw.getHost() != null && raw.getHost().contains(":") && !url.host().contains(":"))) {
                throw failure(FailureReason.TARGET_REJECTED);
            }
            return url;
        } catch (IllegalArgumentException ex) {
            throw failure(FailureReason.INVALID_REQUEST);
        }
    }

    private static void validateTarget(HttpUrl url, TargetPolicy policy) throws SafeHttpException {
        if (policy instanceof FixedTargetPolicy fixed) {
            if (!sameOrigin(url, fixed.url()) || !url.encodedPath().equals(fixed.url().encodedPath())) {
                throw failure(FailureReason.TARGET_REJECTED);
            }
        } else {
            String host = url.host().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.equals("metadata.google.internal")) {
                throw failure(FailureReason.TARGET_REJECTED);
            }
            // Java 会将 IPv4 映射 IPv6 文字折叠为 IPv4，提前拒绝该表示形式。
            if (host.contains(":") && (host.startsWith("::ffff:") || host.contains(":ffff:"))) {
                throw failure(FailureReason.TARGET_REJECTED);
            }
        }
    }

    private static List<InetAddress> resolve(HttpUrl url, TargetPolicy policy, Dns resolver, long deadline)
            throws SafeHttpException {
        Future<List<InetAddress>> task;
        try {
            task = DNS_EXECUTOR.submit(() -> List.copyOf(resolver.lookup(url.host())));
        } catch (RejectedExecutionException ex) {
            throw failure(FailureReason.CONCURRENCY_LIMIT);
        }
        try {
            List<InetAddress> addresses = task.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            if (addresses.isEmpty()) {
                throw failure(FailureReason.DNS_FAILED);
            }
            if (policy instanceof PublicOnlyPolicy) {
                for (InetAddress address : addresses) {
                    if (!isPublicAddress(address)) {
                        throw failure(FailureReason.TARGET_REJECTED);
                    }
                }
            }
            return addresses;
        } catch (TimeoutException ex) {
            throw failure(FailureReason.TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure(FailureReason.TIMEOUT);
        } catch (ExecutionException ex) {
            throw failure(FailureReason.DNS_FAILED);
        } finally {
            task.cancel(true);
            DNS_EXECUTOR.purge();
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            int d = Byte.toUnsignedInt(bytes[3]);
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 192 && b == 0 && (c == 0 || c == 2)) || (a == 192 && b == 88 && c == 99)
                    || (a == 198 && (b == 18 || b == 19)) || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113) || (a == 168 && b == 63 && c == 129 && d == 16));
        }
        if (bytes.length != 16) {
            return false;
        }
        int a = Byte.toUnsignedInt(bytes[0]);
        int b = Byte.toUnsignedInt(bytes[1]);
        // 只接受全球单播分配区间，并排除文档、特殊用途及可嵌入 IPv4 的隧道区间。
        return (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x01 && (Byte.toUnsignedInt(bytes[2]) & 0xfe) == 0)
                && !(a == 0x20 && b == 0x01 && bytes[2] == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x3f && b == 0xff && (Byte.toUnsignedInt(bytes[2]) & 0xf0) == 0);
    }

    private static HttpUrl resolveRedirect(HttpUrl from, String location) throws SafeHttpException {
        if (location.indexOf('\\') >= 0 || location.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw failure(FailureReason.TARGET_REJECTED);
        }
        HttpUrl next = from.resolve(location);
        if (next == null) {
            throw failure(FailureReason.TARGET_REJECTED);
        }
        try {
            URI raw = URI.create(location);
            if (raw.getHost() != null && raw.getHost().contains(":") && !next.host().contains(":")) {
                throw failure(FailureReason.TARGET_REJECTED);
            }
        } catch (IllegalArgumentException ex) {
            throw failure(FailureReason.TARGET_REJECTED);
        }
        return parseUrl(next.toString());
    }

    private static boolean sameOrigin(HttpUrl first, HttpUrl second) {
        return first.scheme().equals(second.scheme()) && first.host().equals(second.host())
                && first.port() == second.port();
    }

    static List<Header> redirectHeaders(List<Header> headers, HttpUrl first, HttpUrl second) {
        // 跨源不传播任何用户请求头，包括自定义认证头与显式 Cookie。
        return sameOrigin(first, second) ? headers : List.of();
    }

    private static List<Header> withoutEntityHeaders(List<Header> headers) {
        return headers.stream().filter(header -> !header.name().equalsIgnoreCase("Content-Type")
                && !header.name().equalsIgnoreCase("Content-Encoding")).toList();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] readBounded(InputStream input, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() <= MAX_RESPONSE_BYTES) {
            remainingNanos(deadline);
            int read = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES + 1 - output.size()));
            if (read == -1) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static long remainingNanos(long deadline) throws SafeHttpException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw failure(FailureReason.TIMEOUT);
        }
        return remaining;
    }

    private static SafeHttpException failure(FailureReason reason) {
        return new SafeHttpException(reason);
    }
}
