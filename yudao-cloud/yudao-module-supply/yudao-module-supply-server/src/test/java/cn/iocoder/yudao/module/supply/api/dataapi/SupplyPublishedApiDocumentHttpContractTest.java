package cn.iocoder.yudao.module.supply.api.dataapi;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.config.YudaoWebSecurityConfigurerAdapter;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.handler.AccessDeniedHandlerImpl;
import cn.iocoder.yudao.framework.security.core.handler.AuthenticationEntryPointImpl;
import cn.iocoder.yudao.framework.security.core.rpc.LoginUserRequestInterceptor;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import cn.iocoder.yudao.module.supply.framework.security.config.SecurityConfiguration;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyApiBindingService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyPublishedApiDocumentService;
import feign.RequestTemplate;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际提供方 HTTP 映射、合法内部用户透传和 JSON 白名单合同；不以此证明对端服务身份已认证。
 */
class SupplyPublishedApiDocumentHttpContractTest {

    private static final long USER_ID = 9_007_199_254_740_993L;
    private static final long API_ID = Long.MAX_VALUE;
    private static final String PATH = SupplyDataApiApi.PREFIX + "/get-published-document";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;
    private SupplyPublishedApiDocumentService documents;
    private JsonMapper json;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // 业务替身不是本轮 HTTP 验证对象，直接登记实例，避免为 mock 的内部字段加载数据库或远端依赖。
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("bindingService", mock(SupplyApiBindingService.class));
            factory.registerSingleton("publishedDocumentService", mock(SupplyPublishedApiDocumentService.class));
        });
        context.register(Config.class);
        context.refresh();
        json = context.getBean(JsonMapper.class);
        documents = context.getBean(SupplyPublishedApiDocumentService.class);
        http = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void feignMethodPathAndNamedIdMatchActualProviderHttpContract() {
        var method = new SpringMvcContract().parseAndValidateMetadata(SupplyDataApiApi.class).stream()
                .filter(item -> item.configKey().contains("getPublishedApiDocument("))
                .findFirst().orElseThrow();
        assertThat(method.template().method()).isEqualTo("GET");
        assertThat(method.template().url()).startsWith(PATH);
        assertThat(method.indexToName()).containsKey(0);
        assertThat(method.indexToName().get(0)).containsExactly("id");
    }

    @Test
    void forwardedConsumerIdentityReachesProviderWithoutSupplyManagementPermission() throws Exception {
        when(documents.getPublishedApiDocument(API_ID)).thenAnswer(call -> {
            assertThat(SecurityFrameworkUtils.getLoginUserId()).isEqualTo(USER_ID);
            assertThat(SecurityFrameworkUtils.getLoginUserDeptId()).isEqualTo(88L);
            return document();
        });
        var response = http.perform(get(PATH).param("id", String.valueOf(API_ID))
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, forwardedUser()))
                .andExpect(status().isOk()).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.at("/data/id").asString()).isEqualTo(String.valueOf(API_ID));
        verify(documents).getPublishedApiDocument(API_ID);
        verifyNoInteractions(context.getBean(OAuth2TokenCommonApi.class), context.getBean(SecurityFrameworkService.class),
                context.getBean(DataApiService.class), context.getBean(SupplyApiBindingService.class));
    }

    @Test
    void rawHttpJsonRetainsLosslessIdsBooleanRequiredAndNarrowNestedFields() throws Exception {
        when(documents.getPublishedApiDocument(anyLong())).thenReturn(document());
        String raw = http.perform(get(PATH).param("id", String.valueOf(API_ID))
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, forwardedUser()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var body = json.readTree(raw);
        assertThat(body.at("/data/id").isTextual()).isTrue();
        assertThat(body.at("/data/activeReleaseId").isIntegralNumber()).isTrue();
        assertThat(body.at("/data/requestParams/0/required").isBoolean()).isTrue();
        assertThat(body.at("/data/requestParams/0").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("name", "type", "required", "description");
        assertThat(body.at("/data/responseFields/0").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("name", "type", "description");
        assertThat(raw).doesNotContain("sqlText", "forwardUrl", "draftJson", "configJson", "defaultValue",
                "exampleValue", "modelRule", "appSecret", "jdbcUrl", "password");
        // 物理绑定只属于内部 RPC 合同，资产浏览器接口仍须再次按批准列过滤并投影。
        assertThat(body.at("/data/outputFieldMapping/publicId").asString()).isEqualTo("Id");
    }

    @Test
    void absentLoginIsDeniedByActualSecurityChainBeforeDocumentService() throws Exception {
        var response = http.perform(get(PATH).param("id", "7"))
                .andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).get("code").asInt()).isEqualTo(401);
        verifyNoInteractions(documents);
    }

    @Test
    void malformedIdAndWrongHttpMethodDoNotReachDocumentService() throws Exception {
        var badId = http.perform(get(PATH).param("id", "invalid")
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, forwardedUser()))
                .andReturn().getResponse();
        assertThat(json.readTree(badId.getContentAsString()).get("code").asInt()).isEqualTo(400);
        var wrongMethod = http.perform(post(PATH).param("id", "7")
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, forwardedUser()))
                .andReturn().getResponse();
        assertThat(json.readTree(wrongMethod.getContentAsString()).get("code").asInt()).isEqualTo(405);
        verifyNoInteractions(documents);
    }

    private static String forwardedUser() {
        var callerRequest = new MockHttpServletRequest("GET", "/admin-api/asset/market/my-assets/api-document");
        callerRequest.setRemoteAddr("127.0.0.1");
        try {
            SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(USER_ID).setUserType(2)
                    .setInfo(Map.of(LoginUser.INFO_KEY_DEPT_ID, "88")), callerRequest);
            var template = new RequestTemplate();
            new LoginUserRequestInterceptor().apply(template);
            return template.headers().get(SecurityFrameworkUtils.LOGIN_USER_HEADER).iterator().next();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static SupplyPublishedApiDocumentRespDTO document() {
        var value = new SupplyPublishedApiDocumentRespDTO();
        value.setId(API_ID);
        value.setName("已批准接口文档");
        value.setPath("/citizens");
        value.setMethod("GET");
        value.setVersion("v1");
        value.setActiveReleaseId(70L);
        value.setContractHash("f".repeat(64));
        value.setDatabaseId(3L);
        value.setSchemaName("public");
        value.setTableName("citizen");
        value.setFieldMappingVerified(true);
        value.setOutputFieldMapping(Map.of("publicId", "Id"));
        var input = new SupplyPublishedApiDocumentRespDTO.RequestParam();
        input.setName("Id"); input.setType("INTEGER"); input.setRequired(true); input.setDescription("编号筛选");
        value.setRequestParams(List.of(input));
        var output = new SupplyPublishedApiDocumentRespDTO.ResponseField();
        output.setName("publicId"); output.setType("BIGINT"); output.setDescription("公开编号");
        value.setResponseFields(List.of(output));
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({YudaoWebSecurityConfigurerAdapter.class, SecurityConfiguration.class})
    static class Config implements WebMvcConfigurer {
        @Bean WebProperties webProperties() { return new WebProperties(); }
        @Bean WebFrameworkUtils webFrameworkUtils(WebProperties properties) {
            return new YudaoWebAutoConfiguration().webFrameworkUtils(properties);
        }
        @Bean SecurityProperties securityProperties() { return new SecurityProperties(); }
        @Bean JsonMapper jsonMapper() {
            var mapper = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
            JsonUtils.init(mapper);
            return mapper;
        }
        @Bean GlobalExceptionHandler exceptionHandler() {
            return new GlobalExceptionHandler("supply-http-contract-test", mock(ApiErrorLogCommonApi.class));
        }
        @Bean AuthenticationEntryPoint authenticationEntryPoint() { return new AuthenticationEntryPointImpl(); }
        @Bean AccessDeniedHandler accessDeniedHandler() { return new AccessDeniedHandlerImpl(); }
        @Bean AuthenticationProvider unusedAuthenticationProvider() { return mock(AuthenticationProvider.class); }
        @Bean OAuth2TokenCommonApi oauth2TokenCommonApi() { return mock(OAuth2TokenCommonApi.class); }
        @Bean TokenAuthenticationFilter tokenFilter(SecurityProperties properties, GlobalExceptionHandler handler,
                OAuth2TokenCommonApi oauth) { return new TokenAuthenticationFilter(properties, handler, oauth); }
        @Bean("ss") SecurityFrameworkService permissionService() { return mock(SecurityFrameworkService.class); }
        @Bean DataApiService dataApiService() { return mock(DataApiService.class); }
        @Bean SupplyDataApiApiImpl endpoint() { return new SupplyDataApiApiImpl(); }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new JacksonJsonHttpMessageConverter(jsonMapper()));
        }
    }
}
