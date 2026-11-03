package cn.iocoder.yudao.module.asset.framework.security;

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
import cn.iocoder.yudao.module.asset.framework.security.config.SensitiveListSecurityConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SensitiveListSecurityTest {

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sensitiveListEntryStripsInternalIdentityAndScopeBeforeContextFilters() throws Exception {
        var registration = new SensitiveListSecurityConfiguration().sensitiveListIdentityFilter(new WebProperties());
        assertEquals(List.of("/admin-api/asset/sensitive-list/*"), List.copyOf(registration.getUrlPatterns()));
        assertTrue(registration.getOrder() < WebFilterOrderEnum.ENV_TAG_FILTER);
        var request = request();
        request.addHeader("Authorization", "Bearer current-session");
        registration.getFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var http = (HttpServletRequest) req;
            for (String header : List.of(SecurityFrameworkUtils.LOGIN_USER_HEADER,
                    WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                    DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG)) {
                assertNull(http.getHeader(header));
                assertFalse(http.getHeaders(header).hasMoreElements());
            }
            assertEquals("Bearer current-session", http.getHeader("Authorization"));
        });
    }

    @Test
    void checkedTokenDeterminesUserInsteadOfForgedLoginUser() throws Exception {
        var api = mock(OAuth2TokenCommonApi.class);
        when(api.checkAccessToken("current-session")).thenReturn(CommonResult.success(
                new OAuth2AccessTokenCheckRespDTO().setUserId(42L).setUserType(2)));
        var token = new TokenAuthenticationFilter(new SecurityProperties(),
                new GlobalExceptionHandler("fixture", null), api);
        var request = request();
        request.addHeader("Authorization", "Bearer current-session");
        new SensitiveListSecurityConfiguration().sensitiveListIdentityFilter(new WebProperties()).getFilter()
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> token.doFilter(req, res,
                        (r, s) -> assertEquals(42L, SecurityFrameworkUtils.getLoginUserId())));
        verify(api).checkAccessToken("current-session");
    }

    @Test
    void missingOrMockTokenCannotUseForgedInternalIdentity() throws Exception {
        var api = mock(OAuth2TokenCommonApi.class);
        when(api.checkAccessToken(anyString())).thenReturn(CommonResult.success(null));
        var properties = new SecurityProperties();
        properties.setMockEnable(true);
        properties.setMockSecret("fixture-mock-");
        for (boolean mockToken : List.of(false, true)) {
            var token = new TokenAuthenticationFilter(properties, new GlobalExceptionHandler("fixture", null), api);
            var request = request();
            if (mockToken) request.addHeader("Authorization", "Bearer fixture-mock-99");
            new SensitiveListSecurityConfiguration().sensitiveListIdentityFilter(new WebProperties()).getFilter()
                    .doFilter(request, new MockHttpServletResponse(), (req, res) -> token.doFilter(req, res,
                            (r, s) -> assertNull(SecurityFrameworkUtils.getLoginUser())));
        }
        verify(api).checkAccessToken("fixture-mock-99");
        verifyNoMoreInteractions(api);
    }

    private MockHttpServletRequest request() {
        new WebFrameworkUtils(new WebProperties());
        var request = new MockHttpServletRequest();
        request.setServletPath("/admin-api/asset/sensitive-list/categories");
        request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, "{\"id\":99,\"userType\":2}");
        request.addHeader(WebFrameworkUtils.HEADER_TENANT_ID, "123");
        request.addHeader(WebFrameworkUtils.HEADER_VISIT_TENANT_ID, "456");
        request.addHeader(DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, "false");
        request.addHeader(EnvUtils.HEADER_TAG, "forged-environment");
        return request;
    }
}
