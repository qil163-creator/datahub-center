package cn.iocoder.yudao.module.modeling.framework.security;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
import cn.iocoder.yudao.module.modeling.framework.security.config.ModelRuleContextRpcConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ModelRuleContextSecurityTest {
    @org.junit.jupiter.api.BeforeEach void webProperties() { new WebFrameworkUtils(new cn.iocoder.yudao.framework.web.config.WebProperties()); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    public static X509Certificate certificate() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn("candidate-test-service".getBytes(StandardCharsets.UTF_8));
        return certificate;
    }
    public static MockHttpServletRequest request(X509Certificate certificate, LoginUser actor) {
        var request = new MockHttpServletRequest("GET", ModelRuleContextApi.PATH);
        request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        if (actor != null) request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER,
                URLEncoder.encode(JsonUtils.toJsonString(actor), StandardCharsets.UTF_8));
        return request;
    }
    public static ModelRuleContextRpcFilter filter(X509Certificate certificate) {
        return new ModelRuleContextRpcFilter(() -> Map.of("governance", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))));
    }
    public static HttpServletRequest verifiedRequest() throws Exception {
        var certificate = certificate();
        var result = new AtomicReference<HttpServletRequest>();
        filter(certificate).doFilter(request(certificate, new LoginUser().setId(42L).setUserType(2)),
                new MockHttpServletResponse(), (req, res) -> result.set((HttpServletRequest) req));
        assertNotNull(result.get()); return result.get();
    }

    @Test void rejectsDirectAndForgedForwardedPeerWithoutReadingUser() throws Exception {
        var certificate = certificate();
        for (boolean secure : List.of(false, true)) {
            var request = new MockHttpServletRequest("GET", ModelRuleContextApi.PATH);
            request.setSecure(secure);
            request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, "forged");
            request.addHeader("X-Forwarded-Client-Cert", VerifiedServicePeerResolver.fingerprint(certificate));
            request.addHeader("X-Forwarded-Proto", "https");
            var response = new MockHttpServletResponse();
            filter(certificate).doFilter(request, response, (req, res) -> fail("不可信请求不得进入业务"));
            assertEquals(401, response.getStatus());
        }
        assertThrows(RuntimeException.class, () -> ModelRuleContextRpcFilter.requireVerified(new MockHttpServletRequest()));
    }

    @Test void rejectsWrongPeerMissingActorAndDuplicateActor() throws Exception {
        var certificate = certificate();
        var actor = new LoginUser().setId(42L).setUserType(2);
        var wrongPeer = new ModelRuleContextRpcFilter(() -> Map.of("asset", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))));
        var response = new MockHttpServletResponse();
        wrongPeer.doFilter(request(certificate, actor), response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
        for (LoginUser invalid : Arrays.asList(null, new LoginUser().setId(42L).setUserType(1), new LoginUser().setId(-1L).setUserType(2))) {
            response = new MockHttpServletResponse();
            filter(certificate).doFilter(request(certificate, invalid), response, (req, res) -> fail());
            assertEquals(401, response.getStatus());
        }
        var duplicate = request(certificate, actor);
        duplicate.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, duplicate.getHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER));
        response = new MockHttpServletResponse();
        filter(certificate).doFilter(duplicate, response, (req, res) -> fail());
        assertEquals(401, response.getStatus());
    }

    @Test void trustedChannelRebuildsActorAndStillChecksRealPermission() throws Exception {
        var certificate = certificate();
        var request = request(certificate, new LoginUser().setId(9007199254740993L).setUserType(2)
                .setTenantId(1L).setVisitTenantId(2L).setScopes(List.of("*")));
        var controls = List.of(WebFrameworkUtils.HEADER_TENANT_ID, WebFrameworkUtils.HEADER_VISIT_TENANT_ID,
                DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, EnvUtils.HEADER_TAG);
        controls.forEach(name -> request.addHeader(name, "forged"));
        var permissionApi = mock(PermissionCommonApi.class);
        when(permissionApi.hasAnyPermissions(9007199254740993L, "modeling:model:query")).thenReturn(CommonResult.success(false));
        var security = new SecurityFrameworkServiceImpl(permissionApi);
        var tokens = mock(OAuth2TokenCommonApi.class);
        var tokenFilter = new TokenAuthenticationFilter(new SecurityProperties(), mock(GlobalExceptionHandler.class), tokens);
        filter(certificate).doFilter(request, new MockHttpServletResponse(), (clean, response) -> {
            var http = (HttpServletRequest) clean;
            ModelRuleContextRpcFilter.requireVerified(http);
            for (String name : controls) {
                assertNull(http.getHeader(name.toUpperCase(Locale.ROOT)));
                assertFalse(http.getHeaders(name).hasMoreElements());
                assertFalse(Collections.list(http.getHeaderNames()).contains(name));
            }
            tokenFilter.doFilter(clean, response, (authenticated, out) -> {
                assertEquals(9007199254740993L, SecurityFrameworkUtils.getLoginUserId());
                assertNull(SecurityFrameworkUtils.getLoginUser().getTenantId());
                assertNull(SecurityFrameworkUtils.getLoginUser().getVisitTenantId());
                assertFalse(SecurityFrameworkUtils.skipPermissionCheck());
                assertFalse(security.hasPermission("modeling:model:query"));
                assertTrue(new SecurityFrameworkServiceImpl(allowedPermissions()).hasPermission("modeling:model:query"));
            });
        });
        verify(permissionApi).hasAnyPermissions(9007199254740993L, "modeling:model:query");
        verifyNoInteractions(tokens);
    }
    private static PermissionCommonApi allowedPermissions() {
        var permission = mock(PermissionCommonApi.class);
        when(permission.hasAnyPermissions(9007199254740993L, "modeling:model:query")).thenReturn(CommonResult.success(true));
        return permission;
    }
    @Test void registrationAndPinsAreNarrowAndFailClosed() {
        var properties = new ModelRuleContextRpcProperties(); properties.validate();
        assertTrue(properties.getPeers().isEmpty());
        var registration = new ModelRuleContextRpcConfiguration().modelRuleContextRpcFilter(properties);
        assertEquals(List.of(ModelRuleContextApi.PATH), List.copyOf(registration.getUrlPatterns()));
        assertTrue(registration.getOrder() < cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum.ENV_TAG_FILTER);
        properties.setPeers(Map.of("asset", Set.of("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
