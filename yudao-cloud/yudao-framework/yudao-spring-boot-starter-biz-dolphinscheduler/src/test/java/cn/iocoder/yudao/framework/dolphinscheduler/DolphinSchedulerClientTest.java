package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;
import cn.iocoder.yudao.framework.dolphinscheduler.config.YudaoDolphinSchedulerAutoConfiguration;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.request.TriggerWorkflowDefinitionRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DolphinSchedulerClientTest {

    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestQuery = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestToken = new AtomicReference<>();
    private final AtomicReference<String> requestCookie = new AtomicReference<>();

    private HttpServer server;
    private String serverUrl;
    private DolphinSchedulerClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/dolphinscheduler";

        DolphinSchedulerProperties properties = new DolphinSchedulerProperties();
        properties.setServerUrl(serverUrl);
        properties.setToken("test-token");
        client = new DolphinSchedulerClient(properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldLoginAndSendTokenHeader() {
        JSONObject result = client.login().login("admin", "admin123");
        assertThat(result.getBool("success")).isTrue();
        assertThat(result.getJSONObject("data").getStr("sessionId")).isEqualTo("sid-1");
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo("/dolphinscheduler/login");
        assertThat(requestBody.get()).contains("userName=admin");
        assertThat(requestBody.get()).contains("userPassword=admin123");
        assertThat(requestToken.get()).isEqualTo("test-token");
        assertThat(client.http().getSessionCookie()).isEqualTo("sessionId=sid-1");
    }

    @Test
    void shouldKeepProjectAndExecutorContract() {
        JSONObject created = client.project().createProject("demo", "desc");
        assertThat(created.getJSONObject("data").getLong("code")).isEqualTo(1001L);
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo("/dolphinscheduler/projects");
        assertThat(requestBody.get()).contains("projectName=demo");

        JSONObject project = client.project().queryProjectByCode(1001L);
        assertThat(project.getJSONObject("data").getStr("name")).isEqualTo("demo");
        assertThat(requestMethod.get()).isEqualTo("GET");
        assertThat(requestPath.get()).isEqualTo("/dolphinscheduler/projects/1001");

        JSONObject started = client.executor().triggerWorkflowDefinition(1001L,
                new TriggerWorkflowDefinitionRequest()
                        .setWorkflowDefinitionCode(2002L)
                        .setFailureStrategy("CONTINUE")
                        .setWarningType("NONE")
                        .setScheduleTime(""));
        assertThat(started.getJSONArray("data")).containsExactly(9);
        assertThat(requestPath.get()).isEqualTo("/dolphinscheduler/projects/1001/executors/start-workflow-instance");
        assertThat(requestBody.get()).contains("workflowDefinitionCode=2002");
        assertThat(requestBody.get()).contains("failureStrategy=CONTINUE");
        assertThat(requestBody.get()).contains("scheduleTime=");
    }

    @Test
    void shouldDownloadBinaryAndSendCookieAfterLogin() {
        client.login().login("admin", "admin123");
        byte[] bytes = client.resource().downloadResource("/tmp/a.sh");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("echo ok");
        assertThat(requestPath.get()).isEqualTo("/dolphinscheduler/resources/download");
        assertThat(requestQuery.get()).contains("fullName=").contains("/tmp/a.sh");
        assertThat(requestCookie.get()).isEqualTo("sessionId=sid-1");
    }

    @Test
    void shouldRejectBusinessFailedResult() {
        assertThatThrownBy(() -> client.project().queryProjectByCode(404L))
                .isInstanceOf(DolphinSchedulerException.class)
                .hasMessageContaining("business failed")
                .hasMessageContaining("project not found");
    }

    @Test
    void shouldRejectNonOkResponse() {
        assertThatThrownBy(() -> client.project().queryProjectByCode(503L))
                .isInstanceOf(DolphinSchedulerException.class)
                .hasMessageContaining("DolphinScheduler rest api request failed")
                .hasMessageContaining("service unavailable");
    }

    @Test
    void shouldFillPathAndBuildParams() {
        assertThat(DolphinSchedulerHttp.fillPath("/projects/{code}/tasks/{id}",
                DolphinSchedulerHttp.params("code", 1L, "id", "a b")))
                .isEqualTo("/projects/1/tasks/a%20b");
    }

    @Test
    void shouldAutoConfigureClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDolphinSchedulerAutoConfiguration.class))
                .withPropertyValues(
                        "dolphinscheduler.server-url=http://ds.test:12345/dolphinscheduler",
                        "dolphinscheduler.token=abc")
                .run(context -> {
                    assertThat(context).hasSingleBean(DolphinSchedulerProperties.class);
                    assertThat(context).hasSingleBean(DolphinSchedulerClient.class);
                    DolphinSchedulerProperties properties = context.getBean(DolphinSchedulerProperties.class);
                    assertThat(properties.getServerUrl()).isEqualTo("http://ds.test:12345/dolphinscheduler");
                    assertThat(properties.getToken()).isEqualTo("abc");
                    assertThat(context.getBean(DolphinSchedulerClient.class).http().getToken()).isEqualTo("abc");
                });
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestMethod.set(exchange.getRequestMethod());
        requestPath.set(exchange.getRequestURI().getPath());
        requestQuery.set(exchange.getRequestURI().getRawQuery());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestToken.set(exchange.getRequestHeaders().getFirst("token"));
        requestCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));

        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/dolphinscheduler/login" -> {
                exchange.getResponseHeaders().add("Set-Cookie", "sessionId=sid-1; Path=/");
                respond(exchange, 200, "{\"code\":0,\"success\":true,\"failed\":false,\"data\":{\"sessionId\":\"sid-1\"}}");
            }
            case "/dolphinscheduler/projects" -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 201, "{\"code\":0,\"success\":true,\"data\":{\"code\":1001,\"name\":\"demo\"}}");
                } else {
                    respond(exchange, 200, "{\"code\":0,\"success\":true,\"data\":[]}");
                }
            }
            case "/dolphinscheduler/projects/1001" ->
                    respond(exchange, 200, "{\"code\":0,\"success\":true,\"data\":{\"code\":1001,\"name\":\"demo\"}}");
            case "/dolphinscheduler/projects/404" ->
                    respond(exchange, 200, "{\"code\":10001,\"success\":false,\"failed\":true,\"msg\":\"project not found\"}");
            case "/dolphinscheduler/projects/503" -> respond(exchange, 503, "service unavailable");
            case "/dolphinscheduler/projects/1001/executors/start-workflow-instance" ->
                    respond(exchange, 200, "{\"code\":0,\"success\":true,\"data\":[9]}");
            case "/dolphinscheduler/resources/download" -> respond(exchange, 200, "echo ok");
            default -> respond(exchange, 404, "not found");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

}
