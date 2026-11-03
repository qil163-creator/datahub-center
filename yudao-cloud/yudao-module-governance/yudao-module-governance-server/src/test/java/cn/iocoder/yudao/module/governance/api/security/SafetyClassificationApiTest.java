package cn.iocoder.yudao.module.governance.api.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.framework.security.config.StandardTermRpcConfiguration;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcProperties;
import cn.iocoder.yudao.module.governance.service.category.SafetyClassificationReferenceService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafetyClassificationApiTest extends BaseMockitoUnitTest {
    @Mock private SafetyClassificationReferenceService classificationReferenceService;
    @InjectMocks private SafetyClassificationApiImpl api;

    @Test
    void directCallAndForgedHeadersCannotBypassVerifiedAssetChannel() throws Exception {
        assertThatThrownBy(api::getClassifications).isInstanceOf(RuntimeException.class);
        var request = new MockHttpServletRequest(); request.addHeader("login-user", "{\"id\":1}");
        request.addHeader("x-service-name", "asset");
        var response = new MockHttpServletResponse();
        new StandardTermRpcFilter(Map::of).doFilter(request, response, (req, res) -> fail("禁止伪造头进入 RPC"));
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(classificationReferenceService);
    }

    @Test
    void actualCertificateAndRegisteredNarrowPathsAllowProjection() throws Exception {
        var certificate = mock(X509Certificate.class); when(certificate.getEncoded()).thenReturn(new byte[]{4, 8, 15});
        var request = new MockHttpServletRequest(); request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        new StandardTermRpcFilter(() -> Map.of("asset", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        ReflectionTestUtils.setField(api, "servletRequest", request);
        when(classificationReferenceService.list()).thenReturn(List.of());
        assertThat(api.getClassifications().getCheckedData()).isEmpty();
        assertThat(api.getClassification(123L).getCheckedData()).isNull();
        verify(classificationReferenceService).get(123L);
        assertThat(new StandardTermRpcConfiguration().standardTermRpcFilter(new StandardTermRpcProperties()).getUrlPatterns())
                .contains(SafetyClassificationApi.PREFIX + "/list", SafetyClassificationApi.PREFIX + "/get");
    }
}
