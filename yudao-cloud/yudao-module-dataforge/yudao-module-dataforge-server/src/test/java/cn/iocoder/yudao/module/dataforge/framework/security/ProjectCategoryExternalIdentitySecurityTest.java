package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.framework.security.config.ProjectCategoryExternalIdentityConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实安全链验证类目管理浏览器入口不信任内部身份头。 */
class ProjectCategoryExternalIdentitySecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private OAuth2TokenCommonApi tokens;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfiguration.class);
        context.refresh();
        tokens = context.getBean(OAuth2TokenCommonApi.class);
        var registration = context.getBean(ProjectCategoryExternalIdentityConfiguration.class)
                .projectCategoryExternalIdentityFilter(context.getBean(WebProperties.class));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(registration.getFilter(), registration.getUrlPatterns().toArray(String[]::new))
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void cleanup() {
        if (context != null) context.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void registrationTargetsOnlyCategoryManagementAndPrecedesContextFilters() {
        var configuration = context.getBean(ProjectCategoryExternalIdentityConfiguration.class);
        var properties = new WebProperties();
        var registration = configuration.projectCategoryExternalIdentityFilter(properties);
        assertEquals(Set.of("/admin-api/dataforge/project-category/*"), Set.copyOf(registration.getUrlPatterns()));
        assertTrue(registration.getOrder() < WebFilterOrderEnum.ENV_TAG_FILTER);
        assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
        properties.getAdminApi().setPrefix("/custom-admin");
        assertEquals(Set.of("/custom-admin/dataforge/project-category/*"),
                Set.copyOf(configuration.projectCategoryExternalIdentityFilter(properties).getUrlPatterns()));
    }

    @Test
    void forgedActorAndControlHeadersWithoutBearerCannotAuthenticate() throws Exception {
        mvc.perform(get("/admin-api/dataforge/project-category/identity")
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, "{\"id\":99,\"userType\":2}")
                        .header(WebFrameworkUtils.HEADER_TENANT_ID, "99")
                        .header(WebFrameworkUtils.HEADER_VISIT_TENANT_ID, "99")
                        .header(DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, "false")
                        .header(EnvUtils.HEADER_TAG, "forged-environment"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokens);
    }

    @Test
    void bearerRestoresVerifiedActorAndDiscardsEveryForgedControlHeader() throws Exception {
        var identity = new OAuth2AccessTokenCheckRespDTO();
        identity.setUserId(42L);
        identity.setUserType(2);
        identity.setScopes(List.of());
        when(tokens.checkAccessToken("verified-token")).thenReturn(CommonResult.success(identity));

        mvc.perform(get("/admin-api/dataforge/project-category/identity")
                        .header("Authorization", "Bearer verified-token")
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, "{\"id\":99,\"userType\":2}")
                        .header(WebFrameworkUtils.HEADER_TENANT_ID, "99")
                        .header(WebFrameworkUtils.HEADER_VISIT_TENANT_ID, "99")
                        .header(DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, "false")
                        .header(EnvUtils.HEADER_TAG, "forged-environment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value(42));
        verify(tokens).checkAccessToken("verified-token");
    }

    @Test
    void invalidBearerAndLocalMockTokenCannotBypassVerification() throws Exception {
        when(tokens.checkAccessToken(anyString())).thenReturn(CommonResult.success(null));
        for (String value : List.of("invalid-token", "fixture-mock-99")) {
            mvc.perform(get("/admin-api/dataforge/project-category/identity")
                            .header("Authorization", "Bearer " + value)
                            .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, "{\"id\":99,\"userType\":2}"))
                    .andExpect(status().isUnauthorized());
            verify(tokens).checkAccessToken(value);
        }
    }

    @Test
    void registrationDoesNotChangeAnUnrelatedInternalRoute() throws Exception {
        mvc.perform(get("/rpc-api/project-category-fixture/identity")
                        .header(SecurityFrameworkUtils.LOGIN_USER_HEADER, "{\"id\":99,\"userType\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value(99));
        verifyNoInteractions(tokens);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ProjectCategoryExternalIdentityConfiguration.class, IdentityProbe.class})
    static class WebConfiguration {
        @Bean WebProperties webProperties() { return new WebProperties(); }
        @Bean WebFrameworkUtils webFrameworkUtils(WebProperties properties) { return new WebFrameworkUtils(properties); }
        @Bean OAuth2TokenCommonApi tokens() { return mock(OAuth2TokenCommonApi.class); }
        @Bean GlobalExceptionHandler exceptions() { return mock(GlobalExceptionHandler.class); }
        @Bean TokenAuthenticationFilter tokenFilter(OAuth2TokenCommonApi api, GlobalExceptionHandler exceptions) {
            var properties = new SecurityProperties();
            properties.setMockEnable(true);
            properties.setMockSecret("fixture-mock-");
            return new TokenAuthenticationFilter(properties, exceptions, api);
        }
        @Bean SecurityFilterChain security(HttpSecurity http, TokenAuthenticationFilter token) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.sendError(401)))
                    .addFilterBefore(token, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }

    @RestController
    static class IdentityProbe {
        @GetMapping("/admin-api/dataforge/project-category/identity")
        Map<String, Object> externalIdentity(HttpServletRequest request) {
            for (String header : List.of(SecurityFrameworkUtils.LOGIN_USER_HEADER,
                    WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                    DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)) {
                assertNull(request.getHeader(header));
            }
            assertEquals("Bearer verified-token", request.getHeader("Authorization"));
            return Map.of("actor", SecurityFrameworkUtils.getLoginUserId());
        }

        @GetMapping("/rpc-api/project-category-fixture/identity")
        Map<String, Object> internalIdentity() {
            return Map.of("actor", SecurityFrameworkUtils.getLoginUserId());
        }
    }
}
