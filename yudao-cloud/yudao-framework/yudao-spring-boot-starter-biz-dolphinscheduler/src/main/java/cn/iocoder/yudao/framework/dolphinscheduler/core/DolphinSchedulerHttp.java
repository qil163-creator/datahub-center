package cn.iocoder.yudao.framework.dolphinscheduler.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException;
import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * DolphinScheduler HTTP 调用封装。
 * <p>
 * 鉴权：请求头 {@code token}，以及登录后下发的 {@code sessionId} Cookie。
 * 参数形态与 OpenAPI 一致：路径变量替换 URL，其余 {@code @RequestParam} 走 query/form。
 */
public class DolphinSchedulerHttp {

    public static final int SUCCESS_CODE = 0;

    private static final int MAX_RESOURCE_TIMEOUT_MILLIS = 30_000;

    private final DolphinSchedulerProperties properties;

    private volatile String token;

    private volatile String sessionCookie;

    /**
     * 校验构造参数并初始化 {@code DolphinSchedulerHttp}。
     */
    public DolphinSchedulerHttp(DolphinSchedulerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.token = properties.getToken();
    }

    /**
     * 返回属性配置。
     */
    public DolphinSchedulerProperties getProperties() {
        return properties;
    }

    /**
     * 返回令牌。
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置令牌。
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 返回会话Cookie。
     */
    public String getSessionCookie() {
        return sessionCookie;
    }

    /**
     * 设置会话Cookie。
     */
    public void setSessionCookie(String sessionCookie) {
        this.sessionCookie = sessionCookie;
    }

    /**
     * 根据给定键或索引读取当前容器中的值。
     */
    public JSONObject get(String path, Map<String, Object> query) {
        return executeJson("GET", path, query, null, null, null);
    }

    /**
     * 删除当前受管文件，或由包装器保留交由外部生命周期清理。
     */
    public JSONObject delete(String path, Map<String, Object> query) {
        return executeJson("DELETE", path, query, null, null, null);
    }

    /**
     * 以 HTTP POST 方式提交表单参数。
     */
    public JSONObject postForm(String path, Map<String, Object> form) {
        return executeJson("POST", path, form, null, null, null);
    }

    /**
     * 以 HTTP PUT 方式提交表单参数。
     */
    public JSONObject putForm(String path, Map<String, Object> form) {
        return executeJson("PUT", path, form, null, null, null);
    }

    /**
     * 以 HTTP POST 方式提交 JSON 请求体。
     */
    public JSONObject postJson(String path, Map<String, Object> query, Object body) {
        return executeJson("POST", path, query, body, null, null);
    }

    /**
     * 以 HTTP PUT 方式提交 JSON 请求体。
     */
    public JSONObject putJson(String path, Map<String, Object> query, Object body) {
        return executeJson("PUT", path, query, body, null, null);
    }

    /**
     * 提交包含文件及表单字段的 multipart 请求。
     */
    public JSONObject multipart(String method, String path, Map<String, Object> form, String fileField, File file) {
        return executeJson(method, path, form, null, fileField, file);
    }

    /**
     * 从远端资源下载响应内容。
     */
    public byte[] download(String method, String path, Map<String, Object> query) {
        Object raw = execute(method, path, query, null, null, null, true);
        return raw instanceof byte[] bytes ? bytes : new byte[0];
    }

    /** Reads at most maxBytes with a complete-request budget capped at 30 seconds. */
    public byte[] download(String method, String path, Map<String, Object> query, int maxBytes) {
        downloadBound(maxBytes);
        int timeoutMillis = resourceTimeoutMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try (HttpResponse response = downloadResponse(method, path, query, timeoutMillis, deadline);
             InputStream stream = response.bodyStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            readBounded(stream, maxBytes, bytes, null, deadline);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new DolphinSchedulerException("DolphinScheduler resource download failed");
        }
    }

    /** Hashes the actual decoded resource bytes without retaining an artifact-sized byte array. */
    public String downloadSha256(String method, String path, Map<String, Object> query, long maxBytes) {
        downloadBound(maxBytes);
        int timeoutMillis = resourceTimeoutMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try (HttpResponse response = downloadResponse(method, path, query, timeoutMillis, deadline);
             InputStream stream = response.bodyStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            readBounded(stream, maxBytes, null, digest, deadline);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new DolphinSchedulerException("DolphinScheduler resource download failed");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 读取并下载远端文件响应。
     */
    private HttpResponse downloadResponse(String method, String path, Map<String, Object> query,
            int timeoutMillis, long deadline) {
        checkResourceDeadline(deadline);
        HttpRequest request = HttpRequest.of(appendQuery(joinUrl(properties.getServerUrl(), path), query))
                .method(Method.valueOf(method.toUpperCase(java.util.Locale.ROOT)))
                .setFollowRedirects(false).timeout(timeoutMillis);
        applyAuth(request);
        // executeAsync leaves the response body as a stream. execute() would buffer it before a limit check.
        HttpResponse response = request.executeAsync();
        if (System.nanoTime() - deadline >= 0) {
            response.close();
            throw new DolphinSchedulerException("DolphinScheduler resource download deadline exceeded");
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            response.close();
            throw new DolphinSchedulerException("DolphinScheduler resource download HTTP failure");
        }
        rememberSession(response);
        return response;
    }

    /**
     * 计算当前资源访问剩余的超时毫秒数。
     */
    private int resourceTimeoutMillis() {
        int configured = properties.getTimeoutMillis();
        return configured > 0 ? Math.min(configured, MAX_RESOURCE_TIMEOUT_MILLIS) : MAX_RESOURCE_TIMEOUT_MILLIS;
    }

    /**
     * 检查当前资源访问未超过整体期限。
     */
    private static void checkResourceDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) {
            throw new DolphinSchedulerException("DolphinScheduler resource download deadline exceeded");
        }
    }

    /**
     * 检查或应用当前下载的字节数和时间上限。
     */
    private static void downloadBound(long maxBytes) {
        if (maxBytes < 1 || maxBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("Resource download limit must be between 1 byte and 1 GiB");
        }
    }

    /**
     * 读取有界。
     */
    private static void readBounded(InputStream stream, long maxBytes, ByteArrayOutputStream bytes,
            MessageDigest digest, long deadline) throws IOException {
        long count = 0;
        byte[] buffer = new byte[8192];
        while (true) {
            // Socket read timeouts alone allow a peer to retain the caller by continuously dripping bytes.
            // A blocked read is also capped by timeoutMillis, so overshoot is bounded by one read timeout.
            checkResourceDeadline(deadline);
            int read = stream.read(buffer, 0, (int) Math.min(buffer.length, maxBytes - count + 1));
            checkResourceDeadline(deadline);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            count += read;
            if (count > maxBytes) {
                throw new DolphinSchedulerException("DolphinScheduler resource exceeds download limit");
            }
            if (bytes != null) {
                bytes.write(buffer, 0, read);
            }
            if (digest != null) {
                digest.update(buffer, 0, read);
            }
        }
    }

    /**
     * 调用当前受控运行时的目标方法并传播调用结果。
     */
    public JSONObject invoke(String method, String path, Map<String, Object> params, Object jsonBody, File file) {
        String fileField = file == null ? null : "file";
        return executeJson(method, path, params, jsonBody, fileField, file);
    }

    /**
     * 调用统一 HTTP 执行入口并返回 JSON 对象响应。
     */
    private JSONObject executeJson(String method, String path, Map<String, Object> params, Object jsonBody,
            String fileField, File file) {
        return (JSONObject) execute(method, path, params, jsonBody, fileField, file, false);
    }

    /**
     * 将路径模板中的 {@code {name}} 替换为已编码的参数值。
     */
    public static String fillPath(String pathTemplate, Map<String, Object> pathVars) {
        String path = pathTemplate;
        if (pathVars != null) {
            for (Map.Entry<String, Object> entry : pathVars.entrySet()) {
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                path = path.replace("{" + entry.getKey() + "}",
                        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        return path;
    }

    /**
     * 返回参数。
     */
    public static Map<String, Object> params(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return new LinkedHashMap<>();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("params key/value count must be even");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key == null) {
                throw new IllegalArgumentException("params key must not be null");
            }
            map.put(String.valueOf(key), keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    @SuppressWarnings("unchecked")
    private Object execute(String method, String path, Map<String, Object> params, Object jsonBody,
            String fileField, File file, boolean raw) {
        boolean hasFile = file != null;
        boolean hasJson = jsonBody != null;
        boolean formMethod = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(
                method) || "PATCH".equalsIgnoreCase(method);
        String url = joinUrl(properties.getServerUrl(), path);
        if (hasJson && !hasFile) {
            url = appendQuery(url, params);
        }
        HttpRequest request = HttpRequest.of(url)
                .method(Method.valueOf(method.toUpperCase()))
                .timeout(properties.getTimeoutMillis());
        applyAuth(request);

        if (hasFile) {
            if (params != null) {
                applyForm(request, params);
            }
            request.form(fileField == null ? "file" : fileField, file);
        } else if (hasJson) {
            request.contentType(ContentType.JSON.getValue());
            request.body(jsonBody instanceof String text ? text : JSONUtil.toJsonStr(jsonBody));
        } else if (params != null && !params.isEmpty()) {
            if (formMethod) {
                applyForm(request, params);
            } else {
                request.form(toFormMap(params));
            }
        }

        try (HttpResponse response = request.execute()) {
            rememberSession(response);
            if (raw) {
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    throw new DolphinSchedulerException(
                            "DolphinScheduler rest api request failed,please check the server if normal:"
                                    + response.body());
                }
                return response.bodyBytes();
            }
            return parseResult(response);
        }
    }

    /**
     * 应用认证。
     */
    private void applyAuth(HttpRequest request) {
        if (StrUtil.isNotBlank(token)) {
            request.header("token", token);
        }
        if (StrUtil.isNotBlank(sessionCookie)) {
            request.header("Cookie", sessionCookie);
        }
    }

    /**
     * 保存当前响应返回的可复用会话信息。
     */
    private void rememberSession(HttpResponse response) {
        String sessionId = response.getCookieValue("sessionId");
        if (StrUtil.isNotBlank(sessionId)) {
            rememberSessionId(sessionId);
        }
    }

    /**
     * 保存当前远端响应中的会话编号。
     */
    public void rememberSessionId(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            this.sessionCookie = "sessionId=" + sessionId;
        }
    }

    /**
     * 解析结果。
     */
    private JSONObject parseResult(HttpResponse response) {
        String body = response.body();
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new DolphinSchedulerException(
                    "DolphinScheduler rest api request failed,please check the server if normal:" + body);
        }
        if (StrUtil.isBlank(body)) {
            return new JSONObject();
        }
        if (!JSONUtil.isTypeJSON(body)) {
            JSONObject wrapper = new JSONObject();
            wrapper.set("data", body);
            wrapper.set("success", true);
            wrapper.set("code", SUCCESS_CODE);
            return wrapper;
        }
        JSONObject json = JSONUtil.parseObj(body);
        if (properties.isThrowOnFailed() && isFailed(json)) {
            Integer code = json.getInt("code");
            String msg = json.getStr("msg");
            throw new DolphinSchedulerException(
                    "DolphinScheduler rest api business failed, code=" + code + ", msg=" + msg, code, json);
        }
        Object data = json.get("data");
        if (data instanceof JSONObject dataObj && StrUtil.isNotBlank(dataObj.getStr("sessionId"))) {
            rememberSessionId(dataObj.getStr("sessionId"));
        }
        return json;
    }

    /**
     * 判断失败。
     */
    private static boolean isFailed(JSONObject json) {
        if (json.containsKey("success") && Boolean.FALSE.equals(json.getBool("success"))) {
            return true;
        }
        if (json.containsKey("failed") && Boolean.TRUE.equals(json.getBool("failed"))) {
            return true;
        }
        Integer code = json.getInt("code");
        return code != null && code != SUCCESS_CODE;
    }

    /**
     * 应用表单。
     */
    private static void applyForm(HttpRequest request, Map<String, Object> form) {
        for (Map.Entry<String, Object> entry : toFormMap(form).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof File file) {
                request.form(entry.getKey(), file);
            } else {
                request.form(entry.getKey(), String.valueOf(value));
            }
        }
    }

    /**
     * 将请求参数整理为 HTTP 表单提交使用的映射。
     */
    private static Map<String, Object> toFormMap(Map<String, Object> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (source == null) {
            return target;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value == null || entry.getKey() == null) {
                continue;
            }
            if (value instanceof Collection<?> collection) {
                target.put(entry.getKey(),
                        collection.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
            } else if (value instanceof Map<?, ?> || (value.getClass().isArray() && !(value instanceof byte[]))) {
                target.put(entry.getKey(), JSONUtil.toJsonStr(value));
            } else {
                target.put(entry.getKey(), value);
            }
        }
        return target;
    }

    /**
     * 追加查询。
     */
    private static String appendQuery(String url, Map<String, Object> params) {
        Map<String, Object> form = toFormMap(params);
        if (form.isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : form.entrySet()) {
            if (entry.getValue() instanceof File) {
                continue;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        if (query.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /**
     * 连接地址。
     */
    private static String joinUrl(String serverUrl, String path) {
        String base = StrUtil.removeSuffix(StrUtil.blankToDefault(serverUrl, ""), "/");
        if (StrUtil.isBlank(path)) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

}
