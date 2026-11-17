package cn.iocoder.yudao.framework.web.config;

import org.apache.catalina.connector.Connector;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.tomcat.SslConnectorCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/** 本地开发的 mTLS 回环监听器，原有 HTTP 连接器继续处理网关和服务发现流量。 */
@AutoConfiguration
@Profile("local")
@ConditionalOnClass(TomcatServletWebServerFactory.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty("yudao.web.local-rpc-tls.port")
public class LocalRpcTlsAutoConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> localRpcTlsConnector(
            @Value("${yudao.web.local-rpc-tls.port}") int port,
            @Value("${yudao.web.local-rpc-tls.ssl-bundle}") String bundleName, SslBundles bundles) {
        return factory -> {
            Connector connector = new Connector(factory.getProtocol());
            connector.setPort(port);
            if (!connector.setProperty("address", "127.0.0.1")) {
                throw new IllegalStateException("无法将本地 RPC 连接器绑定到回环地址");
            }
            new SslConnectorCustomizer(LogFactory.getLog(LocalRpcTlsAutoConfiguration.class), connector, Ssl.ClientAuth.NEED)
                    .customize(bundles.getBundle(bundleName), Map.of());
            factory.addAdditionalConnectors(connector);
        };
    }
}
