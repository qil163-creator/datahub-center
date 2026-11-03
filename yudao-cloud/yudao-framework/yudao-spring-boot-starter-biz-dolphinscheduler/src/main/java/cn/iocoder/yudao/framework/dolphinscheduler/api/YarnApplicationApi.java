package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Bounded, authenticated YARN ResourceManager observation for scheduler-launched applications.
 * The caller owns endpoint/SSL configuration and application identity authorization. No diagnostics,
 * remote URLs or logs leave this API. Missing/invalid observations are errors, never terminal evidence.
 */
public final class YarnApplicationApi implements AutoCloseable {

    private final URI endpoint;

    private final AuthenticatedObservationClient client;

    /**
     * 校验构造参数并初始化 {@code YarnApplicationApi}。
     */
    public YarnApplicationApi(URI endpoint, SSLContext sslContext) {
        this.endpoint = validateEndpoint(endpoint);
        this.client = new AuthenticatedObservationClient(sslContext);
    }

    public enum State {
        NEW, NEW_SAVING, SUBMITTED, ACCEPTED, RUNNING, FINISHED, FAILED, KILLED;

        /**
         * 返回当前末尾。
         */
        public boolean terminal() {
            return this == FINISHED || this == FAILED || this == KILLED;
        }
    }

    public enum FinalStatus {UNDEFINED, SUCCEEDED, FAILED, KILLED}

    public record Application(String id, String user, String queue, String name, State state,
                              FinalStatus finalStatus) {

        /**
         * 返回当前末尾。
         */
        public boolean terminal() {
            return state.terminal();
        }
    }

    /** Query the exact application ID; the caller must check user, queue and generated application name. */
    public Application query(String applicationId) {
        if (applicationId == null || !applicationId.matches("application_[0-9]{1,20}_[0-9]{1,10}")) {
            throw new IllegalArgumentException("Invalid YARN application ID");
        }
        URI uri = URI.create(endpoint.toASCIIString().replaceAll("/+$", "")
                + "/ws/v1/cluster/apps/" + applicationId);
        return parse(applicationId, request(uri));
    }

    /**
     * Find all applications for an exact caller-generated name and OS user (Hadoop 3.3.6 REST).
     * Sixty-five results detect a truncated response: no partial list may prove termination.
     * Empty results are absence of evidence, not evidence of successful termination.
     */
    public List<Application> find(String applicationName, String user) {
        if (applicationName == null || !applicationName.matches("[A-Za-z0-9_-]{1,128}")
                || user == null || !user.matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}")) {
            throw new IllegalArgumentException("Exact YARN application identity required");
        }
        URI uri = URI.create(endpoint.toASCIIString().replaceAll("/+$", "") + "/ws/v1/cluster/apps?name="
                + URLEncoder.encode(applicationName, StandardCharsets.UTF_8) + "&user="
                + URLEncoder.encode(user, StandardCharsets.UTF_8) + "&limit=65");
        byte[] body = request(uri);
        try {
            JSONObject document = JSONUtil.parseObj(new String(body, StandardCharsets.UTF_8));
            if (!document.containsKey("apps")) {
                throw new IllegalArgumentException();
            }
            JSONObject apps = document.getJSONObject("apps");
            if (apps == null || apps.isEmpty()) {
                return List.of();
            }
            var entries = apps.getJSONArray("app");
            if (entries == null || entries.size() >= 65) {
                throw new IllegalArgumentException();
            }
            List<Application> result = new ArrayList<>();
            var identities = new HashSet<String>();
            for (int index = 0; index < entries.size(); index++) {
                var item = entries.getJSONObject(index);
                String id = text(item, "id", 64);
                if (!id.matches("application_[0-9]{1,20}_[0-9]{1,10}") || !identities.add(id)) {
                    throw new IllegalArgumentException();
                }
                Application app = parseApp(id, item);
                if (!applicationName.equals(app.name()) || !user.equals(app.user())) {
                    throw new IllegalArgumentException();
                }
                result.add(app);
            }
            return List.copyOf(result);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid YARN application observation");
        }
    }

    /**
     * 返回请求。
     */
    private byte[] request(URI uri) {
        return client.get(uri);
    }

    /**
     * 返回当前解析。
     */
    static Application parse(String requestedId, byte[] response) {
        try {
            JSONObject app = JSONUtil.parseObj(new String(response, StandardCharsets.UTF_8)).getJSONObject("app");
            return parseApp(requestedId, app);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid YARN application observation");
        }
    }

    /**
     * 解析应用。
     */
    private static Application parseApp(String requestedId, JSONObject app) {
        if (app == null || !requestedId.equals(app.getStr("id"))) {
            throw new IllegalArgumentException();
        }
        State state = State.valueOf(text(app, "state", 32));
        FinalStatus result = FinalStatus.valueOf(text(app, "finalStatus", 32));
        // Conflicting terminal states cannot justify releasing a running application slot.
        if ((state == State.KILLED && result != FinalStatus.KILLED)
                || (state == State.FAILED && result != FinalStatus.FAILED)
                || (state == State.FINISHED && result == FinalStatus.UNDEFINED)
                || (!state.terminal() && result != FinalStatus.UNDEFINED)) {
            throw new IllegalArgumentException();
        }
        return new Application(requestedId, text(app, "user", 256), text(app, "queue", 256),
                text(app, "name", 512), state, result);
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(JSONObject app, String field, int limit) {
        Object value = app.get(field);
        if (!(value instanceof String text) || text.isBlank() || text.length() > limit
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    /**
     * 校验端点。
     */
    public static URI validateEndpoint(URI endpoint) {
        return AuthenticatedObservationClient.validateEndpoint(endpoint);
    }

    /**
     * 关闭 {@code YarnApplicationApi} 当前持有的资源。
     */
    @Override
    public void close() {
        client.close();
    }
}
