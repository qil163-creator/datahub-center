package cn.iocoder.yudao.framework.mq.core.connection;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyRemotingClient;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.RequestCode;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.body.KVTable;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/** 不绑定平台消息客户端，不声明主题、队列、消费组或收发业务消息。 */
public final class ExternalMqConnectionProbe {
    private ExternalMqConnectionProbe() {
    }

    public static void test(ExternalMqConnectionConfig config) throws Exception {
        if (config == null || config.getProtocol() == null || blank(config.getAddresses())
                || config.getTimeoutMillis() < 100 || config.getTimeoutMillis() > 30000) {
            throw new IllegalArgumentException("消息服务类型、地址或超时无效");
        }
        try {
            switch (config.getProtocol()) {
                case KAFKA -> testKafka(config);
                case RABBITMQ -> testRabbit(config);
                case ROCKETMQ -> testRocket(config);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("消息服务连接测试已中断");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("消息服务地址、安全协议或认证配置无效");
        } catch (Exception failure) {
            throw new IllegalStateException(config.getProtocol() + " 协议认证或只读探测失败，请检查地址、TLS、认证及访问权限");
        }
    }

    static Properties kafkaProperties(ExternalMqConnectionConfig config) {
        String protocol = config.getSecurityProtocol();
        if (protocol == null || !Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(protocol)) {
            throw new IllegalArgumentException("Kafka 安全协议无效");
        }
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.getAddresses());
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "connection-probe-" + UUID.randomUUID());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, config.getTimeoutMillis());
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, config.getTimeoutMillis());
        properties.put("security.protocol", protocol);
        if (protocol.startsWith("SASL_")) {
            String mechanism = config.getSaslMechanism();
            if (mechanism == null || !Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512").contains(mechanism)
                    || blank(config.getUsername()) || blank(config.getPassword())) {
                throw new IllegalArgumentException("Kafka SASL 认证类型、用户名或密码无效");
            }
            String module = "PLAIN".equals(mechanism)
                    ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                    : "org.apache.kafka.common.security.scram.ScramLoginModule";
            properties.put("sasl.mechanism", mechanism);
            properties.put("sasl.jaas.config", module + " required username=\"" + jaas(config.getUsername())
                    + "\" password=\"" + jaas(config.getPassword()) + "\";");
        } else if (!blank(config.getUsername()) || !blank(config.getPassword())) {
            throw new IllegalArgumentException("Kafka 配置了认证信息时必须选择 SASL 安全协议");
        }
        return properties;
    }

    private static void testKafka(ExternalMqConnectionConfig config) throws Exception {
        Admin admin = Admin.create(kafkaProperties(config));
        try {
            if (admin.describeCluster(new DescribeClusterOptions().timeoutMs(config.getTimeoutMillis()))
                    .nodes().get(config.getTimeoutMillis(), TimeUnit.MILLISECONDS).isEmpty()) {
                throw new IllegalStateException("Kafka 未返回有效节点");
            }
            if (!blank(config.getTopic())) {
                admin.describeTopics(List.of(config.getTopic()),
                                new DescribeTopicsOptions().timeoutMs(config.getTimeoutMillis()))
                        .allTopicNames().get(config.getTimeoutMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            admin.close(Duration.ofMillis(Math.min(1000, config.getTimeoutMillis())));
        }
    }

    private static void testRabbit(ExternalMqConnectionConfig config) throws Exception {
        URI address = URI.create("amqp://" + config.getAddresses());
        if (address.getHost() == null || address.getRawUserInfo() != null
                || (address.getRawPath() != null && !address.getRawPath().isEmpty())
                || address.getRawQuery() != null || address.getRawFragment() != null
                || address.getPort() == 0 || address.getPort() > 65535) {
            throw new IllegalArgumentException("RabbitMQ 地址必须为 host:port");
        }
        if (blank(config.getUsername()) || config.getPassword() == null) {
            throw new IllegalArgumentException("RabbitMQ 用户名和密码不能为空");
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(address.getHost());
        factory.setPort(address.getPort() < 0 ? (config.isSsl() ? 5671 : 5672) : address.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost() == null ? "/" : config.getVirtualHost());
        factory.setConnectionTimeout(config.getTimeoutMillis());
        factory.setHandshakeTimeout(config.getTimeoutMillis());
        factory.setShutdownTimeout(1000);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        if (config.isSsl()) {
            factory.useSslProtocol(SSLContext.getDefault());
            factory.enableHostnameVerification();
        }
        try (Connection connection = factory.newConnection("connection-probe-" + UUID.randomUUID())) {
            if (!connection.isOpen()) {
                throw new IllegalStateException("RabbitMQ 未建立有效会话");
            }
        }
    }

    private static void testRocket(ExternalMqConnectionConfig config) throws Exception {
        // 当前 SDK 的 TLS 依赖全局可变信任配置，禁止使用默认的测试证书信任路径。
        if (config.isSsl()) {
            throw new IllegalArgumentException("RocketMQ 当前仅支持原生明文协议及 ACL，尚未开放 TLS 连接测试");
        }
        List<String> servers = Arrays.stream(config.getAddresses().split(";", -1)).map(String::trim).toList();
        if (servers.isEmpty() || servers.size() > 8 || servers.stream().anyMatch(ExternalMqConnectionProbe::blank)) {
            throw new IllegalArgumentException("RocketMQ NameServer 地址无效，最多允许 8 个节点");
        }
        if (blank(config.getUsername()) != blank(config.getPassword())) {
            throw new IllegalArgumentException("RocketMQ accessKey 和 secretKey 必须同时填写");
        }
        NettyClientConfig clientConfig = new NettyClientConfig();
        clientConfig.setConnectTimeoutMillis(config.getTimeoutMillis());
        clientConfig.setClientWorkerThreads(1);
        clientConfig.setClientCallbackExecutorThreads(1);
        clientConfig.setScanAvailableNameSrv(false);
        clientConfig.setUseTLS(config.isSsl());
        NettyRemotingClient client = new NettyRemotingClient(clientConfig);
        if (!blank(config.getUsername())) {
            client.registerRPCHook(new AclClientRPCHook(new SessionCredentials(config.getUsername(), config.getPassword())));
        }
        try {
            client.start();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getTimeoutMillis());
            RemotingCommand clusterResponse = null;
            String connectedServer = null;
            for (String server : servers) {
                try {
                    clusterResponse = requireSuccess(client.invokeSync(server,
                            RemotingCommand.createRequestCommand(RequestCode.GET_BROKER_CLUSTER_INFO, null),
                            remaining(deadline)));
                    connectedServer = server;
                    break;
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception unavailable) {
                    if (System.nanoTime() >= deadline) {
                        throw unavailable;
                    }
                }
            }
            if (clusterResponse == null || clusterResponse.getBody() == null) {
                throw new IllegalStateException("RocketMQ NameServer 未返回集群信息");
            }
            ClusterInfo cluster = ClusterInfo.decode(clusterResponse.getBody(), ClusterInfo.class);
            if (cluster.getBrokerAddrTable() == null || cluster.getBrokerAddrTable().isEmpty()) {
                throw new IllegalStateException("RocketMQ 未发现可用 Broker");
            }
            if (!blank(config.getTopic())) {
                RemotingCommand route = RemotingCommand.createRequestCommand(RequestCode.GET_ROUTEINFO_BY_TOPIC, null);
                route.addExtField("topic", config.getTopic());
                requireSuccess(client.invokeSync(connectedServer, route, remaining(deadline)));
            }
            String broker = cluster.getBrokerAddrTable().values().iterator().next().selectBrokerAddr();
            RemotingCommand runtime = requireSuccess(client.invokeSync(broker,
                    RemotingCommand.createRequestCommand(RequestCode.GET_BROKER_RUNTIME_INFO, null), remaining(deadline)));
            if (runtime.getBody() == null || KVTable.decode(runtime.getBody(), KVTable.class).getTable().isEmpty()) {
                throw new IllegalStateException("RocketMQ Broker 未返回运行信息");
            }
        } finally {
            client.shutdown();
        }
    }

    private static RemotingCommand requireSuccess(RemotingCommand response) {
        if (response == null || response.getCode() != ResponseCode.SUCCESS) {
            throw new IllegalStateException("RocketMQ 只读请求失败或无权限");
        }
        return response;
    }

    private static long remaining(long deadline) {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis < 1) {
            throw new IllegalStateException("RocketMQ 连接测试超时");
        }
        return millis;
    }

    private static String jaas(String value) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Kafka SASL 凭据禁止控制字符");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
