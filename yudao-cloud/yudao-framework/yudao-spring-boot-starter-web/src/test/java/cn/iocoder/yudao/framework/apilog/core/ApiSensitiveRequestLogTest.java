package cn.iocoder.yudao.framework.apilog.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.apilog.core.filter.ApiAccessLogFilter;
import cn.iocoder.yudao.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import cn.iocoder.yudao.framework.apilog.core.util.ApiLogUtils;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.spring.SpringUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiSensitiveRequestLogTest {

    private static final String SENTINEL = "NATIVE_CONFIG_LOG_SENTINEL";
    private final ApiAccessLogCommonApi accessApi = mock(ApiAccessLogCommonApi.class);
    private final ApiErrorLogCommonApi errorApi = mock(ApiErrorLogCommonApi.class);
    private final List<ApiAccessLogCreateReqDTO> accessRecords = new ArrayList<>();
    private final List<ApiErrorLogCreateReqDTO> errorRecords = new ArrayList<>();
    private final Map<Logger, Boolean> loggerAdditivity = new LinkedHashMap<>();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private MockedStatic<SpringUtils> springUtils;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
        appender.start();
        for (Class<?> type : List.of(ApiAccessLogInterceptor.class, ApiAccessLogFilter.class, GlobalExceptionHandler.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            loggerAdditivity.put(logger, logger.isAdditive());
            logger.setAdditive(false);
            logger.addAppender(appender);
        }
        springUtils = mockStatic(SpringUtils.class);
        springUtils.when(SpringUtils::isProd).thenReturn(false);
        doAnswer(call -> { accessRecords.add(call.getArgument(0)); return null; })
                .when(accessApi).createApiAccessLogAsync(any());
        doAnswer(call -> { errorRecords.add(call.getArgument(0)); return null; })
                .when(errorApi).createApiErrorLogAsync(any());
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler("test", errorApi), new GlobalResponseBodyHandler())
                .addInterceptors(new ApiAccessLogInterceptor())
                .addFilters(new CacheRequestBodyFilter(), new ApiAccessLogFilter(new WebProperties(), "test", accessApi))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (springUtils != null) springUtils.close();
        loggerAdditivity.forEach((logger, additive) -> {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
        });
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"source\":{\"Jdbc\":{\"password\":\"%s\"}}}",
            "source { Jdbc { password = \"%s\" } }"})
    void nativeJsonAndHoconBodiesAndQueryAreOmitted(String config) throws Exception {
        perform("/private", JsonUtils.toJsonString(Map.of("seatunnelConfig", config.formatted(SENTINEL))));

        assertThat(accessRecords).hasSize(1);
        assertThat(accessRecords.getFirst().getRequestParams()).isNull();
        assertThat(accessRecords.getFirst().getResponseBody()).isNull();
        assertThat(accessRecords.getFirst().getResultCode()).isZero();
        assertHidden();
    }

    @Test
    void nestedExceptionRetainsLocationWithoutRequestOrCauseText() throws Exception {
        perform("/private-failure", payload());

        assertThat(errorRecords).hasSize(1);
        ApiErrorLogCreateReqDTO error = errorRecords.getFirst();
        assertThat(error.getRequestParams()).isEqualTo("{}");
        assertThat(error.getExceptionName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(error.getExceptionMethodName()).isEqualTo("fail");
        assertThat(error.getExceptionStackTrace()).contains("TestController.fail");
        assertThat(accessRecords.getFirst().getResultCode()).isEqualTo(500);
        assertHidden();
    }

    @Test
    void malformedRequestIsOmittedBeforeControllerInvocation() throws Exception {
        perform("/private", "{\"seatunnelConfig\":\"" + SENTINEL);

        assertThat(accessRecords).hasSize(1);
        assertThat(accessRecords.getFirst().getResultCode()).isNotZero();
        assertHidden();
    }

    @Test
    void beanValidationDoesNotLogRejectedValue() throws Exception {
        perform("/private-validation", payload());

        assertThat(accessRecords.getFirst().getResultCode()).isEqualTo(400);
        assertThat(errorRecords).isEmpty();
        assertHidden();
    }

    @Test
    void resultMessageCannotEchoPrivateInputIntoAccessLog() throws Exception {
        perform("/private-result", payload());

        assertThat(accessRecords.getFirst().getResultCode()).isEqualTo(400);
        assertThat(accessRecords.getFirst().getResultMsg()).isNull();
        assertHidden();
    }

    @Test
    void failedLogDeliveryCannotReintroducePrivateInput() throws Exception {
        doThrow(new IllegalStateException(SENTINEL)).when(errorApi).createApiErrorLogAsync(any());
        doThrow(new IllegalStateException(SENTINEL)).when(accessApi).createApiAccessLogAsync(any());

        perform("/private-failure", payload());

        assertThat(renderedLogs()).contains("createExceptionLog", "createApiAccessLog");
        assertHidden();
    }

    @Test
    void endpointsWithoutAnnotationKeepExistingLogging() throws Exception {
        perform("/ordinary", payload());

        assertThat(accessRecords.getFirst().getRequestParams()).contains(SENTINEL);
        assertThat(errorRecords.getFirst().getRequestParams()).contains(SENTINEL);
        assertThat(errorRecords.getFirst().getExceptionRootCauseMessage()).contains(SENTINEL);
        assertThat(renderedLogs()).contains(SENTINEL);
    }

    @Test
    void ordinaryPermissionErrorKeepsResultMessage() throws Exception {
        perform("/ordinary-permission", payload());

        assertThat(accessRecords.getFirst().getResultCode()).isEqualTo(403);
        assertThat(accessRecords.getFirst().getResultMsg()).isEqualTo("权限不足");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "TRACE"})
    void earlySensitiveFlagProtectsAuthenticationAndMethodFailuresWithoutHandler(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/admin-api/supply/api-test/send");
        request.setContentType("application/json");
        request.setContent(JsonUtils.toJsonByte(Map.of("headers", List.of(Map.of("name", "Authorization", "value", SENTINEL)),
                "body", SENTINEL, "url", "https://example.test?credential=" + SENTINEL)));
        request.addParameter("credential", SENTINEL);
        ApiLogUtils.disableRequestLogging(request);
        new CacheRequestBodyFilter().doFilter(request, new MockHttpServletResponse(), (cached, response) ->
                new ApiAccessLogFilter(new WebProperties(), "test", accessApi).doFilter(cached, response, (inner, output) -> {
                    cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils.setCommonResult(
                            (jakarta.servlet.http.HttpServletRequest) inner, CommonResult.error(401, SENTINEL));
                    ((jakarta.servlet.http.HttpServletResponse) output).setStatus(401);
                }));
        assertThat(accessRecords).hasSize(1);
        assertThat(accessRecords.getFirst().getRequestParams()).isNull();
        assertThat(accessRecords.getFirst().getResultMsg()).isNull();
        assertHidden();
    }

    @Test
    void earlySensitiveFlagAlsoProtectsErrorLogRpcWithoutHandler() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/supply/api-test/send");
        request.addParameter("credential", SENTINEL);
        request.setContent(SENTINEL.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ApiLogUtils.disableRequestLogging(request);
        new GlobalExceptionHandler("test", errorApi).defaultExceptionHandler(request,
                new IllegalStateException(SENTINEL, new IllegalArgumentException(SENTINEL)));
        assertThat(errorRecords).hasSize(1);
        assertThat(errorRecords.getFirst().getRequestParams()).isEqualTo("{}");
        assertHidden();
    }

    private void perform(String route, String body) throws Exception {
        mvc.perform(post("/admin-api/log-test" + route).contentType("application/json")
                .requestAttr("login_user_type", 2).requestAttr("login_user_id", 123L)
                .param("connection", SENTINEL).content(body)).andExpect(status().isOk());
    }

    private String payload() {
        return JsonUtils.toJsonString(Map.of("seatunnelConfig", SENTINEL));
    }

    private void assertHidden() {
        assertThat(JsonUtils.toJsonString(accessRecords)).doesNotContain(SENTINEL);
        assertThat(JsonUtils.toJsonString(errorRecords)).doesNotContain(SENTINEL);
        assertThat(renderedLogs()).doesNotContain(SENTINEL);
    }

    private String renderedLogs() {
        return appender.list.stream().map(event -> event.getFormattedMessage()
                + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .reduce("", String::concat);
    }

    public record JobRequest(String seatunnelConfig) { }
    public record InvalidJobRequest(@Size(max = 1) String seatunnelConfig) { }

    @RestController
    static class TestController {
        @PostMapping("/admin-api/log-test/private")
        @ApiAccessLog(requestEnable = false)
        public CommonResult<Boolean> submit(@RequestBody JobRequest request) {
            return CommonResult.success(true);
        }

        @PostMapping("/admin-api/log-test/private-failure")
        @ApiAccessLog(requestEnable = false)
        public CommonResult<Boolean> fail(@RequestBody JobRequest request) {
            throw new IllegalStateException(request.seatunnelConfig(), new IllegalArgumentException(SENTINEL));
        }

        @PostMapping("/admin-api/log-test/private-validation")
        @ApiAccessLog(requestEnable = false)
        public CommonResult<Boolean> validate(@Valid @RequestBody InvalidJobRequest request) {
            return CommonResult.success(true);
        }

        @PostMapping("/admin-api/log-test/private-result")
        @ApiAccessLog(requestEnable = false)
        public CommonResult<Boolean> echoError(@RequestBody JobRequest request) {
            return CommonResult.error(400, request.seatunnelConfig());
        }

        @PostMapping("/admin-api/log-test/ordinary")
        public CommonResult<Boolean> ordinary(@RequestBody JobRequest request) {
            throw new IllegalStateException(request.seatunnelConfig());
        }

        @PostMapping("/admin-api/log-test/ordinary-permission")
        public CommonResult<Boolean> ordinaryPermission(@RequestBody JobRequest request) {
            return CommonResult.error(403, "权限不足");
        }
    }
}
