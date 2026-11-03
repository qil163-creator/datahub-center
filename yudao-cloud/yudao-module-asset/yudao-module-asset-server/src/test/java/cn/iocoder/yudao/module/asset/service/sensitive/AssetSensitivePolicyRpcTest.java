package cn.iocoder.yudao.module.asset.service.sensitive;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.asset.framework.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetSensitivePolicyRpcTest {

    @Test
    void forwardedIdentityAndForgedCertificateHeadersCannotReadWorkbenchSensitiveLists() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader("login-user", "{\"id\":1}");
        request.addHeader("X-Forwarded-Client-Cert", "forged");
        var called = new AtomicBoolean();
        new AssetSensitivePolicyRpcFilter(Map::of).doFilter(request, response, (a, b) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(called).isFalse();
        assertThatThrownBy(() -> AssetSensitivePolicyRpcFilter.requireVerified(request)).isInstanceOf(
                RuntimeException.class);
    }

    @Test
    void onlyPinnedGovernancePeerCanPassAndUnregisteredPeersAreRejected() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{7, 2, 3});
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        var called = new AtomicBoolean();
        new AssetSensitivePolicyRpcFilter(
                () -> Map.of("governance", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                .doFilter(request, new MockHttpServletResponse(), (a, b) -> {
                    AssetSensitivePolicyRpcFilter.requireVerified(request);
                    called.set(true);
                });
        assertThat(called).isTrue();
        var properties = new AssetSensitivePolicyRpcProperties();
        properties.setPeers(Map.of("asset", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
