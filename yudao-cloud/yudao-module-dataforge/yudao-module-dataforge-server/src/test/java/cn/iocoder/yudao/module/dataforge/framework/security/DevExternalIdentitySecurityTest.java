package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.datapermission.core.aop.DataPermissionContextHolder;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRpcWebFilter;
import cn.iocoder.yudao.framework.env.core.context.EnvContextHolder;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.env.core.web.EnvWebFilter;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.ExternalIdentityHeadersFilter;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.DevDirController;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.DevRecordController;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.DevTaskController;
import cn.iocoder.yudao.module.dataforge.framework.security.config.DevExternalIdentityConfiguration;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual Spring Security chain plus the production external/tenant/data-permission filters. */
class DevExternalIdentitySecurityTest {
    private static final List<String> ROUTES = List.of("/dataforge/dev-task", "/dataforge/dev-dir", "/dataforge/dev-record");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private OAuth2TokenCommonApi api;
    private TenantFrameworkService tenants;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(WebConfiguration.class); context.refresh();
        api = context.getBean(OAuth2TokenCommonApi.class); tenants = context.getBean(TenantFrameworkService.class);
        var external = context.getBean(DevExternalIdentityConfiguration.class)
                .devExternalIdentityFilter(context.getBean(WebProperties.class));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilter(external.getFilter(), external.getUrlPatterns().toArray(String[]::new))
                .addFilters(new EnvWebFilter(), new DataPermissionRpcWebFilter(),
                new TenantContextWebFilter(), context.getBean(FilterChainProxy.class),
                new TenantSecurityWebFilter(context.getBean(WebProperties.class), new TenantProperties(), Set.of(),
                        context.getBean(GlobalExceptionHandler.class), tenants)).build();
    }
    @AfterEach void cleanup() {
        if (context != null) { context.close(); }
        SecurityContextHolder.clearContext(); TenantContextHolder.clear(); DataPermissionContextHolder.clear(); EnvContextHolder.removeTag();
    }

    @Test void registrationCoversEveryRealDevelopmentControllerBeforeContextAndSecurity() {
        var config = context.getBean(DevExternalIdentityConfiguration.class);
        var web = context.getBean(WebProperties.class); var registration = config.devExternalIdentityFilter(web);
        assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 1);
        assertTrue(registration.getOrder() < WebFilterOrderEnum.ENV_TAG_FILTER);
        assertEquals(Set.of("/admin-api/dataforge/dev-task/*", "/admin-api/dataforge/dev-dir/*",
                "/admin-api/dataforge/dev-record/*"), Set.copyOf(registration.getUrlPatterns()));
        for (var controller : List.of(DevTaskController.class, DevDirController.class, DevRecordController.class)) {
            String route = controller.getAnnotation(RequestMapping.class).value()[0];
            assertTrue(registration.getUrlPatterns().contains(web.getAdminApi().getPrefix() + route + "/*"));
        }
        web.getAdminApi().setPrefix("/custom-admin");
        assertEquals(Set.of("/custom-admin/dataforge/dev-task/*", "/custom-admin/dataforge/dev-dir/*",
                "/custom-admin/dataforge/dev-record/*"), Set.copyOf(config.devExternalIdentityFilter(web).getUrlPatterns()));
    }

    @Test void directForgedActorTenantAndBypassHeadersCannotReachAnyDevelopmentEntry() throws Exception {
        for (String route : ROUTES) {
            mvc.perform(get("/admin-api" + route + "/identity")
                    .header("login-user", "{\"id\":99,\"tenantId\":99,\"userType\":2}")
                    .header("tenant-id", "99").header("visit-tenant-id", "99")
                    .header("data-permission-enable", "false").header(EnvUtils.HEADER_TAG, "fixture-other-environment"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(api, tenants);
    }

    @Test void gatewayBearerIsRevalidatedAndRestoresOnlyItsActualActorAndTenant() throws Exception {
        var identity = new OAuth2AccessTokenCheckRespDTO();
        identity.setUserId(42L); identity.setUserType(2); identity.setTenantId(7L); identity.setScopes(List.of());
        when(api.checkAccessToken("fixture-token")).thenAnswer(invocation -> {
            assertNull(EnvContextHolder.getTag()); assertNull(TenantContextHolder.getTenantId());
            return CommonResult.success(identity);
        });
        for (String route : ROUTES) {
            mvc.perform(get("/admin-api" + route + "/identity").header("Authorization", "Bearer fixture-token")
                    .header("login-user", "{\"id\":99,\"tenantId\":99,\"userType\":2}")
                    .header("tenant-id", "99").header("visit-tenant-id", "99")
                    .header("data-permission-enable", "false").header(EnvUtils.HEADER_TAG, "fixture-other-environment"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value(42))
                    .andExpect(jsonPath("$.tenant").value(7)).andExpect(jsonPath("$.dataPermissionEnabled").value(true));
        }
        verify(api, times(3)).checkAccessToken("fixture-token"); verify(tenants, times(3)).validTenant(7L);
        assertNull(TenantContextHolder.getTenantId()); assertNull(SecurityFrameworkUtils.getLoginUser());
        assertNull(EnvContextHolder.getTag());
    }

    @Test void invalidRealTokenAndLocalMockTokenBothFailClosed() throws Exception {
        when(api.checkAccessToken(anyString())).thenReturn(CommonResult.success(null));
        for (String value : List.of("fixture-invalid", "fixture-mock-99")) {
            mvc.perform(get("/admin-api/dataforge/dev-task/identity").header("Authorization", "Bearer " + value)
                    .header("tenant-id", "99").header("login-user", "{\"id\":99,\"tenantId\":99,\"userType\":2}"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(tenants);
    }

    @Test void servletPatternScopeKeepsOtherRoutesOutsideTheExternalBoundary() throws Exception {
        mvc.perform(get("/rpc-api/fixture/identity")
                .header("login-user", "{\"id\":99,\"tenantId\":99,\"userType\":2}")
                .header(EnvUtils.HEADER_TAG, "fixture-internal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value(99))
                .andExpect(jsonPath("$.environment").value("fixture-internal"));
        verifyNoInteractions(api, tenants);
        assertNull(EnvContextHolder.getTag());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc @EnableWebSecurity
    @Import({DevExternalIdentityConfiguration.class, IdentityProbe.class})
    static class WebConfiguration {
        @Bean WebProperties webProperties() { return new WebProperties(); }
        @Bean WebFrameworkUtils webFrameworkUtils(WebProperties properties) { return new WebFrameworkUtils(properties); }
        @Bean OAuth2TokenCommonApi tokens() { return mock(OAuth2TokenCommonApi.class); }
        @Bean TenantFrameworkService tenants() { return mock(TenantFrameworkService.class); }
        @Bean GlobalExceptionHandler exceptions() { return mock(GlobalExceptionHandler.class); }
        @Bean TokenAuthenticationFilter tokenFilter(OAuth2TokenCommonApi api, GlobalExceptionHandler exceptions) {
            var properties = new SecurityProperties(); properties.setMockEnable(true); properties.setMockSecret("fixture-mock-");
            return new TokenAuthenticationFilter(properties, exceptions, api);
        }
        @Bean SecurityFilterChain security(HttpSecurity http, TokenAuthenticationFilter token) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.sendError(401)))
                    .addFilterBefore(token, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }
    }

    @RestController
    static class IdentityProbe {
        @GetMapping({"/admin-api/dataforge/dev-task/identity", "/admin-api/dataforge/dev-dir/identity", "/admin-api/dataforge/dev-record/identity"})
        Map<String, Object> identity(HttpServletRequest request) {
            assertNull(request.getHeader("login-user")); assertNull(request.getHeader("tenant-id"));
            assertNull(request.getHeader("visit-tenant-id")); assertNull(request.getHeader("data-permission-enable"));
            assertNull(request.getHeader(EnvUtils.HEADER_TAG)); assertNull(EnvContextHolder.getTag());
            assertNull(SecurityFrameworkUtils.getLoginUser().getVisitTenantId());
            return Map.of("actor", SecurityFrameworkUtils.getLoginUserId(), "tenant", TenantContextHolder.getRequiredTenantId(),
                    "dataPermissionEnabled", DataPermissionContextHolder.get() == null);
        }
        @GetMapping("/rpc-api/fixture/identity")
        Map<String, Object> internalIdentity() {
            return Map.of("actor", SecurityFrameworkUtils.getLoginUserId(), "environment", EnvContextHolder.getTag());
        }
    }
}
