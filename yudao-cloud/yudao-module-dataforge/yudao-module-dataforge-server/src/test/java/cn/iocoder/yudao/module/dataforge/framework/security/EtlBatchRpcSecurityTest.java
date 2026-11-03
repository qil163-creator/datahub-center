package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.api.etl.*;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import cn.iocoder.yudao.module.dataforge.framework.security.config.EtlBatchRpcConfiguration;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlTargetBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EtlBatchRpcSecurityTest {
    private X509Certificate certificate() throws Exception {
        var certificate = mock(X509Certificate.class); when(certificate.getEncoded()).thenReturn(new byte[]{3, 2, 1}); return certificate;
    }
    @Test void forgedIdentityHeadersCannotReachController() throws Exception {
        var request = new MockHttpServletRequest(); request.setSecure(true);
        request.addHeader("tenant-id", "7"); request.addHeader("login-user", "{\"id\":99}"); request.addHeader("X-Forwarded-Client-Cert", "supply");
        var response = new MockHttpServletResponse();
        new EtlBatchRpcFilter(Map::of).doFilter(request, response, (req, res) -> fail("unverified request"));
        assertEquals(401, response.getStatus()); assertThrows(RuntimeException.class, () -> EtlBatchRpcFilter.requireVerified(request));
        var controller = new EtlTargetBatchApiImpl(); var service = mock(EtlTargetBatchService.class);
        ReflectionTestUtils.setField(controller, "servletRequest", request); ReflectionTestUtils.setField(controller, "service", service);
        assertThrows(RuntimeException.class, () -> controller.describe(new EtlTargetBatchDescribeReqDTO()));
        assertThrows(RuntimeException.class, () -> controller.read(new EtlTargetBatchReadReqDTO())); verifyNoInteractions(service);
    }
    @Test void onlyPinnedAssetOrSupplyOverActualTlsCanProceed() throws Exception {
        var certificate = certificate(); String pin = VerifiedServicePeerResolver.fingerprint(certificate);
        for (boolean secure : List.of(false, true)) for (String peer : List.of("asset", "supply", "governance")) {
            var request = new MockHttpServletRequest(); request.setSecure(secure);
            request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
            var reached = new AtomicBoolean();
            new EtlBatchRpcFilter(() -> Map.of(peer, Set.of(pin))).doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
            assertEquals(secure && !"governance".equals(peer), reached.get());
        }
    }
    @Test void mvcRequiresActorAndCompleteReadBinding() throws Exception {
        var controller = new EtlTargetBatchApiImpl(); var service = mock(EtlTargetBatchService.class);
        ReflectionTestUtils.setField(controller, "service", service);
        var certificate = certificate(); String pin = VerifiedServicePeerResolver.fingerprint(certificate);
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new org.springframework.web.servlet.HandlerInterceptor() {
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res, Object handler) {
                ReflectionTestUtils.setField(controller, "servletRequest", req); return true;
            }
        }).addFilters(new EtlBatchRpcFilter(() -> Map.of("supply", Set.of(pin)))).build();
        mvc.perform(post(EtlTargetBatchApi.PREFIX + "/describe").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate})
                .contentType("application/json").content("{\"batchId\":77,\"executionUserId\":99}"))
                .andExpect(status().isOk()); verify(service).describe(77L, 99L);
        mvc.perform(post(EtlTargetBatchApi.PREFIX + "/describe").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate})
                .contentType("application/json").content("{\"batchId\":77}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(EtlTargetBatchApi.PREFIX + "/read").secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate})
                .contentType("application/json").content("{\"batchId\":77,\"executionUserId\":99,\"afterRow\":0,\"limit\":201}"))
                .andExpect(status().isBadRequest()); verifyNoMoreInteractions(service);
    }
    @Test void registrationRunsBeforeTenantHeadersAndDefaultsClosed() {
        new ApplicationContextRunner().withUserConfiguration(EtlBatchRpcConfiguration.class, EtlBatchRpcProperties.class).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var registration = ctx.getBean(EtlBatchRpcConfiguration.class).etlBatchRpcFilter(ctx.getBean(EtlBatchRpcProperties.class));
            assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
            assertEquals(Set.of(EtlTargetBatchApi.PREFIX + "/*"), Set.copyOf(registration.getUrlPatterns()));
            assertTrue(ctx.getBean(EtlBatchRpcProperties.class).getPeers().isEmpty());
        });
        var properties = new EtlBatchRpcProperties(); properties.setPeers(Map.of("other", Set.of("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
