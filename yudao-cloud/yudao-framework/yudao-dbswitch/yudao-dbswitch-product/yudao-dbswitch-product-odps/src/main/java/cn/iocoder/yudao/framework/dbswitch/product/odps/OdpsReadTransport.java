package cn.iocoder.yudao.framework.dbswitch.product.odps;

import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;
import cn.iocoder.yudao.framework.dbswitch.spi.HttpConnectionProbeSupport;
import com.aliyun.odps.commons.transport.Request;
import com.aliyun.odps.commons.transport.Response;
import com.aliyun.odps.commons.transport.Transport;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 项目探测与目录共用只读传输；不允许 SDK 隐式提交 SQL、Tunnel 或跨目标请求。 */
final class OdpsReadTransport implements Transport {
    interface Opener { HttpURLConnection open(URI uri) throws IOException; }

    private static final Pattern XML_ENTITY = Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);
    private final URI endpoint;
    private final Set<String> paths;
    private final Set<String> queryKeys;
    private final Opener opener;
    private final long deadline;
    private int requests;

    OdpsReadTransport(URI endpoint, Duration timeout, Set<String> paths, Set<String> queryKeys) {
        this(endpoint, timeout, paths, queryKeys, uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    OdpsReadTransport(URI endpoint, Duration timeout, Set<String> paths, Set<String> queryKeys, Opener opener) {
        this.endpoint = endpoint;
        this.paths = Set.copyOf(paths);
        this.queryKeys = Set.copyOf(queryKeys);
        this.opener = opener;
        deadline = System.nanoTime() + timeout.toNanos();
    }

    @Override
    public Response request(Request request) throws IOException {
        URI uri = request.getURI();
        if (request.getMethod() != Request.Method.GET || request.getBody() != null
                || !endpoint.getScheme().equals(uri.getScheme()) || !endpoint.getHost().equals(uri.getHost())
                || endpoint.getPort() != uri.getPort() || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !paths.contains(uri.getRawPath()) || !queryAllowed(uri.getRawQuery())) {
            throw new IOException("MaxCompute 请求超出只读目录范围");
        }
        if (++requests > 100) throw new IOException("MaxCompute 目录请求超过上限");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new java.net.SocketTimeoutException("MaxCompute 目录读取已超时");
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, Duration.ofNanos(remaining).toMillis()));
        var connection = opener.open(uri);
        try {
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            request.getHeaders().forEach(connection::setRequestProperty);
            HttpConnectionProbeSupport.requireSuccess(connection.getResponseCode());
            byte[] bytes;
            try (var stream = connection.getInputStream()) {
                bytes = HttpConnectionProbeSupport.readBounded(stream, Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            }
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (XML_ENTITY.matcher(text).find())
                throw new IOException("MaxCompute 目录响应不允许实体声明");
            return new ReadResponse(connection.getHeaderFields(), bytes);
        } catch (ConnectionProbeException classified) {
            throw new IOException("MaxCompute 目录请求被拒绝", classified);
        } finally {
            connection.disconnect();
        }
    }

    private boolean queryAllowed(String query) {
        if (query == null || query.isEmpty()) return true;
        if (query.length() > 8192) return false;
        var seen = new java.util.HashSet<String>();
        for (String pair : query.split("&", -1)) {
            String key = pair.split("=", 2)[0];
            if (!seen.add(key)) return false;
            if (key.equals("curr_project")) {
                String project = java.net.URLDecoder.decode(pair.substring(Math.min(pair.length(), key.length() + 1)), java.nio.charset.StandardCharsets.UTF_8);
                if (!project.matches("[A-Za-z0-9_-]{1,128}") || paths.stream().anyMatch(path -> !path.contains("/projects/" + project + "/") && !path.endsWith("/projects/" + project))) return false;
            } else if (!queryKeys.contains(key)) return false;
        }
        return true;
    }

    @Override public com.aliyun.odps.commons.transport.Connection connect(Request request) throws IOException {
        throw new IOException("MaxCompute 目录不提供流式访问");
    }

    @Override public void setProxy(java.net.Proxy proxy) {
        throw new UnsupportedOperationException("MaxCompute 目录不允许代理覆盖");
    }

    static String path(URI endpoint, String suffix) {
        return endpoint.getRawPath().replaceAll("/+$", "") + suffix;
    }

    private static final class ReadResponse extends Response {
        private ReadResponse(Map<String, java.util.List<String>> values, byte[] bytes) {
            status = 200;
            message = "OK";
            headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            values.forEach((name, items) -> {
                if (name != null && items != null && !items.isEmpty()) headers.put(name, items.getFirst());
            });
            body = bytes;
        }
    }
}
