package cn.iocoder.yudao.framework.security.core.rpc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务身份只能来自容器 TLS 握手提供的客户端证书；任何头、参数或不完整配置都不能证明身份。
 */
class VerifiedServicePeerResolverTest {

    @TempDir
    static Path directory;

    private static X509Certificate asset;
    private static X509Certificate assetRotated;
    private static X509Certificate stranger;
    private static X509Certificate expired;
    private static X509Certificate notYetValid;
    private static String assetPin;
    private static String assetRotatedPin;

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    @BeforeAll
    static void generateCertificates() throws Exception {
        asset = TemporaryCertificates.generate(directory, "asset", "asset-server", null, 30);
        assetRotated = TemporaryCertificates.generate(directory, "asset-next", "asset-server", null, 30);
        stranger = TemporaryCertificates.generate(directory, "stranger", "unknown-service", null, 30);
        expired = TemporaryCertificates.generate(directory, "expired", "asset-server", "-10d", 1);
        notYetValid = TemporaryCertificates.generate(directory, "future", "asset-server", "+10d", 30);
        assetPin = TemporaryCertificates.pin(asset);
        assetRotatedPin = TemporaryCertificates.pin(assetRotated);
    }

    private static MockHttpServletRequest tlsRequest(X509Certificate... chain) {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setScheme("https");
        if (chain.length > 0) {
            request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, chain);
        }
        return request;
    }

    @Test
    void forgedHeadersDoNotProveServiceIdentity() {
        var request = new MockHttpServletRequest();
        request.setSecure(false);
        request.addHeader("login-user", "{\"id\":1,\"tenantId\":1}");
        request.addHeader("tenant-id", "1");
        request.addHeader("X-Forwarded-Client-Cert", "Hash=" + assetPin);
        request.addHeader("ssl-client-verify", "SUCCESS");
        assertThat(resolver.resolve(request, Map.of("asset", Set.of(assetPin)))).isEmpty();
    }

    @Test
    void secureChannelWithoutClientCertificateIsAnonymous() {
        var request = tlsRequest();
        request.addHeader("login-user", "{\"id\":1,\"tenantId\":1}");
        assertThat(resolver.resolve(request, Map.of("asset", Set.of(assetPin)))).isEmpty();
    }

    @Test
    void certificateAttributeWithoutSecureChannelIsRejected() {
        var request = new MockHttpServletRequest();
        request.setSecure(false);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{asset});
        assertThat(resolver.resolve(request, Map.of("asset", Set.of(assetPin)))).isEmpty();
    }

    @Test
    void pinnedLeafCertificateResolvesToItsService() {
        assertThat(resolver.resolve(tlsRequest(asset), Map.of("asset", Set.of(assetPin)))).contains("asset");
    }

    @Test
    void pinComparisonIgnoresHexCaseButNotContent() throws Exception {
        assertThat(resolver.resolve(tlsRequest(asset), Map.of("asset", Set.of(assetPin.toUpperCase()))))
                .contains("asset");
        String flipped = (assetPin.charAt(0) == '0' ? "1" : "0") + assetPin.substring(1);
        assertThat(resolver.resolve(tlsRequest(asset), Map.of("asset", Set.of(flipped)))).isEmpty();
    }

    @Test
    void unknownCertificateIsRejectedEvenWithMatchingSubject() {
        assertThat(resolver.resolve(tlsRequest(stranger), Map.of("asset", Set.of(assetPin)))).isEmpty();
        assertThat(resolver.resolve(tlsRequest(assetRotated), Map.of("asset", Set.of(assetPin)))).isEmpty();
    }

    @Test
    void expiredOrNotYetValidCertificateIsRejectedEvenWhenPinned() throws Exception {
        assertThat(resolver.resolve(tlsRequest(expired),
                Map.of("asset", Set.of(TemporaryCertificates.pin(expired))))).isEmpty();
        assertThat(resolver.resolve(tlsRequest(notYetValid),
                Map.of("asset", Set.of(TemporaryCertificates.pin(notYetValid))))).isEmpty();
    }

    @Test
    void rotationAcceptsBothPinsOfTheSameService() {
        Map<String, Set<String>> peers = Map.of("asset", Set.of(assetPin, assetRotatedPin));
        assertThat(resolver.resolve(tlsRequest(asset), peers)).contains("asset");
        assertThat(resolver.resolve(tlsRequest(assetRotated), peers)).contains("asset");
        assertThat(resolver.resolve(tlsRequest(stranger), peers)).isEmpty();
    }

    @Test
    void onlyTheLeafCertificateIsPinned() throws Exception {
        // 链中的第二张证书即使被 pin 也不代表叶证书身份。
        assertThat(resolver.resolve(tlsRequest(stranger, asset), Map.of("asset", Set.of(assetPin)))).isEmpty();
    }

    @Test
    void ambiguousPinConfigurationIsRejectedInsteadOfPickingAnOwner() {
        Map<String, Set<String>> ambiguous = Map.of("asset", Set.of(assetPin), "governance", Set.of(assetPin));
        assertThatThrownBy(() -> VerifiedServicePeerResolver.requireUnambiguous(ambiguous))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(resolver.resolve(tlsRequest(asset), ambiguous)).isEmpty();
    }

    @Test
    void malformedConfigurationIsRejected() {
        assertThatThrownBy(() -> VerifiedServicePeerResolver.requireUnambiguous(Map.of("asset", Set.of("not-hex"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VerifiedServicePeerResolver.requireUnambiguous(Map.of(" ", Set.of(assetPin))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VerifiedServicePeerResolver.requireUnambiguous(Map.of("asset", Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(resolver.resolve(tlsRequest(asset), Map.of("asset", Set.of("not-hex")))).isEmpty();
    }

    @Test
    void missingConfigurationDeniesEveryone() {
        assertThat(resolver.resolve(tlsRequest(asset), null)).isEmpty();
        assertThat(resolver.resolve(tlsRequest(asset), Map.of())).isEmpty();
    }

    @Test
    void wrongAttributeTypeIsIgnored() {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, assetPin);
        assertThat(resolver.resolve(request, Map.of("asset", Set.of(assetPin)))).isEmpty();
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[0]);
        assertThat(resolver.resolve(request, Map.of("asset", Set.of(assetPin)))).isEmpty();
    }
}
