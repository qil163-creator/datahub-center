package cn.iocoder.yudao.module.governance.framework.security;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.governance.framework.security.config.StandardElementRuleSecurityConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandardElementRuleSecurityTest {
    @org.junit.jupiter.api.BeforeEach void webProperties() { new WebFrameworkUtils(new cn.iocoder.yudao.framework.web.config.WebProperties()); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void stripsForgedIdentityAndDisablesMockLoginAtAllNewEntries() throws Exception {
        var registration = new StandardElementRuleSecurityConfiguration().standardElementRuleIdentityFilter(new WebProperties());
        assertEquals(8, registration.getUrlPatterns().size());
        assertTrue(registration.getUrlPatterns().stream().allMatch(path -> path.startsWith("/admin-api/governance/standard-element/rule-")));
        var properties = new SecurityProperties(); properties.setMockEnable(true); properties.setMockSecret("mock-");
        var tokens = mock(OAuth2TokenCommonApi.class);
        when(tokens.checkAccessToken("mock-42")).thenReturn(CommonResult.success(null));
        var tokenFilter = new TokenAuthenticationFilter(properties, mock(GlobalExceptionHandler.class), tokens);
        var request = new MockHttpServletRequest();
        var controls = List.of(SecurityFrameworkUtils.LOGIN_USER_HEADER, WebFrameworkUtils.HEADER_TENANT_ID,
                WebFrameworkUtils.HEADER_VISIT_TENANT_ID, DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG);
        controls.forEach(name -> request.addHeader(name, "forged"));
        request.addHeader("Authorization", "Bearer mock-42");
        registration.getFilter().doFilter(request, new MockHttpServletResponse(), (clean, response) -> {
            var http = (HttpServletRequest) clean;
            controls.forEach(name -> assertNull(http.getHeader(name)));
            tokenFilter.doFilter(clean, response, (req, res) -> assertNull(SecurityFrameworkUtils.getLoginUser()));
        });
        verify(tokens).checkAccessToken("mock-42");
    }
    @Test void realTokenPrincipalSurvivesExternalBoundary() throws Exception {
        var token = new OAuth2AccessTokenCheckRespDTO(); token.setUserId(73L); token.setUserType(2);
        var tokens = mock(OAuth2TokenCommonApi.class);
        when(tokens.checkAccessToken("verified-token")).thenReturn(CommonResult.success(token));
        var filter = new TokenAuthenticationFilter(new SecurityProperties(), mock(GlobalExceptionHandler.class), tokens);
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer verified-token");
        new StandardElementRuleSecurityConfiguration().standardElementRuleIdentityFilter(new WebProperties()).getFilter()
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> filter.doFilter(req, res,
                        (authenticated, response) -> assertEquals(73L, SecurityFrameworkUtils.getLoginUserId())));
    }
}
