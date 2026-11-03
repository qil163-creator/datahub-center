package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketCheckController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckTicketRespVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实权限代理、参数验证和JSON线协议；领域编排由服务及PostgreSQL专测覆盖。 */
class MarketCheckHttpContractTest {
    private static final String PREFIX = "/admin-api/asset/market/check";
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private JsonMapper json;
    private MarketCheckService service;

    @BeforeEach
    void configureHttp() {
        json = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(MarketCheckService.class);
        var permissions = context.getBean(PermissionCommonApi.class);
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            long user = call.getArgument(0);
            String[] requested = (String[]) call.getRawArguments()[1];
            return CommonResult.success(user == 9 && java.util.Arrays.asList(requested).contains("asset:market:check"));
        });
        login(9L);
        when(service.getPage(any())).thenReturn(PageResult.empty());
        when(service.applicants()).thenReturn(List.of());
        var ticket = new MarketCheckTicketRespVO(); ticket.setId("9007199254740993"); ticket.setOrigin("QDATA");
        ticket.setApplyTime(LocalDateTime.of(2026, 1, 1, 1, 0)); ticket.setStatus("2");
        var detail = new MarketCheckDetailRespVO(); detail.setTicket(ticket);
        when(service.get("9007199254740993", "QDATA")).thenReturn(detail);
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(MarketCheckController.class))
                .setCustomHandlerMapping(() -> new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties()).getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("approval-http-contract", mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (validator != null) validator.close();
        if (context != null) context.close();
    }

    @Test
    void approvalPermissionAdmitsAllFourRoutesAndPreservesIdAndTimestampBytes() throws Exception {
        assertThat(request(get(PREFIX + "/page")).get("code").intValue()).isZero();
        assertThat(request(get(PREFIX + "/applicants")).get("code").intValue()).isZero();
        var detail = request(get(PREFIX + "/get").param("id", "9007199254740993").param("origin", "QDATA"));
        assertThat(detail.get("data").get("ticket").get("id").isString()).isTrue();
        assertThat(detail.get("data").get("ticket").get("id").asString()).isEqualTo("9007199254740993");
        assertThat(detail.get("data").get("ticket").get("applyTime").isNumber()).isTrue();
        assertThat(detail.get("data").get("canInvoke").booleanValue()).isFalse();
        assertThat(request(put(PREFIX + "/check").contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"9007199254740993","origin":"QDATA","status":"3","expectedRevision":1,"approvalReason":"意见"}
                """)).get("code").intValue()).isZero();
        verify(service).check(argThat(req -> req.getId().equals("9007199254740993") && req.getExpectedRevision() == 1));
    }

    @Test
    void missingOriginAndUnboundedOrUntrustedParametersAreRejectedBeforeService() throws Exception {
        assertThat(request(get(PREFIX + "/get").param("id", "21")).get("code").intValue()).isEqualTo(400);
        assertThat(request(get(PREFIX + "/get").param("id", "21").param("origin", "OTHER")).get("code").intValue()).isEqualTo(400);
        for (var parameter : List.of(new String[]{"pageNo", "0"}, new String[]{"pageSize", "201"},
                new String[]{"status", "0"}, new String[]{"sortBy", "id;drop table"}, new String[]{"sortOrder", "anything"})) {
            assertThat(request(get(PREFIX + "/page").param(parameter[0], parameter[1])).get("code").intValue()).isEqualTo(400);
        }
        assertThat(request(put(PREFIX + "/check").contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"21","origin":"QDATA","status":"3"}
                """)).get("code").intValue()).isEqualTo(400);
        assertThat(request(post(PREFIX + "/check")).get("code").intValue()).isEqualTo(405);
        verifyNoInteractions(service);
    }

    @Test
    void approvalReasonIsRequiredTrimmedAndLimitedBeforeService() throws Exception {
        for (String reason : new String[]{null, "", "   ", "字".repeat(257)}) {
            var body = json.createObjectNode().put("id", "21").put("origin", "QDATA")
                    .put("status", "3").put("expectedRevision", 1).put("approvalReason", reason);
            assertThat(request(put(PREFIX + "/check").contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString())).get("code").intValue()).isEqualTo(400);
        }
        verifyNoInteractions(service);
        var reason = "字".repeat(256);
        var body = json.createObjectNode().put("id", "21").put("origin", "QDATA")
                .put("status", "3").put("expectedRevision", 1).put("approvalReason", "  " + reason + "  ");
        assertThat(request(put(PREFIX + "/check").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).get("code").intValue()).isZero();
        verify(service).check(argThat(req -> req.getApprovalReason().equals(reason)));
    }

    @Test
    void userWithoutApprovalPermissionCannotReadGlobalSourceOrWriteOpinion() throws Exception {
        login(10L);
        for (String path : List.of("/page", "/applicants", "/get?id=21&origin=QDATA"))
            assertThat(request(get(PREFIX + path)).get("code").intValue()).isEqualTo(403);
        assertThat(request(put(PREFIX + "/check").contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"21","origin":"QDATA","status":"3","expectedRevision":1,"approvalReason":"意见"}
                """)).get("code").intValue()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    private JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(id), null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean static MethodValidationPostProcessor methodValidation() {
            var processor = new MethodValidationPostProcessor(); processor.setProxyTargetClass(true); return processor;
        }
        @Bean PermissionCommonApi permissionApi() { return mock(PermissionCommonApi.class); }
        @Bean("ss") SecurityFrameworkService security(PermissionCommonApi api) { return new SecurityFrameworkServiceImpl(api); }
        @Bean MarketCheckService service() { return mock(MarketCheckService.class); }
        @Bean MarketCheckController controller() { return new MarketCheckController(); }
    }
}
