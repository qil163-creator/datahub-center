package cn.iocoder.yudao.module.supply.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceClientConfiguration;
import feign.Client;
import feign.Request;
import feign.Response;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 TLS 链路：嵌入式 Tomcat 启用客户端认证，门禁只认容器握手得到的证书；
 * 调用方使用生产同款 {@link SupplyGrantSourceClientConfiguration} 客户端，校验服务端证书与主机名。
 */
class SourceGrantTlsChannelTest {

    private static final String PATH = SourceGrantRpcFilter.PROTECTED_PATH + "/operation";

    @TempDir
    static Path directory;
    private static WebServer server;
    private static int port;
    private static SslBundle assetBundle;
    private static SslBundle strangerBundle;
    private static SslBundle anonymousBundle;
    private static SslBundle distrustingBundle;

    @BeforeAll
    static void startMutualTlsServer() throws Exception {
        Path serverStore = TemporaryCertificates.keyStore(directory, "server", "localhost", null, 30, List.of("dns:localhost"));
        Path assetStore = TemporaryCertificates.keyStore(directory, "asset", "asset-server", null, 30, List.of());
        Path strangerStore = TemporaryCertificates.keyStore(directory, "stranger", "unknown-service", null, 30, List.of());
        X509Certificate serverCert = TemporaryCertificates.certificate(serverStore, "server");
        X509Certificate assetCert = TemporaryCertificates.certificate(assetStore, "asset");
        X509Certificate strangerCert = TemporaryCertificates.certificate(strangerStore, "stranger");

        // 服务端 TLS 信任两张客户端证书，门禁只 pin 其中 asset 的指纹：TLS 握手成功不等于服务身份通过。
        SslBundle serverBundle = TemporaryCertificates.bundle(serverStore,
                TemporaryCertificates.trustStore(assetCert, strangerCert));
        assetBundle = TemporaryCertificates.bundle(assetStore, TemporaryCertificates.trustStore(serverCert));
        strangerBundle = TemporaryCertificates.bundle(strangerStore, TemporaryCertificates.trustStore(serverCert));
        anonymousBundle = TemporaryCertificates.bundle(null, TemporaryCertificates.trustStore(serverCert));
        distrustingBundle = TemporaryCertificates.bundle(assetStore, TemporaryCertificates.trustStore(strangerCert));

        Map<String, Set<String>> peers = Map.of("asset", Set.of(TemporaryCertificates.pin(assetCert)));
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        Ssl ssl = new Ssl();
        ssl.setBundle("server");
        ssl.setClientAuth(Ssl.ClientAuth.WANT);
        factory.setSsl(ssl);
        factory.setSslBundles(new DefaultSslBundleRegistry("server", serverBundle));
        server = factory.getWebServer(context -> {
            var gate = new SourceGrantRpcFilter(new VerifiedServicePeerResolver(), () -> peers);
            context.addFilter("gate", gate).addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false,
                    SourceGrantRpcFilter.PROTECTED_PATH + "/*");
            context.addServlet("echo", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    response.setStatus(200);
                    response.setContentType("text/plain;charset=UTF-8");
                    response.getWriter().write(SourceGrantRpcFilter.requireVerifiedPeer(request)
                            + "|" + request.getHeader("login-user"));
                }
            }).addMapping(SourceGrantRpcFilter.PROTECTED_PATH + "/*");
        });
        server.start();
        port = server.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private static Response call(SslBundle bundle, String host, Map<String, java.util.Collection<String>> headers)
            throws IOException {
        Client client = SupplyGrantSourceClientConfiguration.mutualTlsClient(bundle);
        String url = "https://" + host + ":" + port + PATH + "?sourceSystem=asset&operationId=op-1";
        return client.execute(Request.create(Request.HttpMethod.GET, url, headers, null, StandardCharsets.UTF_8),
                new Request.Options());
    }

    private static String body(Response response) throws IOException {
        try (response) {
            return new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void pinnedClientCertificateOverRealTlsIsIdentified() throws Exception {
        Response response = call(assetBundle, "localhost", Map.of("login-user", List.of("{\"id\":1}")));
        assertThat(response.status()).isEqualTo(200);
        // 身份来自证书；同时到达的 login-user 头在受信通道上仍可传播，但不是身份来源。
        assertThat(body(response)).isEqualTo("asset|{\"id\":1}");
    }

    @Test
    void trustedButUnpinnedClientCertificateIsRejectedByTheGate() throws Exception {
        Response response = call(strangerBundle, "localhost", Map.of());
        assertThat(response.status()).isEqualTo(401);
        assertThat(body(response)).contains("\"code\":401");
    }

    @Test
    void anonymousTlsClientWithForgedHeadersIsRejected() throws Exception {
        Response response = call(anonymousBundle, "localhost", Map.of(
                "login-user", List.of("{\"id\":1,\"tenantId\":1}"),
                "tenant-id", List.of("1"),
                "X-Forwarded-Client-Cert", List.of("Hash=deadbeef")));
        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void clientVerifiesServerHostname() {
        // 服务端证书 SAN 只有 localhost：按 IP 访问必须在握手阶段失败，而不是被“信任所有主机名”放行。
        assertThatThrownBy(() -> call(assetBundle, "127.0.0.1", Map.of()))
                .isInstanceOf(SSLException.class);
    }

    @Test
    void clientDoesNotTrustAnUnknownServerCertificate() {
        assertThatThrownBy(() -> call(distrustingBundle, "localhost", Map.of()))
                .isInstanceOf(SSLException.class);
    }

    @Test
    void plainHttpTargetsAreRefusedBeforeConnecting() {
        Client client = SupplyGrantSourceClientConfiguration.mutualTlsClient(assetBundle);
        assertThatThrownBy(() -> client.execute(Request.create(Request.HttpMethod.GET,
                "http://localhost:1" + PATH, Map.of(), null, StandardCharsets.UTF_8), new Request.Options()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
    }

    @Test
    void unconfiguredBundleFailsClosedPerCall() {
        Client client = SupplyGrantSourceClientConfiguration.mutualTlsClient(" ", null);
        assertThatThrownBy(() -> client.execute(Request.create(Request.HttpMethod.GET,
                "https://localhost:" + port + PATH, Map.of(), null, StandardCharsets.UTF_8), new Request.Options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SupplyGrantSourceClientConfiguration.SSL_BUNDLE_PROPERTY);
        Client missing = SupplyGrantSourceClientConfiguration.mutualTlsClient("absent",
                new DefaultSslBundleRegistry("other", assetBundle));
        assertThatThrownBy(() -> missing.execute(Request.create(Request.HttpMethod.GET,
                "https://localhost:" + port + PATH, Map.of(), null, StandardCharsets.UTF_8), new Request.Options()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("absent");
    }
}
