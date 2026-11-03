package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLException;

/** 有界 JSON 协议探测；拒绝重定向和 URL 内凭据，不返回响应正文。 */
public final class HttpConnectionProbeSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 初始化 {@code HttpConnectionProbeSupport}。
     */
    private HttpConnectionProbeSupport() {
    }

    /**
     * 返回查询。
     */
    public static String query(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * 返回当前端点。
     */
    public static URI endpoint(String text) {
        URI uri = URI.create(text == null ? "" : text);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("端点必须为不含凭据、查询串和片段的 HTTP 地址");
        }
        return uri;
    }

    /**
     * 按当前值域约定解析或转换JSON 值。
     */
    public static JsonNode json(ConnectionProbeRequest request, String path, String method,
            String authorization, JsonNode body) throws IOException, ConnectionProbeException {
        String base = endpoint(request.endpoint()).toString().replaceAll("/+$", "");
        var connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        int timeout = Math.toIntExact(request.timeout().toMillis());
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = JSON.writeValueAsBytes(body);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var stream = connection.getOutputStream()) {
                    stream.write(bytes);
                }
            }
            requireSuccess(connection.getResponseCode());
            try (var stream = connection.getInputStream()) {
                JsonNode response = JSON.readTree(readBounded(stream, request.timeout()));
                if (response == null || !response.isObject()) {
                    throw new IOException("目标响应不是 JSON 对象");
                }
                return response;
            }
        } catch (SSLException invalidCertificate) {
            throw new ConnectionProbeException(ConnectionProbeException.Reason.TLS, invalidCertificate);
        } finally {
            connection.disconnect();
        }
    }

    /** 只映射协议状态，不向诊断结果传递服务端正文或认证头。 */
    public static void requireSuccess(int status) throws IOException, ConnectionProbeException {
        switch (status) {
            case 200 -> {
            }
            case 401 -> throw new ConnectionProbeException(ConnectionProbeException.Reason.AUTHENTICATION, null);
            case 403 -> throw new ConnectionProbeException(ConnectionProbeException.Reason.AUTHORIZATION, null);
            case 404 -> throw new ConnectionProbeException(ConnectionProbeException.Reason.TARGET_NOT_FOUND, null);
            default -> throw new IOException("目标拒绝协议探测");
        }
    }

    /** 读取受限协议响应；每次读取前检查截止时间和取消信号。 */
    public static byte[] readBounded(java.io.InputStream stream, java.time.Duration timeout) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                throw new java.net.SocketTimeoutException("协议探测已超时");
            }
            int count = stream.read(buffer);
            if (count == -1) {
                return bytes.toByteArray();
            }
            if (bytes.size() + count > 65536) {
                throw new IOException("协议探测响应超过上限");
            }
            bytes.write(buffer, 0, count);
        }
    }

    /**
     * 按当前值域约定解析或转换对象。
     */
    public static com.fasterxml.jackson.databind.node.ObjectNode object() {
        return JSON.createObjectNode();
    }
}
