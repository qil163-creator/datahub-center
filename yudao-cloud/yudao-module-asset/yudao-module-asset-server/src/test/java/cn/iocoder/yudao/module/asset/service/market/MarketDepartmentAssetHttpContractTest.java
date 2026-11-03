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
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketDepartmentAssetController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实权限代理、查询参数验证及无损传输；部门与个人交付隔离由服务及真实数据库测试覆盖。 */
class MarketDepartmentAssetHttpContractTest {
    private static final String PREFIX = "/admin-api/asset/market/department-assets";
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private JsonMapper json;
    private MarketDepartmentAssetService service;

    @BeforeEach
    void configure() {
        json = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(MarketDepartmentAssetService.class);
        when(context.getBean(PermissionCommonApi.class).hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            long actor = call.getArgument(0); String[] permissions = (String[]) call.getRawArguments()[1];
            return CommonResult.success(actor == 9 && Arrays.asList(permissions).contains("asset:market:query"));
        });
        login(9L);
        when(service.getPage(any())).thenReturn(PageResult.empty());
        when(service.categories()).thenReturn(List.of());
        var row = new MarketDepartmentAssetRespVO(); row.setId(9007199254740993L); row.setDisplayId("21");
        row.setApplyTime(LocalDateTime.of(2026, 10, 22, 22, 25));
        var detail = new MarketDepartmentAssetDetailRespVO(); detail.setAsset(row);
        when(service.get(9007199254740993L)).thenReturn(detail);
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(MarketDepartmentAssetController.class))
                .setCustomHandlerMapping(() -> new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties()).getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("department-assets-http", mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
    }

    @AfterEach
    void close() { SecurityContextHolder.clearContext(); validator.close(); context.close(); }

    @Test
    void originalQueryPermissionAdmitsAllReadRoutesAndPreservesLargeIdAndTime() throws Exception {
        assertThat(request(get(PREFIX + "/page")).get("code").intValue()).isZero();
        assertThat(request(get(PREFIX + "/categories")).get("code").intValue()).isZero();
        var detail = request(get(PREFIX + "/get").param("id", "9007199254740993"));
        assertThat(detail.get("data").get("asset").get("id").asString()).isEqualTo("9007199254740993");
        assertThat(detail.get("data").get("asset").get("applyTime").isNumber()).isTrue();
        assertThat(detail.get("data").get("canInvoke").booleanValue()).isFalse();
        assertThat(request(get(PREFIX + "/preview").param("id", "7")).get("code").intValue()).isZero();
        assertThat(request(get(PREFIX + "/api-document").param("id", "7")).get("code").intValue()).isZero();
        verify(service).preview(7L, 20);
    }

    @Test
    void anotherPermissionCannotReplaceTheApplicationQueryGate() throws Exception {
        login(10L);
        for (String suffix : List.of("/page", "/categories", "/get?id=7", "/preview?id=7", "/api-document?id=7"))
            assertThat(request(get(PREFIX + suffix)).get("code").intValue()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void invalidPagingSortDeliveryCategoryIdentifiersAndVerbAreRejectedBeforeService() throws Exception {
        for (String[] pair : List.of(new String[]{"pageNo", "0"}, new String[]{"pageSize", "201"},
                new String[]{"sortField", "id;drop table"}, new String[]{"sortOrder", "raw"},
                new String[]{"deliveryType", "4"}, new String[]{"marketCategoryId", "0"}))
            assertThat(request(get(PREFIX + "/page").param(pair[0], pair[1])).get("code").intValue()).isEqualTo(400);
        for (String path : List.of("/get?id=0", "/get?id=9223372036854775808", "/preview?id=7&limit=101", "/preview?id=7&limit=0"))
            assertThat(request(get(PREFIX + path)).get("code").intValue()).isEqualTo(400);
        assertThat(request(post(PREFIX + "/get").param("id", "7")).get("code").intValue()).isEqualTo(405);
        verifyNoInteractions(service);
    }

    private JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser().setId(id), null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean static MethodValidationPostProcessor methodValidation() {
            var processor = new MethodValidationPostProcessor(); processor.setProxyTargetClass(true); return processor;
        }
        @Bean PermissionCommonApi permissionApi() { return mock(PermissionCommonApi.class); }
        @Bean("ss") SecurityFrameworkService security(PermissionCommonApi api) { return new SecurityFrameworkServiceImpl(api); }
        @Bean MarketDepartmentAssetService service() { return mock(MarketDepartmentAssetService.class); }
        @Bean MarketDepartmentAssetController controller() { return new MarketDepartmentAssetController(); }
    }
}
