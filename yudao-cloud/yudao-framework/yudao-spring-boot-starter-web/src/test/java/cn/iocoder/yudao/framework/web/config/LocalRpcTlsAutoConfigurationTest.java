package cn.iocoder.yudao.framework.web.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRpcTlsAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LocalRpcTlsAutoConfiguration.class))
            .withBean(DefaultSslBundleRegistry.class,
                    () -> new DefaultSslBundleRegistry("test", SslBundle.of(SslStoreBundle.NONE)));

    @Test
    void activatesOnlyWithExplicitLocalPort() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).doesNotHaveBean("localRpcTlsConnector"));
        runner.withPropertyValues("spring.profiles.active=prod", "yudao.web.local-rpc-tls.port=50044")
                .run(context -> assertThat(context).doesNotHaveBean("localRpcTlsConnector"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsLoopbackMutualTlsAlongsideTheOriginalConnector() throws Exception {
        int port;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        runner.withPropertyValues("spring.profiles.active=local", "yudao.web.local-rpc-tls.port=" + port,
                "yudao.web.local-rpc-tls.ssl-bundle=test").run(context -> {
            var factory = new TomcatServletWebServerFactory(50004);
            var customizer = (WebServerFactoryCustomizer<TomcatServletWebServerFactory>)
                    context.getBean("localRpcTlsConnector");
            customizer.customize(factory);
            assertThat(factory.getPort()).isEqualTo(50004);
            var connector = factory.getAdditionalConnectors().getFirst();
            var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
            assertThat(protocol.getAddress().isLoopbackAddress()).isTrue();
            assertThat(connector.getSecure()).isTrue();
            assertThat(connector.getPort()).isEqualTo(port);
            assertThat(protocol.findSslHostConfigs()[0].getCertificateVerification())
                    .isEqualTo(SSLHostConfig.CertificateVerification.REQUIRED);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsTheListenerWhenThePortIsAlreadyTaken() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            runner.withPropertyValues("spring.profiles.active=local",
                    "yudao.web.local-rpc-tls.port=" + occupied.getLocalPort(),
                    "yudao.web.local-rpc-tls.ssl-bundle=test").run(context -> {
                var factory = new TomcatServletWebServerFactory(50004);
                ((WebServerFactoryCustomizer<TomcatServletWebServerFactory>) context.getBean("localRpcTlsConnector"))
                        .customize(factory);
                assertThat(factory.getAdditionalConnectors()).isEmpty();
            });
        }
    }
}
