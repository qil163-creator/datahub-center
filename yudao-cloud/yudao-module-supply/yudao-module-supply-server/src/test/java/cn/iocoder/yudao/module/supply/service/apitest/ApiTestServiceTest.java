package cn.iocoder.yudao.module.supply.service.apitest;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.http.SafeHttpExecutor;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.ApiTestController;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestKeyValueVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apicategory.ApiCategoryDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.service.apicategory.ApiCategoryService;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTestServiceTest {
    private DataApiService dataApiService;
    private ApiCategoryService categoryService;
    private SecurityFrameworkService securityService;
    private ApiTestServiceImpl service;

    @BeforeEach
    void setup() {
        dataApiService = mock(DataApiService.class);
        categoryService = mock(ApiCategoryService.class);
        securityService = mock(SecurityFrameworkService.class);
        when(securityService.hasPermission("supply:data-api:invoke")).thenReturn(true);
        when(dataApiService.getPublicBaseUrl()).thenReturn("https://gateway.example.test/supply/gateway");
        service = new ApiTestServiceImpl();
        ReflectionTestUtils.setField(service, "dataApiService", dataApiService);
        ReflectionTestUtils.setField(service, "apiCategoryService", categoryService);
        ReflectionTestUtils.setField(service, "securityFrameworkService", securityService);
    }


    @Test
    void registeredRequestUsesPublishedTargetAndExplicitHeadersWithoutAdminContext() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/supply/gateway/services/v1/orders", exchange -> {
            observed.set(exchange.getRequestMethod() + "|" + exchange.getRequestURI().getRawQuery() + "|"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization") + "|"
                    + exchange.getRequestHeaders().getFirst("Cookie") + "|"
                    + exchange.getRequestHeaders().getFirst("tenant-id") + "|"
                    + exchange.getRequestHeaders().getFirst("login-user"));
            exchange.getResponseHeaders().add("Set-Cookie", "one=1; HttpOnly");
            exchange.getResponseHeaders().add("Set-Cookie", "two=2; HttpOnly");
            byte[] response = "真实响应".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(418, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            when(dataApiService.getPublicBaseUrl()).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/supply/gateway");
            when(dataApiService.getPublishedDataApiEntity(1L)).thenReturn(api(1L));
            ApiTestSendReqVO request = request("REGISTERED", "POST");
            request.setApiId(1L);
            request.setUrl("http://169.254.169.254/ignored");
            request.setQuery(List.of(new ApiTestKeyValueVO("城市", "上海"),
                    new ApiTestKeyValueVO("q", "a b"), new ApiTestKeyValueVO("q", "*~")));
            request.setHeaders(List.of(new ApiTestKeyValueVO("Authorization", "Bearer explicit-test-value"),
                    new ApiTestKeyValueVO("Cookie", "explicit=1"),
                    new ApiTestKeyValueVO("Content-Type", "text/plain")));
            request.setBody("请求内容");
            var response = service.send(request);
            assertThat(observed.get()).isEqualTo("POST|%E5%9F%8E%E5%B8%82=%E4%B8%8A%E6%B5%B7&q=a%20b&q=%2A~"
                    + "|请求内容|Bearer explicit-test-value|explicit=1|null|null");
            assertThat(response.getStatusCode()).isEqualTo(418);
            assertThat(response.getBody()).isEqualTo("真实响应");
            assertThat(response.getBodyEncoding()).isEqualTo("UTF8");
            assertThat(response.getBodyBytes()).isEqualTo("真实响应".getBytes(StandardCharsets.UTF_8).length);
            assertThat(response.getHeaders().stream().filter(header -> "Set-Cookie".equalsIgnoreCase(header.getName())))
                    .hasSize(2);
            assertThat(response.getUrl()).startsWith("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalModeCannotPromoteLoopbackToTrustedTarget() {
        ApiTestSendReqVO request = request("EXTERNAL", "GET");
        request.setApiId(1L);
        request.setUrl("http://127.0.0.1:9/secret");
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(ServiceException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void detailOnlyExposesPublishedTestDefinition() throws Exception {
        DataApiDO api = api(9_007_199_254_740_993L);
        api.setSqlText("SELECT private_column FROM private_table");
        api.setForwardUrl("https://private-config.example.test");
        api.setRequestParamsJson("[{\"name\":\"id\",\"paramType\":\"Long\",\"nullable\":0,"
                + "\"exampleValue\":\"9007199254740993\",\"description\":\"编号\"}]");
        when(dataApiService.getPublishedDataApiEntity(api.getId())).thenReturn(api);
        var response = service.getDetail(api.getId());
        assertThat(response.getRequestParams()).hasSize(1);
        assertThat(response.getRequestParams().getFirst().getExampleValue()).isEqualTo("9007199254740993");
        assertThat(response.getPublicUrl()).isEqualTo("https://gateway.example.test/supply/gateway/services/v1/orders");
        var mapper = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        String json = mapper.writeValueAsString(response);
        assertThat(json)
                .contains("9007199254740993").doesNotContain("sqlText", "forwardUrl", "private_table", "databaseId");
        assertThat(mapper.readTree(json).get("id").isString()).isTrue();
        assertThat(mapper.readTree(json).get("id").asString()).isEqualTo("9007199254740993");
        ApiTestSendReqVO request = mapper.readValue("{\"mode\":\"REGISTERED\",\"method\":\"GET\","
                + "\"apiId\":\"9007199254740993\"}", ApiTestSendReqVO.class);
        assertThat(request.getApiId()).isEqualTo(9_007_199_254_740_993L);
    }

    @Test
    void catalogUsesTestScopeAndFiltersOfflineDefinitions() {
        var first = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSimpleRespVO();
        first.setId(1L);
        var second = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSimpleRespVO();
        second.setId(2L);
        var offline = new cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSimpleRespVO();
        offline.setId(3L);
        DataApiDO offlineApi = api(3L);
        offlineApi.setStatus(DataApiDO.STATUS_OFFLINE);
        when(dataApiService.getDataApiSimpleList(DataApiDO.STATUS_ONLINE, "test"))
                .thenReturn(List.of(first, second, offline));
        when(dataApiService.getPublishedDataApiEntity(1L)).thenReturn(api(1L));
        when(dataApiService.getPublishedDataApiEntity(2L)).thenReturn(api(2L));
        when(dataApiService.getPublishedDataApiEntity(3L)).thenReturn(offlineApi);
        when(categoryService.getApiCategoryList(any())).thenReturn(
                List.of(ApiCategoryDO.builder().id(10L).parentId(0L).name("目录").build()));
        var response = service.getCatalog();
        assertThat(response.getApis()).extracting("id").containsExactly(1L, 2L);
        assertThat(response.getCategories()).extracting("name").containsExactly("目录");
    }

    @Test
    void missingPermissionCannotBypassTestScope() {
        when(securityService.hasPermission("supply:data-api:invoke")).thenReturn(false);
        assertThatThrownBy(service::getCatalog).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.send(request("EXTERNAL", "GET"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void detailAndRegisteredSendRejectMissingAndOfflineApi() {
        when(dataApiService.getPublishedDataApiEntity(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.getDetail(1L)).isInstanceOf(ServiceException.class);
        DataApiDO offline = api(1L);
        offline.setStatus(DataApiDO.STATUS_OFFLINE);
        when(dataApiService.getPublishedDataApiEntity(1L)).thenReturn(offline);
        ApiTestSendReqVO request = request("REGISTERED", "GET");
        request.setApiId(1L);
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant-id", "LOGIN-USER", "visit-tenant-id", "data-permission-enable", "X-Forwarded-For", "Forwarded", "tag",
            "WL-Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR"})
    void internalHeadersAreRejectedBeforeHttpConnection(String header) {
        ApiTestSendReqVO request = request("EXTERNAL", "GET");
        request.setUrl("https://example.test/secret");
        request.setHeaders(List.of(new ApiTestKeyValueVO(header, "sensitive-value")));
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(ServiceException.class)
                .hasMessageNotContaining("sensitive-value").hasMessageNotContaining("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/../../admin-api", "/%2e%2e/admin-api", "/%252e%252e/admin-api", "/orders?x=y", "//rpc-api", "/a%2fb",
            "/..;x/rpc-api", "/..%3Bx/rpc-api", "/%252e%252e%253Bx/rpc-api"})
    void registeredPathCannotEscapeThePublicGateway(String path) {
        DataApiDO api = api(1L);
        api.setPath(path);
        assertThatThrownBy(() -> service.registeredTarget(api)).isInstanceOf(ServiceException.class);
    }

    @Test
    void localFallbackUsesActualWebServerPortAndConfiguredContextPath() {
        when(dataApiService.getPublicBaseUrl()).thenReturn("");
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        WebServer server = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(18082);
        when(context.getEnvironment()).thenReturn(new MockEnvironment().withProperty("server.servlet.context-path", "/datahub"));
        ReflectionTestUtils.setField(service, "applicationContext", context);
        assertThat(service.registeredTarget(api(1L)))
                .isEqualTo("http://127.0.0.1:18082/datahub/supply/gateway/services/v1/orders");
    }

    @Test
    void queryPreservesExistingQueryOrderDuplicateNamesAndUtf8() {
        assertThat(ApiTestServiceImpl.appendQuery("https://example.test/path?a=one", List.of(
                new ApiTestKeyValueVO("a", "two"), new ApiTestKeyValueVO("城市", "上海 空"))))
                .isEqualTo("https://example.test/path?a=one&a=two&%E5%9F%8E%E5%B8%82=%E4%B8%8A%E6%B5%B7%20%E7%A9%BA");
    }

    @Test
    void binaryResponseUsesBase64WithoutLosingBytes() {
        var response = ApiTestServiceImpl.convertResponse(new SafeHttpExecutor.Result(200, "OK", List.of(),
                new byte[]{0, (byte) 0xff, 1}, 5L, true, "https://example.test", List.of()));
        assertThat(response.getBodyEncoding()).isEqualTo("BASE64");
        assertThat(response.getBody()).isEqualTo("AP8B");
        assertThat(response.getBodyBytes()).isEqualTo(3);
        assertThat(response.getTruncated()).isTrue();
    }

    @Test
    void minimalSpringContextWiresThinAdapterAndController() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataApiService.class, () -> dataApiService);
            context.registerBean(ApiCategoryService.class, () -> categoryService);
            context.registerBean(SecurityFrameworkService.class, () -> securityService);
            context.register(ApiTestServiceImpl.class, ApiTestController.class);
            context.refresh();
            assertThat(context.getBean(ApiTestController.class)).isNotNull();
            assertThat(context.getBean(ApiTestService.class)).isInstanceOf(ApiTestServiceImpl.class);
        }
    }

    @Test
    void sensitiveEndpointBodiesAreExcludedFromAccessLogsAndEveryEndpointRequiresInvokePermission() throws Exception {
        for (String method : List.of("getDetail", "send")) {
            var endpoint = method.equals("send") ? ApiTestController.class.getMethod(method, ApiTestSendReqVO.class)
                    : ApiTestController.class.getMethod(method, Long.class);
            ApiAccessLog logging = endpoint.getAnnotation(ApiAccessLog.class);
            assertThat(logging.requestEnable()).isFalse();
            assertThat(logging.responseEnable()).isFalse();
        }
        for (var endpoint : ApiTestController.class.getDeclaredMethods()) {
            assertThat(endpoint.getAnnotation(PreAuthorize.class).value()).contains("supply:data-api:invoke");
        }
        var gatewayEndpoint = cn.iocoder.yudao.module.supply.controller.open.GatewayController.class.getMethod("invoke",
                String.class, jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class, String.class);
        assertThat(gatewayEndpoint.getAnnotation(ApiAccessLog.class).requestEnable()).isFalse();
        assertThat(gatewayEndpoint.getAnnotation(ApiAccessLog.class).responseEnable()).isFalse();
    }

    private static ApiTestSendReqVO request(String mode, String method) {
        ApiTestSendReqVO request = new ApiTestSendReqVO();
        request.setMode(mode);
        request.setMethod(method);
        return request;
    }

    private static DataApiDO api(Long id) {
        return DataApiDO.builder().id(id).categoryId(10L).status(DataApiDO.STATUS_ONLINE)
                .name("订单接口").version("v1").path("/orders").method("POST")
                .configType(DataApiDO.CONFIG_TYPE_TABLE).resultFormat(DataApiDO.RESULT_FORMAT_LIST)
                .authType(DataApiDO.AUTH_TYPE_APPKEY).build();
    }
}
