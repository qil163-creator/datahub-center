package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 由受控物理 Flight 连接拥有的产品传输。绝不暴露凭据、
 * 客户端或原始响应。HTTP 重定向或重放不是恢复策略。 */
public final class InfluxNativeSession {

    static final ObjectMapper JSON = new ObjectMapper();

    private final Connection connection;

    private final URI origin;

    private final String database, token;

    private final long limit, timeoutMillis;

    private volatile String clusterIdentity;

    /**
     * 初始化 {@code InfluxNativeSession}。
     */
    private InfluxNativeSession(Connection connection, URI origin, String database, String token, long limit,
            long timeoutMillis) {
        this.connection = connection;
        this.origin = origin;
        this.database = database;
        this.token = token;
        this.limit = limit;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 打开InfluxDB原生会话并准备后续访问。
     */
    public static InfluxNativeSession open(DataSource dataSource, Connection connection, long limit,
            int timeoutSeconds) {
        if (!(dataSource instanceof HikariDataSource pool) || limit < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Influx native operations require a configured controlled pool");
        }
        try {
            String jdbc = pool.getJdbcUrl();
            if (jdbc == null || !jdbc.startsWith("jdbc:arrow-flight-sql://")) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(jdbc.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() < 1) {
                throw new IllegalArgumentException();
            }
            var parameters = new HashMap<String, String>();
            for (String item : Objects.requireNonNullElse(uri.getRawQuery(), "").split("&")) {
                if (item.isEmpty()) {
                    continue;
                }
                String[] parts = item.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                if (parameters.putIfAbsent(key, value) != null || Set.of("token", "password", "user", "username",
                        "authorization").contains(key)) {
                    throw new IllegalArgumentException();
                }
            }
            String database = identifier(parameters.get("database"));
            String encryption = parameters.getOrDefault("useencryption", "true");
            if (!Set.of("0", "1", "false", "true").contains(encryption.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            boolean tls = !Set.of("0", "false").contains(encryption.toLowerCase(Locale.ROOT));
            String token = pool.getPassword();
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException();
            }
            return new InfluxNativeSession(connection,
                    new URI(tls ? "https" : "http", null, uri.getHost(), uri.getPort(), null, null, null), database,
                    token,
                    Math.min(limit, 16L * 1024 * 1024), Math.multiplyExact((long) timeoutSeconds, 1000L));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid InfluxDB3 deployment endpoint or native limits");
        }
    }

    /**
     * 返回数据库。
     */
    public String database() {
        return database;
    }

    /** 服务器持久化目录 UUID 独立于 HTTP 别名。它不是表编号。 */
    public String clusterIdentity() throws SQLException {
        if (clusterIdentity == null) {
            query("SELECT 1 AS dbswitch_identity");
        }
        if (clusterIdentity == null) {
            throw new SQLException("Influx server did not provide its catalog identity");
        }
        return clusterIdentity;
    }

    /** 端点证据是逻辑证据，不是数据库代次标识符。 */
    public String logicalTable(String table) {
        return origin.toASCIIString() + "/" + encode(database) + "/" + encode(identifier(table));
    }

    /**
     * 按当前值域约定解析或转换JSON 值。
     */
    public JsonNode json(String method, String path, JsonNode body) throws SQLException {
        return json(method, path, body, 0);
    }

    /**
     * 按当前值域约定解析或转换JSON 值。
     */
    JsonNode json(String method, String path, JsonNode body, int expectedStatus) throws SQLException {
        byte[] result = request(method, path, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json", expectedStatus);
        try {
            return result.length == 0 ? JSON.nullNode() : JSON.readTree(result);
        } catch (Exception invalid) {
            throw new SQLException("InfluxDB3 returned invalid bounded JSON");
        }
    }

    /**
     * 返回查询。
     */
    public JsonNode query(String sql) throws SQLException {
        return json("POST", "/api/v3/query_sql",
                JSON.createObjectNode().put("db", database).put("q", sql).put("format", "json"));
    }

    /** 服务器身份来自原生 ping 端点，绝不来自 DataFusion SQL 标识信息。 */
    public void requireSchemaEvolutionVersion() throws SQLException {
        var identity = json("GET", "/ping", null, 200);
        if (!"InfluxDB 3 Core".equals(identity.path("product_name").asText())
                || !"3.12.0".equals(identity.path("version").asText())) {
            throw new SQLException("Native table evolution requires verified InfluxDB 3 Core 3.12.0");
        }
    }

    /**
     * 通过受控 InfluxDB 原生写入接口提交当前载荷。
     */
    public void write(byte[] lines) throws SQLException {
        request("POST", "/api/v3/write_lp?db=" + encode(database) + "&precision=nanosecond&accept_partial=false", lines,
                "text/plain; charset=utf-8");
    }

    /**
     * 返回请求。
     */
    byte[] request(String method, String path, byte[] body, String contentType) throws SQLException {
        return request(method, path, body, contentType, 0);
    }

    /**
     * 返回请求。
     */
    private byte[] request(String method, String path, byte[] body, String contentType,
            int expectedStatus) throws SQLException {
        if (!(path.startsWith("/api/v3/") || method.equals("GET") && path.equals("/ping")) || path.startsWith("//")) {
            throw new IllegalArgumentException("Invalid Influx native route");
        }
        return DriverNativeAccess.invoke(connection, timeoutMillis, () -> {
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(10_000, timeoutMillis)))
                    .followRedirects(HttpClient.Redirect.NEVER).build()) {
                var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofMillis(timeoutMillis))
                        .header("Authorization", "Bearer " + token).header("Content-Type", contentType)
                        .method(method,
                                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(
                                        body)).build();
                var response = client.send(request, info -> new BoundedBody(limit));
                var identities = response.headers().allValues("cluster-uuid");
                if (!identities.isEmpty()) {
                    final String observed;
                    try {
                        if (identities.size() != 1) {
                            throw new IllegalArgumentException();
                        }
                        String raw = identities.getFirst();
                        observed = UUID.fromString(raw).toString();
                        if (!observed.equalsIgnoreCase(raw)) {
                            throw new IllegalArgumentException();
                        }
                    } catch (IllegalArgumentException invalid) {
                        throw new SQLException("Influx server catalog identity is invalid");
                    }
                    if (clusterIdentity != null && !clusterIdentity.equals(observed)) {
                        throw new SQLException("Influx server catalog changed during the native session");
                    }
                    clusterIdentity = observed;
                } else if (clusterIdentity != null) {
                    throw new SQLException("Influx server catalog identity disappeared during the native session");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new SQLException("InfluxDB3 rejected the native request");
                }
                if (expectedStatus != 0 && response.statusCode() != expectedStatus) {
                    throw new SQLException("InfluxDB3 native acknowledgement does not prove the requested operation");
                }
                return response.body();
            }
        });
    }

    /**
     * 将当前数据编码为字符串。
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 检查或转换当前数据库标识符。
     */
    public static String identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.chars()
                .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid InfluxDB3 identifier");
        }
        return value;
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    public static String quote(String value) {
        return "\"" + identifier(value).replace("\"", "\"\"") + "\"";
    }

    /**
     * 返回当前字面量。
     */
    public static String literal(String value) {
        return "'" + identifier(value).replace("'", "''") + "'";
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "InfluxNativeSession[controlled]";
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        private Flow.Subscription subscription;

        /**
         * 初始化 {@code BoundedBody}。
         */
        BoundedBody(long limit) {
            this.limit = limit;
        }

        /**
         * 返回结果。
         */
        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        /**
         * 保存订阅对象并请求当前处理器允许的数据量。
         */
        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        /**
         * 接收上游数据并更新当前消费状态。
         */
        @Override
        public void onNext(List<ByteBuffer> values) {
            for (var value : values) {
                if (value.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new SQLException("Influx response exceeds its byte budget"));
                    return;
                }
                byte[] chunk = new byte[value.remaining()];
                value.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        /**
         * 记录上游异常并结束当前消费流程。
         */
        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(new SQLException("Influx response did not complete"));
        }

        /**
         * 记录上游完成并结束当前消费流程。
         */
        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
