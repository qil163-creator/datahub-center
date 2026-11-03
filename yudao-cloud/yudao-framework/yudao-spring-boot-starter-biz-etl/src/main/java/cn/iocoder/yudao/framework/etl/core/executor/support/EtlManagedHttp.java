package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.iocoder.yudao.framework.etl.core.http.EtlBoundedHttp;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** 原生工作节点的受管HTTP传输：精确地址授权、有界响应、不跟随重定向、不传播底层敏感异常。 */
public final class EtlManagedHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private EtlManagedHttp() { }

    public record Response(byte[] bytes, String contentType) { }

    public static Response exchange(String endpoint, Set<String> allowed, String method,
            Map<String, String> query, Map<String, String> headers, byte[] body, int limit) {
        return exchange(endpoint, allowed, method, query, headers, body, limit, 30_000);
    }

    public static Response exchange(String endpoint, Set<String> allowed, String method,
            Map<String, String> query, Map<String, String> headers, byte[] body, int limit, int timeoutMillis) {
        endpoint = EtlHttpRows.endpoint(endpoint, allowed);
        if (limit < 1 || limit > 16_777_216 || body.length > limit || timeoutMillis < 100 || timeoutMillis > 30_000 || !Set.of("GET", "POST").contains(method))
            throw invalid("HTTP请求方式或大小预算无效");
        if (Thread.currentThread().isInterrupted()) throw invalid("HTTP请求已取消");
        try {
            String arguments = String.join("&", query.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).toList());
            String target = endpoint + (arguments.isEmpty() ? "" : (endpoint.contains("?") ? "&" : "?") + arguments);
            var request = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofMillis(timeoutMillis));
            for (var entry : headers.entrySet()) {
                String name = entry.getKey(), value = entry.getValue();
                if (!name.matches("[A-Za-z][A-Za-z0-9-]{0,99}") || Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade").contains(name.toLowerCase(java.util.Locale.ROOT))
                        || value == null || value.length() > 4096 || value.chars().anyMatch(character -> character < 32 || character == 127))
                    throw invalid("HTTP请求头无效");
                request.header(name, value);
            }
            if (method.equals("GET")) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            var response = EtlBoundedHttp.send(CLIENT, request.build(), limit, timeoutMillis);
            try (InputStream original = new ByteArrayInputStream(response.body())) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw invalid("HTTP响应状态异常");
                String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
                if (!Set.of("identity", "gzip").contains(encoding)) throw invalid("HTTP响应压缩格式未支持");
                try (InputStream input = encoding.equals("gzip") ? new GZIPInputStream(original) : original) {
                    byte[] bytes = input.readNBytes(limit + 1);
                    if (bytes.length > limit) throw invalid("HTTP响应超过预算");
                    return new Response(bytes, response.headers().firstValue("Content-Type").orElse("application/octet-stream"));
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw invalid("HTTP请求已取消");
        } catch (Exception failure) {
            throw invalid("HTTP传输失败，请检查授权、认证、响应状态和大小预算");
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
