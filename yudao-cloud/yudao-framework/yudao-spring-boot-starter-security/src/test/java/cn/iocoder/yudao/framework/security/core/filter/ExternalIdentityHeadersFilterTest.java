package cn.iocoder.yudao.framework.security.core.filter;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ExternalIdentityHeadersFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private MockHttpServletRequest request() {
        new WebFrameworkUtils(new WebProperties());
        var request = new MockHttpServletRequest(); request.setServletPath("/admin-api/probe");
        request.addHeader("LOGIN-USER", "{\"id\":99,\"tenantId\":99,\"userType\":2}");
        request.addHeader("Tenant-ID", "99"); request.addHeader("data-permission-enable", "false");
        return request;
    }
    private TokenAuthenticationFilter tokenFilter(SecurityProperties properties, OAuth2TokenCommonApi api) {
        return new TokenAuthenticationFilter(properties, new GlobalExceptionHandler("fixture", null), api);
    }
    private OAuth2TokenCommonApi tokenApi(List<String> checked, Function<String, OAuth2AccessTokenCheckRespDTO> identities) {
        return (OAuth2TokenCommonApi) Proxy.newProxyInstance(OAuth2TokenCommonApi.class.getClassLoader(),
                new Class<?>[]{OAuth2TokenCommonApi.class}, (proxy, method, args) -> {
                    if (!"checkAccessToken".equals(method.getName())) { throw new AssertionError("Unexpected OAuth method"); }
                    String token = (String) args[0]; checked.add(token);
                    return CommonResult.success(identities.apply(token));
                });
    }
    private ExternalIdentityHeadersFilter boundary() {
        return new ExternalIdentityHeadersFilter(List.of("tenant-id", "data-permission-enable"));
    }

    @Test void allHeaderViewsAreCaseInsensitiveAndTheConfigurationIsImmutable() throws Exception {
        var names = new ArrayList<>(List.of("tenant-id", "data-permission-enable"));
        var filter = new ExternalIdentityHeadersFilter(names); names.clear();
        var request = request(); request.addHeader("Authorization", "Bearer fixture-token");
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            var http = (HttpServletRequest) wrapped;
            for (String name : List.of("login-user", "LOGIN-USER", "tenant-ID", "data-permission-enable")) {
                assertNull(http.getHeader(name)); assertFalse(http.getHeaders(name).hasMoreElements());
                assertEquals(-1, http.getIntHeader(name)); assertEquals(-1L, http.getDateHeader(name));
            }
            assertEquals(List.of("Authorization"), Collections.list(http.getHeaderNames()));
            assertEquals("Bearer fixture-token", http.getHeader("Authorization"));
            assertTrue(ExternalIdentityHeadersFilter.requiresVerifiedToken(http));
        });
        assertFalse(ExternalIdentityHeadersFilter.requiresVerifiedToken(request));
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentityHeadersFilter(List.of("bad\r\nname")));
    }

    @Test void forgedHeadersWithoutATokenCannotCreateALoginUser() throws Exception {
        var checked = new ArrayList<String>(); var api = tokenApi(checked, value -> null);
        var token = tokenFilter(new SecurityProperties(), api);
        boundary().doFilter(request(), new MockHttpServletResponse(), (req, res) -> token.doFilter(req, res, (r, s) -> {
            assertNull(SecurityFrameworkUtils.getLoginUser());
        }));
        assertTrue(checked.isEmpty());
    }

    @Test void aCheckedTokenWinsOverForgedIdentityHeaders() throws Exception {
        var checked = new ArrayList<String>();
        var identity = new OAuth2AccessTokenCheckRespDTO().setUserId(42L).setUserType(2).setTenantId(7L);
        var api = tokenApi(checked, value -> "fixture-token".equals(value) ? identity : null);
        var token = tokenFilter(new SecurityProperties(), api); var request = request();
        request.addHeader("Authorization", "Bearer fixture-token");
        boundary().doFilter(request, new MockHttpServletResponse(), (req, res) -> token.doFilter(req, res, (r, s) -> {
            assertEquals(42L, SecurityFrameworkUtils.getLoginUserId());
            assertEquals(7L, SecurityFrameworkUtils.getLoginUser().getTenantId());
        }));
        assertEquals(List.of("fixture-token"), checked);
    }

    @Test void mockFallbackIsDisabledAtTheBoundaryButUnregisteredRoutesKeepTheirContract() throws Exception {
        var properties = new SecurityProperties(); properties.setMockEnable(true); properties.setMockSecret("fixture-mock-");
        var checked = new ArrayList<String>(); var api = tokenApi(checked, value -> null);
        var token = tokenFilter(properties, api); var external = request();
        external.addHeader("Authorization", "Bearer fixture-mock-99");
        boundary().doFilter(external, new MockHttpServletResponse(), (req, res) -> token.doFilter(req, res, (r, s) -> {
            assertNull(SecurityFrameworkUtils.getLoginUser());
        }));
        var internal = request();
        token.doFilter(internal, new MockHttpServletResponse(), (req, res) -> assertEquals(99L, SecurityFrameworkUtils.getLoginUserId()));
        assertEquals(List.of("fixture-mock-99"), checked);
    }
}
