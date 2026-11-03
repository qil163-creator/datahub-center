package cn.iocoder.yudao.module.governance.framework.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.rpc.LoginUserRequestInterceptor;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.standard.StandardAssociationApi;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermClientConfiguration;
import cn.iocoder.yudao.module.governance.api.standard.StandardAssociationApiImpl;
import cn.iocoder.yudao.module.governance.framework.security.config.StandardAssociationRpcConfiguration;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardAssociationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import feign.Feign;
import feign.Request;
import feign.Response;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandardAssociationRpcSecurityTest {
    @Test void dedicatedFeignContextUsesExistingPinnedTlsTransportAndFailsClosedWithoutBundle() throws Exception {
        var annotation = StandardAssociationApi.class.getAnnotation(FeignClient.class);
        assertEquals("standardAssociationApi", annotation.contextId());
        assertArrayEquals(new Class<?>[]{StandardTermClientConfiguration.class}, annotation.configuration());
        assertFalse(StandardTermClientConfiguration.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class));
        var config = new StandardTermClientConfiguration();
        var transport = config.standardTermClient(mock(Environment.class), provider(), provider(), provider(), provider());
        var request = Request.create(Request.HttpMethod.GET, "https://governance.example.test" + StandardAssociationApi.PREFIX + "/columns", Map.of(), new byte[0], StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> transport.execute(request, config.standardTermOptions()));
        assertSame(feign.Retryer.NEVER_RETRY, config.standardTermRetryer());
        assertFalse(config.standardTermOptions().isFollowRedirects());
    }

    @Test void realFeignContractPropagatesLosslessActorAndExpiryThroughSharedSecurityInterceptor() {
        var captured = new AtomicReference<Request>();
        var expiry = LocalDateTime.of(2026, 10, 16, 18, 0);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(9007199254740993L).setUserType(2).setExpiresTime(expiry), new MockHttpServletRequest());
        try {
            var client = Feign.builder().contract(new SpringMvcContract())
                    .decoder((response, type) -> CommonResult.success(List.of()))
                    .requestInterceptor(new LoginUserRequestInterceptor())
                    .client((request, options) -> {
                        captured.set(request);
                        return Response.builder().request(request).status(200).reason("OK").headers(Map.of())
                                .body("{}", StandardCharsets.UTF_8).build();
                    }).target(StandardAssociationApi.class, "https://governance.example.test");
            client.getColumns("codeset", 229);
            assertTrue(captured.get().url().contains(StandardAssociationApi.PREFIX + "/columns?"));
            assertTrue(captured.get().url().contains("kind=codeset"));
            assertTrue(captured.get().url().contains("id=229"));
            var header = captured.get().headers().get(SecurityFrameworkUtils.LOGIN_USER_HEADER).iterator().next();
            var actor = JsonUtils.parseObject(URLDecoder.decode(header, StandardCharsets.UTF_8), LoginUser.class);
            assertEquals(9007199254740993L, actor.getId());
            assertEquals(expiry, actor.getExpiresTime());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        var result = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(result.orderedStream()).thenReturn(Stream.empty());
        return result;
    }

    @Test void bareRpcAndForgedIdentityHeadersNeverReachDomainService() throws Exception {
        var service = mock(StandardAssociationService.class);
        var request = new MockHttpServletRequest();
        request.addHeader("login-user", "{\"id\":1}");
        request.addHeader("X-Forwarded-Client-Cert", "asset");
        var api = new StandardAssociationApiImpl(service, request);
        assertThrows(ServiceException.class, () -> api.getColumns("element", 10));
        var response = new MockHttpServletResponse();
        new StandardTermRpcFilter(Map::of).doFilter(request, response, (req, res) -> fail("未认证通道不得继续"));
        assertEquals(401, response.getStatus()); verifyNoInteractions(service);
    }

    @Test void verifiedAssetChannelPreservesDomainPermissionAndObjectValidation() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{1, 3, 5});
        var request = new MockHttpServletRequest(); request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        var service = mock(StandardAssociationService.class);
        when(service.getColumns("codeset", 10)).thenReturn(List.of());
        var api = new StandardAssociationApiImpl(service, request);
        new StandardTermRpcFilter(() -> Map.of("asset", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> assertTrue(api.getColumns("codeset", 10).getCheckedData().isEmpty()));
        verify(service).getColumns("codeset", 10);
    }

    @Test void configurationProtectsWholeNewRpcPrefixAndRejectsOtherPeers() throws Exception {
        var registration = new StandardAssociationRpcConfiguration().standardAssociationRpcFilter(new StandardTermRpcProperties());
        assertTrue(registration.getUrlPatterns().contains(StandardAssociationApi.PREFIX + "/*"));
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{2, 4, 6});
        var request = new MockHttpServletRequest(); request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        var response = new MockHttpServletResponse();
        new StandardTermRpcFilter(() -> Map.of("modeling", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                .doFilter(request, response, (req, res) -> fail("非资产对端不得读取"));
        assertEquals(401, response.getStatus());
    }
}
