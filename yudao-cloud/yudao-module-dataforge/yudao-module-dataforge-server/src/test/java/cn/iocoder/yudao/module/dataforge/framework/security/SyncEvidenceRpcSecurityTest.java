package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.api.migration.*;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.*;
import cn.iocoder.yudao.module.dataforge.framework.security.config.SyncEvidenceRpcConfiguration;
import cn.iocoder.yudao.module.dataforge.service.access.SyncBatchObservationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SyncEvidenceRpcSecurityTest {
    X509Certificate cert() throws Exception {
        var cert = mock(X509Certificate.class); when(cert.getEncoded()).thenReturn(new byte[]{1, 2, 3});
        return cert;
    }
    @Test void forgedInternalHeadersNeverPass() throws Exception {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.addHeader("tenant-id", "1"); request.addHeader("login-user", "{\"id\":1}");
        request.addHeader("X-Forwarded-Client-Cert", "anything");
        var response = new MockHttpServletResponse();
        new SyncEvidenceRpcFilter(Map::of).doFilter(request, response, (a, b) -> fail("unverified"));
        assertEquals(401, response.getStatus());
        assertThrows(RuntimeException.class, () -> SyncEvidenceRpcFilter.requireVerified(request));
    }
    @Test void pinnedGovernanceOnlyAndOnlyOverTls() throws Exception {
        var cert = cert();
        var request = new MockHttpServletRequest();
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{cert});
        String pin = VerifiedServicePeerResolver.fingerprint(cert);
        for (boolean secure : List.of(false, true)) for (String peer : List.of("governance", "asset")) {
            request.setSecure(secure);
            var reached = new AtomicBoolean();
            new SyncEvidenceRpcFilter(() -> Map.of(peer, Set.of(pin))).doFilter(request,
                    new MockHttpServletResponse(), (a, b) -> reached.set(true));
            assertEquals(secure && peer.equals("governance"), reached.get());
        }
    }
    @Test void controllerRejectsMissingFilterEvenWithForgedLogin() {
        var controller = new SyncBatchEvidenceApiImpl();
        ReflectionTestUtils.setField(controller, "servletRequest", new MockHttpServletRequest());
        var service = mock(SyncBatchObservationService.class);
        ReflectionTestUtils.setField(controller, "service", service);
        assertThrows(RuntimeException.class, () -> controller.observe(new SyncBatchQueryReqDTO(1L, "orders", 1L)));
        assertThrows(RuntimeException.class, () -> controller.ensureBoundary(new SyncBoundaryReqDTO("quality:" + "a".repeat(64),
                new SyncBatchQueryReqDTO(1L, "orders", 1L))));
        verifyNoInteractions(service);
    }
    @Test void exactRpcRouteAndRecordValidationWorkThroughMvc() throws Exception {
        var controller = new SyncBatchEvidenceApiImpl();
        var service = mock(SyncBatchObservationService.class);
        ReflectionTestUtils.setField(controller, "service", service);
        var boundaries = mock(cn.iocoder.yudao.module.dataforge.service.access.SyncBoundaryService.class);
        ReflectionTestUtils.setField(controller, "boundaryService", boundaries);
        var cert = cert(); String pin = VerifiedServicePeerResolver.fingerprint(cert);
        // Request 注入由测试拦截器绑定；认证仍必须由生产过滤器写入属性。
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new org.springframework.web.servlet.HandlerInterceptor() {
                    public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,
                            jakarta.servlet.http.HttpServletResponse res, Object handler) {
                        ReflectionTestUtils.setField(controller, "servletRequest", req); return true;
                    }
                }).addFilters(new SyncEvidenceRpcFilter(() -> Map.of("governance", Set.of(pin)))).build();
        var req = new SyncBatchQueryReqDTO(20L, "orders", 99L);
        when(service.observe(req)).thenReturn(new SyncBatchObservationDTO(1, 20L, "rev", "POSTGRESQL",
                "public", "orders", "orders", false, false, List.of()));
        mvc.perform(post(SyncBatchEvidenceApi.PREFIX + "/observe").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{cert})
                .contentType("application/json").content("{\"databaseId\":20,\"tableReference\":\"orders\",\"executionUserId\":99}"))
                .andExpect(status().isOk());
        verify(service).observe(req);
        mvc.perform(post(SyncBatchEvidenceApi.PREFIX + "/observe").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{cert})
                .contentType("application/json").content("{\"databaseId\":20,\"tableReference\":\"orders\"}"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
        var boundaryRequest = new SyncBoundaryReqDTO("quality:" + "a".repeat(64), req);
        when(boundaries.ensure(boundaryRequest)).thenReturn(new SyncBoundaryDTO(1, boundaryRequest.requestKey(),
                20L, "rev", "POSTGRESQL", "public", "orders", 1L));
        String body = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(boundaryRequest);
        mvc.perform(post(SyncBatchEvidenceApi.PREFIX + "/boundary").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{cert})
                .contentType("application/json").content(body)).andExpect(status().isOk());
        verify(boundaries).ensure(boundaryRequest);
        mvc.perform(post(SyncBatchEvidenceApi.PREFIX + "/boundary").secure(true)
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(SyncBatchEvidenceApi.PREFIX + "/boundary").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{cert})
                .contentType("application/json").content("{\"requestKey\":\"arbitrary\",\"target\":{}}"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(boundaries);
    }
    @Test void filterRegistrationPrecedesTenantAndDefaultConfigurationDenies() {
        new ApplicationContextRunner().withUserConfiguration(SyncEvidenceRpcConfiguration.class, SyncEvidenceRpcProperties.class)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var registration = ctx.getBean(SyncEvidenceRpcConfiguration.class)
                            .syncEvidenceRpcFilter(ctx.getBean(SyncEvidenceRpcProperties.class));
                    assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
                    assertEquals(Set.of(SyncBatchEvidenceApi.PREFIX + "/*"), Set.copyOf(registration.getUrlPatterns()));
                    assertTrue(ctx.getBean(SyncEvidenceRpcProperties.class).getPeers().isEmpty());
                });
        var properties = new SyncEvidenceRpcProperties();
        properties.setPeers(Map.of("asset", Set.of("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
