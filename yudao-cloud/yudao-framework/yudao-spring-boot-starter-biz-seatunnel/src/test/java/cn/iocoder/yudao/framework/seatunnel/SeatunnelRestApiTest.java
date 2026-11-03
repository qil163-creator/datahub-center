package cn.iocoder.yudao.framework.seatunnel;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.seatunnel.config.SeatunnelProperties;
import cn.iocoder.yudao.framework.seatunnel.config.YudaoSeatunnelAutoConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatunnelRestApiTest {

    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestQuery = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestContentType = new AtomicReference<>();

    private HttpServer server;
    private String serverUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SeatunnelRestApi.SERVER_URL = serverUrl;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepUpstreamGetApiContract() {
        JSONArray jobs = SeatunnelRestApi.getAllJobs();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getJSONObject(0).getStr(RestConstant.JOB_ID)).isEqualTo("job-1");

        JSONObject job = SeatunnelRestApi.getJobInfo("job-1");
        assertThat(job.getStr(RestConstant.JOB_NAME)).isEqualTo("demo");

        JSONArray monitor = SeatunnelRestApi.getSystemMonitor();
        assertThat(monitor).hasSize(1);
        assertThat(monitor.getJSONObject(0).getStr("status")).isEqualTo("UP");

        SeatunnelRestApi.SERVER_URL = serverUrl + "/array";
        JSONArray monitorArray = SeatunnelRestApi.getSystemMonitor();
        assertThat(monitorArray).hasSize(1);
        assertThat(monitorArray.getJSONObject(0).getStr("status")).isEqualTo("UP");
    }

    @Test
    void shouldPostJsonAndEncodeFormat() {
        JSONObject submitted = SeatunnelRestApi.submitJob("{\"env\":{}}", "json conf");
        assertThat(submitted.getStr(RestConstant.JOB_ID)).isEqualTo("job-2");
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestQuery.get()).isEqualTo("format=json+conf");
        assertThat(requestBody.get()).isEqualTo("{\"env\":{}}");
        assertThat(requestContentType.get()).startsWith("application/json");

        JSONObject stopped = SeatunnelRestApi.stopJob("{\"jobId\":\"job-2\"}");
        assertThat(stopped.getBool("stopped")).isTrue();
        assertThat(requestBody.get()).isEqualTo("{\"jobId\":\"job-2\"}");
    }

    @Test
    void shouldRejectNonOkResponse() {
        SeatunnelRestApi.SERVER_URL = serverUrl + "/error";

        assertThatThrownBy(SeatunnelRestApi::getAllJobs)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 503")
                .hasMessageNotContaining("service unavailable")
                .hasMessageNotContaining("test-secret")
                .hasNoCause();
    }

    @Test
    void shouldEncodeReconciliationJobName() {
        SeatunnelRestApi.submitJob("{\"env\":{}}", "json", "run 17/tenant&1");
        assertThat(requestQuery.get()).isEqualTo("format=json&jobName=run+17%2Ftenant%261");
        SeatunnelRestApi.submitJob("{\"env\":{}}", null, "run-17");
        assertThat(requestQuery.get()).isEqualTo("jobName=run-17");
    }

    @Test
    void shouldSubmitHoconExactlyAsWrittenWithSeparateStableJobName() {
        String source = """
                # 保留原生作业注释、名称和格式
                env { job.name = "user-name", job.mode = "BATCH" }
                source { FakeSource { row.num = 1 } }
                transform { Sql { query = "select * from source" } }
                sink { Console {} }
                """;
        NativeSeatunnelConfig.validate(source, "hocon");
        SeatunnelRestApi.submitJob(source, "hocon", "access-1-31");
        assertThat(requestQuery.get()).isEqualTo("format=hocon&jobName=access-1-31");
        assertThat(requestBody.get()).isEqualTo(source);
    }

    @Test
    void shouldNotExposeMalformedResponseOrSubmissionErrorBody() {
        SeatunnelRestApi.SERVER_URL = serverUrl + "/error";
        assertThatThrownBy(() -> SeatunnelRestApi.submitJob("{\"password\":\"test-secret\"}", "json", "run-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("test-secret").hasNoCause();
        SeatunnelRestApi.SERVER_URL = serverUrl + "/malformed";
        assertThatThrownBy(() -> SeatunnelRestApi.submitJob("{}", "json", "run-1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("SeaTunnel REST 响应格式错误")
                .hasMessageNotContaining("test-secret").hasNoCause();
        assertThatThrownBy(SeatunnelRestApi::getAllJobs)
                .isInstanceOf(IllegalStateException.class).hasMessage("SeaTunnel REST 响应格式错误")
                .hasMessageNotContaining("test-secret").hasNoCause();
    }

    @Test
    void shouldInitializeServerUrlAndAutoConfigureBean() throws Exception {
        SeatunnelProperties properties = new SeatunnelProperties();
        assertThat(properties.getServerUrl()).isEqualTo("http://localhost:5801");
        properties.setServerUrl("http://seatunnel.internal:5801");

        new BusinessInitializer(properties).run(new DefaultApplicationArguments(new String[0]));
        assertThat(SeatunnelRestApi.SERVER_URL).isEqualTo("http://seatunnel.internal:5801");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoSeatunnelAutoConfiguration.class))
                .withPropertyValues("seatunnel.server-url=http://seatunnel.test:5801")
                .run(context -> {
                    assertThat(context).hasSingleBean(SeatunnelProperties.class);
                    assertThat(context).hasSingleBean(BusinessInitializer.class);
                    assertThat(context.getBean(SeatunnelProperties.class).getServerUrl())
                            .isEqualTo("http://seatunnel.test:5801");
                    context.getBean(BusinessInitializer.class)
                            .run(new DefaultApplicationArguments(new String[0]));
                    assertThat(SeatunnelRestApi.SERVER_URL).isEqualTo("http://seatunnel.test:5801");
                });
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestMethod.set(exchange.getRequestMethod());
        requestQuery.set(exchange.getRequestURI().getRawQuery());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));

        switch (path) {
            case "/running-jobs" -> respond(exchange, 200, "[{\"jobId\":\"job-1\"}]");
            case "/job-info/job-1" -> respond(exchange, 200, "{\"jobId\":\"job-1\",\"jobName\":\"demo\"}");
            case "/system-monitoring-information" -> respond(exchange, 200, "{\"status\":\"UP\"}");
            case "/array/system-monitoring-information" -> respond(exchange, 200, "[{\"status\":\"UP\"}]");
            case "/submit-job" -> respond(exchange, 200, "{\"jobId\":\"job-2\"}");
            case "/stop-job" -> respond(exchange, 200, "{\"stopped\":true}");
            case "/error/running-jobs" -> respond(exchange, 503, "service unavailable password=test-secret");
            case "/error/submit-job" -> respond(exchange, 500, requestBody.get());
            case "/malformed/submit-job", "/malformed/running-jobs" -> respond(exchange, 200, "test-secret: malformed");
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
