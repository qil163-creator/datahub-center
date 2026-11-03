package cn.iocoder.yudao.module.governance.framework.security;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.security.SafetyClassificationApi;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermClientConfiguration;
import cn.iocoder.yudao.module.governance.controller.admin.security.SensitiveClassificationReferenceController;
import cn.iocoder.yudao.module.governance.framework.security.config.SensitiveClassificationReferenceSecurityConfiguration;
import cn.iocoder.yudao.module.governance.service.category.SafetyClassificationReferenceService;
import feign.Feign;
import feign.Request;
import feign.Response;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实令牌过滤器、方法权限代理和共享 MVC 前缀验证无需 TLS 配置的只读引用入口。 */
class SensitiveClassificationReferenceSecurityTest {

    private static final long ID = 9007199254740993L;
    private static final String PATH = "/admin-api" + SensitiveClassificationReferenceApi.PREFIX;
    private static final List<String> CONTROL_HEADERS = List.of(SecurityFrameworkUtils.LOGIN_USER_HEADER,
            WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
            DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG);

    private final OAuth2TokenCommonApi tokens = mock(OAuth2TokenCommonApi.class);
    private final PermissionCommonApi permissions = mock(PermissionCommonApi.class);
    private final SafetyClassificationReferenceService classifications = mock(SafetyClassificationReferenceService.class);
    private final Set<String> granted = new HashSet<>();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        new WebFrameworkUtils(new WebProperties());
        var identity = new OAuth2AccessTokenCheckRespDTO().setUserId(41L).setUserType(2);
        when(tokens.checkAccessToken(anyString())).thenAnswer(invocation ->
                CommonResult.success("valid-token".equals(invocation.getArgument(0)) ? identity : null));
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(invocation -> {
            assertThat((Long) invocation.getArgument(0)).isEqualTo(41L);
            var requested = (String[]) invocation.getRawArguments()[1];
            return CommonResult.success(Arrays.stream(requested).anyMatch(granted::contains));
        });
        var category = new SafetyClassificationRespDTO();
        category.setId("category:" + ID);
        category.setCategoryId(ID);
        category.setName("测站编码");
        when(classifications.list()).thenReturn(List.of(category));
        when(classifications.get(ID)).thenReturn(category);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(beans -> {
            beans.registerSingleton("tokens", tokens);
            beans.registerSingleton("permissions", permissions);
            beans.registerSingleton("classificationReferenceService", classifications);
        });
        context.register(SecurityConfiguration.class);
        context.refresh();
        var boundary = context.getBean(SensitiveClassificationReferenceSecurityConfiguration.class)
                .sensitiveClassificationReferenceIdentityFilter(context.getBean(WebProperties.class));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(boundary.getFilter(), context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void missingTokenAndForgedIdentityCannotReadEitherEndpoint() throws Exception {
        mvc.perform(forged(get(PATH + "/list"))).andExpect(status().isUnauthorized());
        mvc.perform(forged(get(PATH + "/get").param("id", String.valueOf(ID))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokens, permissions, classifications);
    }

    @Test
    void mockTokenCannotUseDevelopmentLoginFallback() throws Exception {
        mvc.perform(forged(get(PATH + "/list")).header("Authorization", "Bearer mock-99"))
                .andExpect(status().isUnauthorized());
        verify(tokens).checkAccessToken("mock-99");
        verifyNoInteractions(permissions, classifications);
    }

    @Test
    void verifiedTokenWithoutSensitiveListPermissionIsForbidden() throws Exception {
        mvc.perform(authorized(get(PATH + "/list"))).andExpect(status().isForbidden());
        mvc.perform(authorized(get(PATH + "/get").param("id", String.valueOf(ID))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(classifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "create", "update"})
    void eachExistingSensitiveListPermissionAllowsReadOnlyProjection(String action) throws Exception {
        granted.add("asset:sensitive-list:" + action);
        mvc.perform(authorized(get(PATH + "/list"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryId").value(String.valueOf(ID)));
        mvc.perform(authorized(get(PATH + "/get").param("id", String.valueOf(ID))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("测站编码"));
        verify(classifications).get(ID);
        verify(permissions).hasAnyPermissions(41L, "asset:sensitive-list:query",
                "asset:sensitive-list:create", "asset:sensitive-list:update");
        mvc.perform(authorized(get(PATH + "/mask-fields"))).andExpect(status().isNotFound());
    }

    @Test
    void malformedOrNonpositiveCategoryIdIsRejectedBeforeProviderRead() throws Exception {
        granted.add("asset:sensitive-list:query");
        for (String id : List.of("0", "-1", "not-an-id")) {
            mvc.perform(authorized(get(PATH + "/get").param("id", id))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(classifications);
    }

    @Test
    void boundaryStripsAllPrivilegedControlsBeforeContextFiltersAndKeepsBearerToken() throws Exception {
        var configuration = new SensitiveClassificationReferenceSecurityConfiguration();
        var registration = configuration.sensitiveClassificationReferenceIdentityFilter(new WebProperties());
        assertThat(registration.getUrlPatterns()).containsExactly(PATH + "/*");
        assertThat(registration.getOrder()).isLessThan(WebFilterOrderEnum.ENV_TAG_FILTER);
        var request = new MockHttpServletRequest();
        CONTROL_HEADERS.forEach(name -> request.addHeader(name, "forged"));
        request.addHeader("Authorization", "Bearer valid-token");
        registration.getFilter().doFilter(request, new MockHttpServletResponse(), (clean, response) -> {
            var http = (HttpServletRequest) clean;
            CONTROL_HEADERS.forEach(name -> assertThat(http.getHeader(name)).isNull());
            assertThat(http.getHeader("Authorization")).isEqualTo("Bearer valid-token");
        });
    }

    @Test
    void feignUsesAdminPrefixAndLosslessIdWithoutChangingProtectedMaskClient() {
        var annotation = SensitiveClassificationReferenceApi.class.getAnnotation(FeignClient.class);
        assertThat(annotation.contextId()).isEqualTo("sensitiveClassificationReferenceApi");
        assertThat(annotation.configuration()).isEmpty();
        String prefix = new MockEnvironment().resolveRequiredPlaceholders(annotation.path());
        assertThat(prefix).isEqualTo("/admin-api");
        var captured = new AtomicReference<Request>();
        var client = Feign.builder().contract(new SpringMvcContract()).decoder((response, type) -> CommonResult.success(null))
                .client((request, options) -> {
                    captured.set(request);
                    return Response.builder().request(request).status(200).reason("OK").headers(Map.of())
                            .body("{}", StandardCharsets.UTF_8).build();
                }).target(SensitiveClassificationReferenceApi.class, "http://governance.example.test" + prefix);
        client.getClassification(ID, "Bearer valid-token");
        assertThat(captured.get().url()).isEqualTo("http://governance.example.test" + PATH + "/get?id=" + ID);
        assertThat(captured.get().headers().get("Authorization")).containsExactly("Bearer valid-token");
        assertThat(SafetyClassificationApi.class.getAnnotation(FeignClient.class).configuration())
                .containsExactly(StandardTermClientConfiguration.class);
    }

    private MockHttpServletRequestBuilder forged(MockHttpServletRequestBuilder request) {
        for (String name : CONTROL_HEADERS) request.header(name, "forged");
        return request;
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return forged(request).header("Authorization", "Bearer valid-token");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity(proxyTargetClass = true)
    @ImportAutoConfiguration({WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, YudaoJacksonAutoConfiguration.class})
    @Import({SensitiveClassificationReferenceController.class, SensitiveClassificationReferenceSecurityConfiguration.class})
    static class SecurityConfiguration {
        @Bean
        WebProperties webProperties() {
            var properties = new WebProperties();
            var ui = new WebProperties.Ui();
            ui.setUrl("http://localhost");
            properties.setAdminUi(ui);
            return properties;
        }

        @Bean
        WebMvcRegistrations webMvcRegistrations(WebProperties properties) {
            return new YudaoWebAutoConfiguration().webMvcRegistrations(properties);
        }

        @Bean("ss")
        SecurityFrameworkService permissionService(PermissionCommonApi permissions) {
            return new SecurityFrameworkServiceImpl(permissions);
        }

        @Bean
        TokenAuthenticationFilter tokenFilter(OAuth2TokenCommonApi tokens) {
            var properties = new SecurityProperties();
            properties.setMockEnable(true);
            properties.setMockSecret("mock-");
            return new TokenAuthenticationFilter(properties, new GlobalExceptionHandler("fixture", null), tokens);
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors
                            .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                            .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                    .addFilterBefore(tokens, UsernamePasswordAuthenticationFilter.class).build();
        }
    }
}
