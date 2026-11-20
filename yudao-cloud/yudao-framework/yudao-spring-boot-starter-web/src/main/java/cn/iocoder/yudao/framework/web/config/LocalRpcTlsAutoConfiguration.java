package cn.iocoder.yudao.framework.web.config;

import org.apache.catalina.connector.Connector;
import org.apache.commons.logging.Log;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;

/** 本地开发的 mTLS 回环监听器，原有 HTTP 连接器继续处理网关和服务发现流量。 */
@AutoConfiguration
@Profile("local")
@ConditionalOnClass(TomcatServletWebServerFactory.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty("yudao.web.local-rpc-tls.port")
public class LocalRpcTlsAutoConfiguration {

    private static final Log log = LogFactory.getLog(LocalRpcTlsAutoConfiguration.class);

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> localRpcTlsConnector(
            @Value("${yudao.web.local-rpc-tls.port}") int port,
            @Value("${yudao.web.local-rpc-tls.ssl-bundle}") String bundleName, SslBundles bundles) {
        return factory -> {
            // yudao-server 与独立 Infra 会导入同一份 infra.properties，先启动的进程持有该监听器，后启动的跳过
            if (!isLoopbackPortFree(port)) {
                log.warn("本地 RPC mTLS 端口 " + port + " 已被占用，跳过该监听器；文件交付由已占用该端口的 Infra 进程处理");
                return;
            }
            Connector connector = new Connector(factory.getProtocol());
            connector.setPort(port);
            if (!connector.setProperty("address", "127.0.0.1")) {
                throw new IllegalStateException("无法将本地 RPC 连接器绑定到回环地址");
            }
            new SslConnectorCustomizer(log, connector, Ssl.ClientAuth.NEED)
                    .customize(bundles.getBundle(bundleName), Map.of());
            factory.addAdditionalConnectors(connector);
        };
    }

    static boolean isLoopbackPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
