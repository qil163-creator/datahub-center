package cn.iocoder.yudao.framework.etl.flink.rabbitmq;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import com.rabbitmq.client.ConnectionFactory;

import java.io.StringReader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

/** 只序列化批准的地址和引用，凭据文件再次绑定地址、虚拟主机和队列范围。 */
public final class ManagedRabbitConnection implements Serializable {
    private static final long serialVersionUID = 1L;
    final String host, virtualHost, queue, reference;
    final int port;
    final boolean ssl, output;

    public ManagedRabbitConnection(String host, int port, String virtualHost, String queue, String reference, boolean ssl, boolean output) {
        this.host = host; this.port = port; this.virtualHost = virtualHost; this.queue = queue; this.reference = reference; this.ssl = ssl; this.output = output;
    }

    public ConnectionFactory openFactory() {
        try {
            Properties secret = new Properties(); secret.load(new StringReader(EtlManagedSecrets.readFromEnvironment(reference)));
            if (!secret.stringPropertyNames().equals(Set.of("host", "port", "virtualHost", "sourceQueues", "sinkQueues", "useSsl", "username", "password"))) throw new IllegalArgumentException();
            if (!host.equals(secret.getProperty("host")) || !Integer.toString(port).equals(secret.getProperty("port"))
                    || !virtualHost.equals(secret.getProperty("virtualHost")) || !Boolean.toString(ssl).equals(secret.getProperty("useSsl"))
                    || !Arrays.asList(secret.getProperty(output ? "sinkQueues" : "sourceQueues").split(",", -1)).contains(queue)) throw new IllegalArgumentException();
            String username = secret.getProperty("username"), password = secret.getProperty("password");
            if (username == null || !username.matches("[A-Za-z0-9_.-]{1,64}") || password == null || password.isEmpty() || password.length() > 1024
                    || password.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            ConnectionFactory factory = new ConnectionFactory(); factory.setHost(host); factory.setPort(port); factory.setVirtualHost(virtualHost);
            factory.setUsername(username); factory.setPassword(password); factory.setConnectionTimeout(10_000); factory.setHandshakeTimeout(10_000);
            factory.setRequestedHeartbeat(30); factory.setAutomaticRecoveryEnabled(false);
            if (ssl) { factory.useSslProtocol(); factory.enableHostnameVerification(); }
            return factory;
        } catch (Exception failure) { throw new IllegalStateException("RabbitMQ 受管凭据不可用或资源范围不一致"); }
    }
}
