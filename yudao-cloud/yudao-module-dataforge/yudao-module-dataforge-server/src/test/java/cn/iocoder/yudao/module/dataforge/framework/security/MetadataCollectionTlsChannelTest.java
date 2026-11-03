package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApiImpl;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.systemsource.SystemSourceMapper;
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本机真实 TLS 握手、生产 Feign 客户端和服务门禁共同验证，测试证书只生成在临时目录。 */
class MetadataCollectionTlsChannelTest {
    private static final String PASSWORD = "metadata-rpc-fixture";
    private static final String PATH = MetadataCollectionApi.PREFIX + "/options";
    @TempDir
    static Path directory;
    private static WebServer server;
    private static SslBundle governance;
    private static SslBundle stranger;
    private static SslBundle anonymous;
    private static SslBundle untrustedServer;
    private static final AtomicInteger reachedProvider = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        KeyStore serverKeys = keyStore("server", true);
        KeyStore governanceKeys = keyStore("governance", false);
        KeyStore strangerKeys = keyStore("stranger", false);
        var serverCertificate = (X509Certificate) serverKeys.getCertificate("server");
        var governanceCertificate = (X509Certificate) governanceKeys.getCertificate("governance");
        var strangerCertificate = (X509Certificate) strangerKeys.getCertificate("stranger");
        var serverBundle = bundle(serverKeys, governanceCertificate, strangerCertificate);
        governance = bundle(governanceKeys, serverCertificate);
        stranger = bundle(strangerKeys, serverCertificate);
        anonymous = bundle(null, serverCertificate);
        untrustedServer = bundle(governanceKeys, strangerCertificate);
        var peers = Map.of("governance", Set.of(VerifiedServicePeerResolver.fingerprint(governanceCertificate)));
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        var ssl = new Ssl();
        ssl.setBundle("server");
        // 同端口可承载普通 HTTPS；仅受保护 RPC 必须出示客户端证书，由生产门禁强制执行。
        ssl.setClientAuth(Ssl.ClientAuth.WANT);
        factory.setSsl(ssl);
        factory.setSslBundles(new DefaultSslBundleRegistry("server", serverBundle));
        server = factory.getWebServer(context -> {
            context.addFilter("metadataGate", new MetadataCollectionRpcFilter(() -> peers))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, MetadataCollectionApi.PREFIX + "/*");
            context.addServlet("metadataOptions", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    reachedProvider.incrementAndGet();
                    var provider = new MetadataCollectionApiImpl();
                    var permissions = mock(PermissionApi.class);
                    when(permissions.hasAnyPermissions(9L, "governance:meta-collect-task:query", "governance:meta-file:query"))
                            .thenReturn(CommonResult.success(true));
                    when(permissions.hasAnyPermissions(10L, "governance:meta-collect-task:query", "governance:meta-file:query"))
                            .thenReturn(CommonResult.success(false));
                    ReflectionTestUtils.setField(provider, "servletRequest", request);
                    ReflectionTestUtils.setField(provider, "permissionApi", permissions);
                    ReflectionTestUtils.setField(provider, "databaseMapper", mock(DatabaseMapper.class));
                    ReflectionTestUtils.setField(provider, "systemMapper", mock(SystemSourceMapper.class));
                    response.setContentType("application/json;charset=UTF-8");
                    try {
                        response.getWriter().write(JsonUtils.toJsonString(provider.options(Long.valueOf(request.getParameter("actorId")))));
                    } catch (ServiceException denied) {
                        response.setStatus(denied.getCode());
                        response.getWriter().write(JsonUtils.toJsonString(CommonResult.error(denied.getCode(), denied.getMessage())));
                    }
                }
            }).addMapping(PATH);
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop();
    }

    @Test
    void governanceCertificateAndAuthorizedActorReachProviderOverRealTls() throws Exception {
        try (Response response = call(governance, "localhost", 9, Map.of())) {
            assertEquals(200, response.status());
            assertTrue(new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("\"code\":0"));
        }
    }

    @Test
    void trustedServiceStillCannotClaimAnActorWithoutPermission() throws Exception {
        try (Response response = call(governance, "localhost", 10, Map.of())) {
            assertEquals(403, response.status());
        }
    }

    @Test
    void anonymousAndUnpinnedCertificatesCannotForgeServiceIdentity() throws Exception {
        int before = reachedProvider.get();
        var forgedHeaders = Map.<String, Collection<String>>of("login-user", List.of("{\"id\":9}"),
                "X-Forwarded-Client-Cert", List.of("governance"), "tenant-id", List.of("1"));
        try (Response response = call(anonymous, "localhost", 9, forgedHeaders)) {
            assertEquals(401, response.status());
        }
        try (Response response = call(stranger, "localhost", 9, forgedHeaders)) {
            assertEquals(401, response.status());
        }
        assertEquals(before, reachedProvider.get());
    }

    @Test
    void clientVerifiesServerTrustAndHostname() {
        assertThrows(SSLException.class, () -> call(governance, "127.0.0.1", 9, Map.of()));
        assertThrows(SSLException.class, () -> call(untrustedServer, "localhost", 9, Map.of()));
    }

    @Test
    void absentBundleAndPlainHttpFailClosedBeforeSending() {
        var request = Request.create(Request.HttpMethod.GET, "http://localhost:1" + PATH + "?actorId=9",
                Map.of(), null, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> client(governance).execute(request, new Request.Options()));
        assertThrows(IllegalStateException.class, () -> client(null).execute(request, new Request.Options()));
    }

    private static Response call(SslBundle bundle, String host, int actor, Map<String, Collection<String>> headers)
            throws IOException {
        var request = Request.create(Request.HttpMethod.GET, "https://" + host + ":" + server.getPort()
                + PATH + "?actorId=" + actor, headers, null, StandardCharsets.UTF_8);
        return client(bundle).execute(request, new MetadataCollectionApi.ClientConfig().metadataCollectionOptions());
    }

    private static Client client(SslBundle bundle) {
        var beans = new DefaultListableBeanFactory();
        if (bundle != null) beans.registerSingleton("sslBundles", new DefaultSslBundleRegistry("metadata-client", bundle));
        var environment = new MockEnvironment().withProperty(MetadataCollectionApi.ClientConfig.SSL_BUNDLE_PROPERTY, "metadata-client");
        return new MetadataCollectionApi.ClientConfig().metadataCollectionClient(environment,
                beans.getBeanProvider(SslBundles.class), beans.getBeanProvider(LoadBalancerClient.class),
                beans.getBeanProvider(LoadBalancerClientFactory.class), beans.getBeanProvider(LoadBalancerFeignRequestTransformer.class));
    }

    private static KeyStore keyStore(String alias, boolean server) throws Exception {
        Path store = directory.resolve(alias + ".p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        var command = new ArrayList<>(List.of(keytool.toString(), "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + alias,
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD));
        if (server) command.addAll(List.of("-ext", "SAN=dns:localhost"));
        Path log = directory.resolve(alias + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("测试证书生成超时");
        }
        if (process.exitValue() != 0) throw new IOException("测试证书生成失败：" + Files.readString(log));
        KeyStore result = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            result.load(input, PASSWORD.toCharArray());
        }
        return result;
    }

    private static SslBundle bundle(KeyStore keys, X509Certificate... certificates) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (int i = 0; i < certificates.length; i++) trust.setCertificateEntry("trusted-" + i, certificates[i]);
        return SslBundle.of(SslStoreBundle.of(keys, PASSWORD, trust));
    }
}
