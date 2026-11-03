package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * 为 jdbc-jest-driver 的连接自有传输提供版本绑定适配器。不复制端点、凭据、
 * SDK 实例或 HTTP 连接池。不带类型的 REST 操作同样支持 Elasticsearch 8。
 * 响应从同一客户端的 HTTP 流读取，设置字节上限并按 UTF-8 解码；
 * 绝不使用 Jest 旧版无界或 ISO-8859-1 物化器。所有厂商反射均集中于此。
 */
public final class ElasticsearchNativeSession {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Connection connection;

    private final Object client;

    private final ClassLoader loader;

    private final Class<?> actionType;

    private final Method prepareRequest;

    private final Method executeRequest;

    private final Method getStatusLine;

    private final Method getStatusCode;

    private final Method getEntity;

    private final Method getContentLength;

    private final Method getContent;

    private final Method setRequestConfig;

    private final Method abortRequest;

    private final Object requestConfig;

    private final long responseLimit;

    /**
     * 打开Elasticsearch原生会话并准备后续访问。
     */
    public static ElasticsearchNativeSession open(Connection connection, long responseLimit) throws SQLException {
        return open(connection, responseLimit, 60);
    }

    /**
     * 打开Elasticsearch原生会话并准备后续访问。
     */
    public static ElasticsearchNativeSession open(Connection connection, long responseLimit,
            int timeoutSeconds) throws SQLException {
        return new ElasticsearchNativeSession(connection, responseLimit, timeoutSeconds);
    }

    /**
     * 校验构造参数并初始化 {@code ElasticsearchNativeSession}。
     */
    private ElasticsearchNativeSession(Connection connection, long responseLimit,
            int timeoutSeconds) throws SQLException {
        if (responseLimit < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive response and timeout limits are required");
        }
        this.connection = connection;
        this.responseLimit = responseLimit;
        try {
            Object nativeConnection = DriverNativeAccess.unwrap(connection,
                    "com.gitee.jdbc.elasticsearch.ElasticsearchConnection");
            loader = nativeConnection.getClass().getClassLoader();
            Class<?> connectionType = Class.forName("com.gitee.jdbc.elasticsearch.ElasticsearchConnection", false,
                    loader);
            Class<?> wrapperType = Class.forName("com.gitee.jdbc.elasticsearch.ElasticsearchClient", false, loader);
            Class<?> clientType = Class.forName("io.searchbox.client.JestClient", false, loader);
            actionType = Class.forName("io.searchbox.action.Action", false, loader);
            Object wrapper = connectionType.getMethod("getClient").invoke(nativeConnection);
            // 此桥接器没有公开 execute 方法。打开时校验其唯一已知结构。
            Field transport = wrapperType.getDeclaredField("jestClient");
            if (transport.getType() != clientType || !transport.trySetAccessible()) {
                throw new ReflectiveOperationException();
            }
            client = transport.get(wrapper);
            if (!clientType.isInstance(client)) {
                throw new ReflectiveOperationException();
            }
            Class<?> httpClient = Class.forName("io.searchbox.client.http.JestHttpClient", false, loader);
            if (!httpClient.isInstance(client)) {
                throw new ReflectiveOperationException();
            }
            Class<?> requestType = Class.forName("org.apache.http.client.methods.HttpUriRequest", false, loader);
            prepareRequest = httpClient.getDeclaredMethod("prepareRequest", actionType);
            executeRequest = httpClient.getDeclaredMethod("executeRequest", requestType);
            if (!prepareRequest.trySetAccessible() || !executeRequest.trySetAccessible()) {
                throw new ReflectiveOperationException();
            }
            Class<?> responseType = Class.forName("org.apache.http.HttpResponse", false, loader);
            Class<?> entityType = Class.forName("org.apache.http.HttpEntity", false, loader);
            getStatusLine = responseType.getMethod("getStatusLine");
            getStatusCode = Class.forName("org.apache.http.StatusLine", false, loader).getMethod("getStatusCode");
            getEntity = responseType.getMethod("getEntity");
            getContentLength = entityType.getMethod("getContentLength");
            getContent = entityType.getMethod("getContent");
            Class<?> configType = Class.forName("org.apache.http.client.config.RequestConfig", false, loader);
            Class<?> builderType = Class.forName("org.apache.http.client.config.RequestConfig$Builder", false, loader);
            Object builder = configType.getMethod("custom").invoke(null);
            int timeoutMillis = (int) Math.min(Integer.MAX_VALUE, 1000L * timeoutSeconds);
            builderType.getMethod("setConnectTimeout", int.class).invoke(builder, timeoutMillis);
            builderType.getMethod("setSocketTimeout", int.class).invoke(builder, timeoutMillis);
            builderType.getMethod("setConnectionRequestTimeout", int.class).invoke(builder, timeoutMillis);
            builderType.getMethod("setRedirectsEnabled", boolean.class).invoke(builder, false);
            requestConfig = builderType.getMethod("build").invoke(builder);
            Class<?> requestBase = Class.forName("org.apache.http.client.methods.HttpRequestBase", false, loader);
            setRequestConfig = requestBase.getMethod("setConfig", configType);
            abortRequest = requestBase.getMethod("abort");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new SQLException("Elasticsearch native bridge contract is unavailable");
        }
    }

    /** 仅组件生成的相对路径可进入此方法；绝不将其暴露为管理员 REST 代理。 */
    public Response request(String method, String path, String body) throws SQLException {
        if (connection.isClosed()) {
            throw new SQLException("Elasticsearch connection is closed");
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("#")
                || !java.util.Set.of("GET", "POST", "PUT", "DELETE", "HEAD").contains(method)) {
            throw new IllegalArgumentException("Invalid Elasticsearch native operation");
        }
        Object action = Proxy.newProxyInstance(loader, new Class<?>[]{actionType}, (self, called, arguments) -> {
            return switch (called.getName()) {
                case "getURI" -> path;
                case "getRestMethodName" -> method;
                case "getData" -> body;
                case "getPathToResult" -> null;
                case "getHeaders" -> Map.of("Content-Type", path.startsWith("/_bulk")
                        ? "application/x-ndjson" : "application/json");
                case "createNewElasticSearchResult" ->
                        throw new SQLException("Legacy response materialization is forbidden");
                case "toString" -> "Elasticsearch native action";
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == arguments[0];
                default -> throw new SQLException("Elasticsearch native action contract is incompatible");
            };
        });
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            Object request = prepareRequest.invoke(client, action);
            setRequestConfig.invoke(request, requestConfig);
            Object response = executeRequest.invoke(client, request);
            if (!(response instanceof java.io.Closeable owner)) {
                throw new SQLException("Elasticsearch response has no closeable owner");
            }
            try (owner) {
                int status = (Integer) getStatusCode.invoke(getStatusLine.invoke(response));
                Object entity = getEntity.invoke(response);
                if (entity == null) {
                    return new Response(status, JSON.createObjectNode());
                }
                long declared = (Long) getContentLength.invoke(entity);
                if (declared > responseLimit) {
                    abortRequest.invoke(request);
                    throw new SQLException("Elasticsearch response exceeds the configured boundary");
                }
                try (var input = (java.io.InputStream) getContent.invoke(entity);
                     var bytes = new java.io.ByteArrayOutputStream()) {
                    try {
                        byte[] buffer = new byte[8192];
                        long received = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new SQLException("Elasticsearch read interrupted");
                            }
                            received = Math.addExact(received, count);
                            if (received > responseLimit) {
                                throw new SQLException("Elasticsearch response exceeds the configured boundary");
                            }
                            bytes.write(buffer, 0, count);
                        }
                    } catch (SQLException | java.io.IOException | RuntimeException failure) {
                        // 先 abort 再关闭流：否则 EntityUtils/HTTP 实体关闭可能
                        // 为复用连接而排空过大或停滞的响应。
                        abortRequest.invoke(request);
                        throw failure;
                    }
                    String raw = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                    JsonNode json = raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
                    return new Response(status, json);
                }
            }
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof InterruptedException) {
                thread.interrupt();
            }
            throw new SQLException("Elasticsearch native request did not return a usable response");
        } catch (ReflectiveOperationException | RuntimeException | java.io.IOException failure) {
            throw new SQLException("Elasticsearch native response is invalid");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public record Response(int status, JsonNode json) {

        /**
         * 校验并取得成功标记。
         */
        public JsonNode requireSuccess() throws SQLException {
            if (status < 200 || status >= 300) {
                throw new SQLException("Elasticsearch rejected the native operation (HTTP " + status + ")");
            }
            return json;
        }
    }

    /**
     * 返回索引。
     */
    public static String index(String name) {
        // 单个具体普通索引；别名、通配符或多索引选择器以及系统索引
        // 需要各自的授权和变更契约。
        if (name == null || !name.matches("[a-z0-9][a-z0-9_.-]{0,254}") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("One ordinary Elasticsearch index name is required");
        }
        return name;
    }

    /**
     * 按当前值域约定解析或转换JSON 值。
     */
    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Elasticsearch request cannot be encoded");
        }
    }
}
