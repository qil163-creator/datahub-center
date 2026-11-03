package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyContractTest {

    private HttpServer server;
    private DolphinSchedulerClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private String path;
    private String query;
    private String contentType;
    private String body;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dolphinscheduler", exchange -> {
            calls.incrementAndGet();
            path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getRawQuery();
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = "{\"code\":0,\"success\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders("/dolphinscheduler/users/batch/activate".equals(path) ? 200 : 201,
                    response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
            exchange.close();
        });
        server.start();
        DolphinSchedulerProperties properties = new DolphinSchedulerProperties();
        properties.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/dolphinscheduler");
        client = new DolphinSchedulerClient(properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void batchActivationConvertsLegacyCommaSeparatedNamesToJsonArray() {
        client.user().batchActivateUser("alice, bob");
        assertBatchActivation(List.of("alice", "bob"));
    }

    @Test
    void batchActivationSupportsTypedNamesAndRawArrayWithExactEscaping() {
        List<String> names = List.of("alice", "name\"with\\escaping", "用户");
        client.user().batchActivateUsers(names);
        assertBatchActivation(names);
        client.user().batchActivateUser(JSONUtil.toJsonStr(names));
        assertBatchActivation(names);
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsMissingAndInvalidNamesBeforeCallingScheduler() {
        for (String invalid : new String[]{null, "", " ", "alice,", "[]", "[null]", "[1]"}) {
            assertThatThrownBy(() -> client.user().batchActivateUser(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (List<String> invalid : Arrays.asList(null, List.<String>of(), List.of(" "),
                Arrays.asList("alice", null))) {
            assertThatThrownBy(() -> client.user().batchActivateUsers(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void projectWorkerGroupAssignmentSendsJsonArrayBody() {
        client.projectWorkerGroup().assignWorkerGroups(9007199254740993L, null,
                List.of("default", "analytics"));
        assertThat(path).isEqualTo("/dolphinscheduler/projects/9007199254740993/worker-group");
        assertThat(query).isNull();
        assertThat(contentType).startsWith("application/json");
        assertThat(JSONUtil.parseArray(body).toList(String.class)).containsExactly("default", "analytics");
    }

    @Test
    void workflowDeletionAndBatchControlAcceptFullWidthCodes() {
        client.workflowDefinition().deleteWorkflowDefinitionByCode(9007199254740993L, 9007199254740995L);
        assertThat(path).isEqualTo("/dolphinscheduler/projects/9007199254740993/workflow-definition/9007199254740995");
        client.executor().batchControlWorkflowInstance(9007199254740993L, "41,42", "STOP");
        assertThat(path).isEqualTo("/dolphinscheduler/projects/9007199254740993/executors/batch-execute");
        assertThat(body).contains("workflowInstanceIds=41%2C42", "executeType=STOP");
        client.workflowInstance().batchDeleteWorkflowInstanceByIds(9007199254740993L, "41,42");
        assertThat(path).isEqualTo("/dolphinscheduler/projects/9007199254740993/workflow-instances/batch-delete");
        assertThat(body).contains("workflowInstanceIds=41%2C42");
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyIntegerCodeSignaturesStillDelegateToCorrectRoutes() {
        client.workflowDefinition().deleteWorkflowDefinitionByCode(41L, Integer.valueOf(42));
        assertThat(path).isEqualTo("/dolphinscheduler/projects/41/workflow-definition/42");
        client.executor().batchControlWorkflowInstance(Integer.valueOf(41), "42", "STOP");
        assertThat(path).isEqualTo("/dolphinscheduler/projects/41/executors/batch-execute");
        client.workflowInstance().batchDeleteWorkflowInstanceByIds(Integer.valueOf(41), "42");
        assertThat(path).isEqualTo("/dolphinscheduler/projects/41/workflow-instances/batch-delete");
    }

    private void assertBatchActivation(List<String> names) {
        assertThat(path).isEqualTo("/dolphinscheduler/users/batch/activate");
        assertThat(query).isNull();
        assertThat(contentType).startsWith("application/json");
        assertThat(JSONUtil.parseArray(body).toList(String.class)).containsExactlyElementsOf(names);
    }
}
