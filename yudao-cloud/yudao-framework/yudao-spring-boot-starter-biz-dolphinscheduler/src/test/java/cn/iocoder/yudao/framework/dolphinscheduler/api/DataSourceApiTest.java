package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the DS 3.4.2 raw JSON body contract, including the Swagger jsonStr annotation artifact. */
class DataSourceApiTest {

    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "POSTGRESQL", "name", "contract-fixture", "password", "synthetic-secret",
            "other", Map.of("currentSchema", "contract_schema"), "connectType", "SERVICE_NAME");

    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private DataSourceApi api;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        var properties = new DolphinSchedulerProperties();
        properties.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/dolphinscheduler");
        api = new DolphinSchedulerClient(properties).dataSource();
    }

    @AfterEach
    void shutdown() {
        if (server != null) server.stop(0);
    }

    @Test
    void createPreservesPluginFieldsInJsonBodyAndAcceptsCreatedStatus() {
        var result = api.createDataSource(PARAMETERS);
        assertThat(result.getJSONObject("data").getInt("id")).isEqualTo(12);
        assertRequest("POST", "/dolphinscheduler/datasources");
        assertThat(JSONUtil.parseObj(body.get()).getJSONObject("other").getStr("currentSchema"))
                .isEqualTo("contract_schema");
        assertThat(JSONUtil.parseObj(body.get()).getStr("connectType")).isEqualTo("SERVICE_NAME");
    }

    @Test
    void connectPostsTheUnsavedConnectionParametersAsJson() {
        assertThat(api.connectDataSource(PARAMETERS).getBool("data")).isTrue();
        assertRequest("POST", "/dolphinscheduler/datasources/connect");
    }

    @Test
    void updateUsesPathIdAndNeverSendsTheConnectionBodyAsAQueryParameter() {
        api.updateDataSource(12, PARAMETERS);
        assertRequest("PUT", "/dolphinscheduler/datasources/12");
        assertThat(query.get()).isNull();
    }

    @Test
    void rawJsonOverloadsDoNotDoubleEncodeAnObjectAsAString() {
        String raw = JSONUtil.toJsonStr(PARAMETERS);
        api.createDataSource(raw);
        assertRequest("POST", "/dolphinscheduler/datasources");
        assertThat(body.get()).isEqualTo(raw);
        api.connectDataSource(raw);
        assertRequest("POST", "/dolphinscheduler/datasources/connect");
        assertThat(body.get()).isEqualTo(raw);
        api.updateDataSource(12, raw);
        assertRequest("PUT", "/dolphinscheduler/datasources/12");
        assertThat(body.get()).isEqualTo(raw);
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyUpdatePreservesBodyAndTreatsJsonStrAsBodyOnlyWhenNeeded() {
        api.updateDataSource(12, JSONUtil.toJsonStr(PARAMETERS), PARAMETERS);
        assertRequest("PUT", "/dolphinscheduler/datasources/12");
        api.updateDataSource(12, JSONUtil.toJsonStr(PARAMETERS), null);
        assertRequest("PUT", "/dolphinscheduler/datasources/12");
        assertThat(query.get()).isNull();
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyUpdateStillSerializesBeanBodiesAndPrefersBodyOverJsonStr() {
        api.updateDataSource(12, "{\"password\":\"unused-synthetic-value\"}", new ConnectionParametersBean());
        assertRequest("PUT", "/dolphinscheduler/datasources/12");
        assertThat(JSONUtil.parseObj(body.get()).getStr("name")).isEqualTo("bean-fixture");
        assertThat(JSONUtil.parseObj(body.get()).getJSONObject("other").getStr("currentSchema"))
                .isEqualTo("contract_schema");
        assertThat(body.get()).doesNotContain("unused-synthetic-value");
    }

    @Test
    @SuppressWarnings("deprecation")
    void missingBodiesInvalidIdsAndNonObjectJsonFailBeforeContactingTheServer() {
        assertThatThrownBy(() -> api.createDataSource()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.connectDataSource()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.createDataSource((Map<String, Object>) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.connectDataSource(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.createDataSource("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.connectDataSource("\"synthetic-secret\""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("synthetic-secret");
        assertThatThrownBy(() -> api.updateDataSource(0, PARAMETERS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.updateDataSource(null, PARAMETERS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.updateDataSource(12, null, 12)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.updateDataSource(12, null, List.of(PARAMETERS))).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.get()).isZero();
    }

    public static final class ConnectionParametersBean {
        private final String type = "POSTGRESQL";
        private final String name = "bean-fixture";
        private final String password = "synthetic-secret";
        private final Map<String, String> other = Map.of("currentSchema", "contract_schema");

        public String getType() { return type; }
        public String getName() { return name; }
        public String getPassword() { return password; }
        public Map<String, String> getOther() { return other; }
    }

    private void assertRequest(String expectedMethod, String expectedPath) {
        assertThat(method.get()).isEqualTo(expectedMethod);
        assertThat(path.get()).isEqualTo(expectedPath);
        assertThat(query.get()).isNull();
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(JSONUtil.parseObj(body.get()).getStr("password")).isEqualTo("synthetic-secret");
        assertThat(JSONUtil.parseObj(body.get()).getStr("type")).isEqualTo("POSTGRESQL");
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        method.set(exchange.getRequestMethod());
        path.set(exchange.getRequestURI().getPath());
        query.set(exchange.getRequestURI().getRawQuery());
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        boolean connectionTest = path.get().endsWith("/connect");
        byte[] response = (connectionTest ? "{\"code\":0,\"data\":true}"
                : "{\"code\":0,\"data\":{\"id\":12}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders("POST".equals(method.get()) && !connectionTest ? 201 : 200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
