package cn.iocoder.yudao.module.governance.framework.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.rpc.LoginUserRequestInterceptor;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.rpc.TenantRequestInterceptor;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApi;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApiImpl;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermClientConfiguration;
import cn.iocoder.yudao.module.governance.framework.security.config.StandardTermRpcConfiguration;
import cn.iocoder.yudao.module.governance.service.standardterm.StandardTermService;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Response;
import feign.Retryer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StandardTermRpcSecurityTest {

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void forgedHeadersAndMissingFilterCannotReachEitherLookup() throws Exception {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.addHeader("tenant-id", "7");
        request.addHeader("login-user", "{\"id\":99,\"tenantId\":7}");
        request.addHeader("data-permission-enable", "false");
        request.addHeader("X-Forwarded-Client-Cert", "asset");
        var response = new MockHttpServletResponse();
        new StandardTermRpcFilter(Map::of).doFilter(request, response, (req, res) -> fail("unverified channel"));
        assertEquals(401, response.getStatus());
        var controller = new StandardTermApiImpl();
        var service = mock(StandardTermService.class);
        ReflectionTestUtils.setField(controller, "servletRequest", request);
        ReflectionTestUtils.setField(controller, "standardTermService", service);
        assertThrows(RuntimeException.class, () -> controller.getEnabledTermList(null));
        assertThrows(RuntimeException.class, () -> controller.getTermList(List.of(1L)));
        verifyNoInteractions(service);
    }

    @Test
    void onlyPinnedAssetCertificateOnPhysicalTlsIsAccepted() throws Exception {
        var certificate = certificate();
        String pin = VerifiedServicePeerResolver.fingerprint(certificate);
        for (boolean secure : List.of(false, true)) {
            for (String peer : List.of("asset", "dataforge")) {
                var request = new MockHttpServletRequest();
                request.setSecure(secure);
                request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
                var reached = new AtomicBoolean();
                new StandardTermRpcFilter(() -> Map.of(peer, Set.of(pin))).doFilter(request,
                        new MockHttpServletResponse(), (req, res) -> reached.set(true));
                assertEquals(secure && "asset".equals(peer), reached.get());
            }
        }
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        var response = new MockHttpServletResponse();
        new StandardTermRpcFilter(() -> Map.of("asset", Set.of("a".repeat(64))))
                .doFilter(request, response, (req, res) -> fail("mismatched certificate"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void registrationIsNarrowRunsBeforeTenantHeadersAndDefaultsClosed() {
        new ApplicationContextRunner().withUserConfiguration(StandardTermRpcConfiguration.class,
                StandardTermRpcProperties.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var registration = context.getBean(StandardTermRpcConfiguration.class)
                            .standardTermRpcFilter(context.getBean(StandardTermRpcProperties.class));
                    assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER - 1);
                    assertEquals(Set.of(StandardTermApi.PREFIX + "/enabled-list", StandardTermApi.PREFIX + "/list",
                                    cn.iocoder.yudao.module.governance.api.security.SafetyClassificationApi.PREFIX + "/list",
                                    cn.iocoder.yudao.module.governance.api.security.SafetyClassificationApi.PREFIX + "/get"),
                            Set.copyOf(registration.getUrlPatterns()));
                    assertTrue(context.getBean(StandardTermRpcProperties.class).getPeers().isEmpty());
                });
        var properties = new StandardTermRpcProperties();
        properties.setPeers(Map.of("dataforge", Set.of("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void clientRequiresSslBundleAndHttpsWithoutChangingGlobalFeignConfiguration() throws Exception {
        var annotation = StandardTermApi.class.getAnnotation(org.springframework.cloud.openfeign.FeignClient.class);
        assertEquals("standardTermApi", annotation.contextId());
        assertArrayEquals(new Class<?>[]{StandardTermClientConfiguration.class}, annotation.configuration());
        assertFalse(StandardTermClientConfiguration.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class));
        var configuration = new StandardTermClientConfiguration();
        var environment = mock(Environment.class);
        Client missing = configuration.standardTermClient(environment, provider(null), provider(null), provider(null), provider(null));
        var https = Request.create(Request.HttpMethod.GET, "https://localhost:1/test", Map.of(), new byte[0], StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> missing.execute(https, configuration.standardTermOptions()));
        when(environment.getProperty(StandardTermClientConfiguration.SSL_BUNDLE_PROPERTY)).thenReturn("fixture");
        var bundles = mock(SslBundles.class);
        var bundle = mock(SslBundle.class);
        when(bundles.getBundle("fixture")).thenReturn(bundle);
        when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());
        Client configured = configuration.standardTermClient(environment, provider(bundles), provider(null), provider(null), provider(null));
        var http = Request.create(Request.HttpMethod.GET, "http://localhost:1/test", Map.of(), new byte[0], StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> configured.execute(http, configuration.standardTermOptions()));
        assertSame(Retryer.NEVER_RETRY, configuration.standardTermRetryer());
        assertFalse(configuration.standardTermOptions().isFollowRedirects());
    }

    @Test
    void realFeignContractPropagatesTenantAndLosslessIdsThroughTheAuthenticatedMvcGate() throws Exception {
        var certificate = certificate();
        String pin = VerifiedServicePeerResolver.fingerprint(certificate);
        var captured = new AtomicReference<Request>();
        TenantContextHolder.setTenantId(7L);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(7L).setUserType(2), new MockHttpServletRequest());
        var client = Feign.builder().contract(new SpringMvcContract())
                .decoder((response, type) -> CommonResult.success(List.of()))
                .requestInterceptor(new LoginUserRequestInterceptor()).requestInterceptor(new TenantRequestInterceptor())
                .client((outbound, options) -> {
                    captured.set(outbound);
                    return Response.builder().request(outbound).status(200).reason("OK").headers(Map.of())
                            .body("{}", StandardCharsets.UTF_8).build();
                }).target(StandardTermApi.class, "https://governance.example.test");
        client.getTermList(List.of(9007199254740993L));
        assertEquals(StandardTermApi.PREFIX + "/list", URI.create(captured.get().url()).getPath());
        assertTrue(captured.get().url().contains("9007199254740993"));
        assertEquals(List.of("7"), List.copyOf(captured.get().headers().get("tenant-id")));
        assertTrue(captured.get().headers().containsKey("login-user"));
        TenantContextHolder.clear();

        var controller = new StandardTermApiImpl();
        var service = mock(StandardTermService.class);
        ReflectionTestUtils.setField(controller, "standardTermService", service);
        when(service.getStandardTermList(List.of(9007199254740993L))).thenAnswer(invocation -> {
            assertEquals(7L, TenantContextHolder.getRequiredTenantId());
            return List.of();
        });
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                ReflectionTestUtils.setField(controller, "servletRequest", request);
                return true;
            }
        }).addFilters(new StandardTermRpcFilter(() -> Map.of("asset", Set.of(pin))), new TenantContextWebFilter()).build();
        mvc.perform(get(StandardTermApi.PREFIX + "/list").secure(true).param("ids", "9007199254740993")
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate})
                .header("tenant-id", "7").header("login-user", captured.get().headers().get("login-user").iterator().next()))
                .andExpect(status().isOk());
        verify(service).getStandardTermList(List.of(9007199254740993L));
    }

    private X509Certificate certificate() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{3, 6, 9});
        return certificate;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        var result = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(value);
        when(result.orderedStream()).thenReturn(Stream.empty());
        return result;
    }

}
