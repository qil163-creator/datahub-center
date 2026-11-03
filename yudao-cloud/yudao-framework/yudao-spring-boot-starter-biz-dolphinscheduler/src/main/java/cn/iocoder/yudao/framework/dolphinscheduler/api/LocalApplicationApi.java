package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 通过受信任的 Worker 观察器核验本地引擎进程，不把调度器结束或空结果当作进程退出证据。 */
public final class LocalApplicationApi implements AutoCloseable {

    private static final Set<String> STATES = Set.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
    private final URI endpoint;
    private final AuthenticatedObservationClient client;

    public LocalApplicationApi(URI endpoint, SSLContext sslContext) {
        this.endpoint = validateEndpoint(endpoint);
        this.client = new AuthenticatedObservationClient(sslContext);
    }

    public record Execution(String name, String user, String engine, String state, Integer exitCode,
                            boolean processTreeAlive, boolean started, boolean finished) {
    }

    /** 精确查询管理员生成的运行名称及操作系统用户，最多返回 64 个观测项。 */
    public List<Execution> find(String name, String user) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,128}")
                || user == null || !user.matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}")) {
            throw new IllegalArgumentException("需要精确的本地运行身份");
        }
        URI uri = URI.create(endpoint.toASCIIString().replaceAll("/+$", "") + "/v1/executions?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&user="
                + URLEncoder.encode(user, StandardCharsets.UTF_8));
        return parse(name, user, client.get(uri));
    }

    static List<Execution> parse(String name, String user, byte[] bytes) {
        try {
            JSONObject document = JSONUtil.parseObj(new String(bytes, StandardCharsets.UTF_8));
            var values = document.getJSONArray("executions");
            if (values == null || values.size() > 64) {
                throw new IllegalArgumentException();
            }
            List<Execution> result = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                JSONObject item = values.getJSONObject(i);
                String observedName = text(item, "name", 128);
                String observedUser = text(item, "user", 100);
                String engine = text(item, "engine", 16);
                String state = text(item, "state", 16);
                if (!name.equals(observedName) || !user.equals(observedUser)
                        || !Set.of("FLINK", "SPARK").contains(engine) || !STATES.contains(state)) {
                    throw new IllegalArgumentException();
                }
                boolean started = bool(item, "started");
                boolean finished = bool(item, "finished");
                boolean alive = bool(item, "processTreeAlive");
                if (!item.containsKey("exitCode")) {
                    throw new IllegalArgumentException();
                }
                Object rawCode = item.get("exitCode");
                Integer code = null;
                if (!JSONUtil.isNull(rawCode)) {
                    if (!(rawCode instanceof Number number) || number.doubleValue() != number.intValue()
                            || number.intValue() < -255 || number.intValue() > 255) {
                        throw new IllegalArgumentException();
                    }
                    code = number.intValue();
                }
                if ((finished && (!started || alive || code == null || Set.of("RUNNING", "UNKNOWN").contains(state)))
                        || (!finished && (!Set.of("RUNNING", "UNKNOWN").contains(state) || code != null))
                        || ("SUCCEEDED".equals(state) && !Integer.valueOf(0).equals(code))
                        || ("FAILED".equals(state) && Integer.valueOf(0).equals(code))) {
                    throw new IllegalArgumentException();
                }
                result.add(new Execution(observedName, observedUser, engine, state, code, alive, started, finished));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("本地引擎观测结果无效");
        }
    }

    private static String text(JSONObject item, String key, int maxLength) {
        Object value = item.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    private static boolean bool(JSONObject item, String key) {
        if (!(item.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    public static URI validateEndpoint(URI endpoint) {
        return AuthenticatedObservationClient.validateEndpoint(endpoint);
    }

    @Override
    public void close() {
        client.close();
    }
}
