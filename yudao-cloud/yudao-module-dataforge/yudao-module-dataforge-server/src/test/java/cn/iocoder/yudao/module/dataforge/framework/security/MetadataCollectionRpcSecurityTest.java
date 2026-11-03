package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApiImpl;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.systemsource.SystemSourceMapper;
import cn.iocoder.yudao.module.dataforge.framework.security.config.MetadataCollectionRpcConfiguration;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetadataCollectionRpcSecurityTest {
    private X509Certificate certificate() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{9, 2, 4});
        return certificate;
    }

    @Test
    void anonymousDirectRequestsCannotUseForgedHeadersOrActor() throws Exception {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.addHeader("login-user", "{\"id\":1}");
        request.addHeader("X-Forwarded-Client-Cert", "governance");
        request.addHeader("tenant-id", "1");
        request.addParameter("actorId", "1");
        var response = new MockHttpServletResponse();
        new MetadataCollectionRpcFilter(Map::of).doFilter(request, response, (req, res) -> fail("未认证请求不应进入业务"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void certificateOnlyWorksForPinnedGovernanceOverTls() throws Exception {
        var certificate = certificate();
        String pin = VerifiedServicePeerResolver.fingerprint(certificate);
        for (boolean secure : List.of(false, true)) {
            for (String peer : List.of("governance", "asset")) {
                var request = new MockHttpServletRequest();
                request.setSecure(secure);
                request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
                var reached = new AtomicBoolean();
                new MetadataCollectionRpcFilter(() -> Map.of(peer, Set.of(pin))).doFilter(request,
                        new MockHttpServletResponse(), (req, res) -> reached.set(true));
                assertEquals(secure && peer.equals("governance"), reached.get());
            }
        }
    }

    @Test
    void providerAlsoRejectsMissingFilterBeforePermissionOrSourceLookup() {
        var provider = new MetadataCollectionApiImpl();
        var permissions = mock(PermissionApi.class);
        ReflectionTestUtils.setField(provider, "permissionApi", permissions);
        var request = new MockHttpServletRequest();
        request.addHeader("login-user", "{\"id\":9}");
        ReflectionTestUtils.setField(provider, "servletRequest", request);
        assertEquals(401, assertThrows(ServiceException.class, () -> provider.options(9L)).getCode());
        assertEquals(401, assertThrows(ServiceException.class, () -> provider.fileManifest(7L, "/", 9L)).getCode());
        assertEquals(401, assertThrows(ServiceException.class, () -> provider.system(3L, 9L)).getCode());
        verifyNoInteractions(permissions);
    }

    @Test
    void exactMvcRouteRequiresPeerAndActorBeforeReturningOptions() throws Exception {
        var provider = new MetadataCollectionApiImpl();
        var permissions = mock(PermissionApi.class);
        var databases = mock(DatabaseMapper.class);
        var systems = mock(SystemSourceMapper.class);
        ReflectionTestUtils.setField(provider, "permissionApi", permissions);
        ReflectionTestUtils.setField(provider, "databaseMapper", databases);
        ReflectionTestUtils.setField(provider, "systemMapper", systems);
        when(permissions.hasAnyPermissions(9L, "governance:meta-collect-task:query", "governance:meta-file:query"))
                .thenReturn(CommonResult.success(true));
        var certificate = certificate();
        var filter = new MetadataCollectionRpcFilter(() -> Map.of("governance", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))));
        var mvc = MockMvcBuilders.standaloneSetup(provider).addInterceptors(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                ReflectionTestUtils.setField(provider, "servletRequest", request);
                return true;
            }
        }).addFilters(filter).build();
        mvc.perform(get(MetadataCollectionApi.PREFIX + "/options").param("actorId", "9")
                .secure(true).requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate}))
                .andExpect(status().isOk());
        verify(permissions).hasAnyPermissions(9L, "governance:meta-collect-task:query", "governance:meta-file:query");
        mvc.perform(get(MetadataCollectionApi.PREFIX + "/options").param("actorId", "9")
                .header("login-user", "{\"id\":9}")).andExpect(status().isUnauthorized());
        mvc.perform(get(MetadataCollectionApi.PREFIX + "/options").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate}))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(permissions);
    }

    @Test
    void narrowRegistrationRunsBeforeIdentityFiltersAndDefaultsToDeny() {
        new ApplicationContextRunner().withUserConfiguration(MetadataCollectionRpcConfiguration.class,
                MetadataCollectionRpcProperties.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(MetadataCollectionRpcProperties.class);
                    var registration = context.getBean(MetadataCollectionRpcConfiguration.class).metadataCollectionRpcFilter(properties);
                    assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
                    assertEquals(Set.of(MetadataCollectionApi.PREFIX + "/*"), Set.copyOf(registration.getUrlPatterns()));
                    assertTrue(properties.getPeers().isEmpty());
                });
        var properties = new MetadataCollectionRpcProperties();
        properties.setPeers(Map.of("asset", Set.of("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setPeers(Map.of("governance", Set.of("invalid")));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
