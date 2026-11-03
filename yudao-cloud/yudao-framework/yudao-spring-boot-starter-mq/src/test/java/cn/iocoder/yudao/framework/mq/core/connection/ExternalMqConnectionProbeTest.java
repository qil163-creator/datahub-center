package cn.iocoder.yudao.framework.mq.core.connection;

import cn.iocoder.yudao.framework.mq.rabbitmq.config.YudaoRabbitMQAutoConfiguration;
import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.remoting.netty.NettyRemotingServer;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.RequestCode;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.body.KVTable;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalMqConnectionProbeTest {
    @Test
    void saslCredentialEscapingAndAuthenticationMustBeExplicit() {
        ExternalMqConnectionConfig config = new ExternalMqConnectionConfig().setAddresses("127.0.0.1:9092")
                .setSecurityProtocol("SASL_SSL").setSaslMechanism("SCRAM-SHA-512")
                .setUsername("user\"quoted").setPassword("pass\\slash");
        var properties = ExternalMqConnectionProbe.kafkaProperties(config);
        assertThat(properties.getProperty("sasl.jaas.config"))
                .contains("username=\"user\\\"quoted\"", "password=\"pass\\\\slash\"");
        assertThat(config.toString()).doesNotContain(config.getPassword());
        config.setSecurityProtocol("PLAINTEXT");
        assertThatThrownBy(() -> ExternalMqConnectionProbe.kafkaProperties(config)).hasMessageContaining("SASL");
    }

    @Test
    void rocketReadsClusterAndBrokerWithSignedAclAndNoProducerOrConsumerRegistration() throws Exception {
        rocketFixture(false);
    }

    @Test
    void rocketCannotTreatNameServerSuccessAsAuthenticatedBrokerSuccess() throws Exception {
        rocketFixture(true);
    }

    private void rocketFixture(boolean denyBroker) throws Exception {
        List<Integer> codes = new CopyOnWriteArrayList<>();
        List<Boolean> signed = new CopyOnWriteArrayList<>();
        NettyServerConfig serverConfig = new NettyServerConfig();
        serverConfig.setBindAddress("127.0.0.1");
        serverConfig.setListenPort(0);
        serverConfig.setServerWorkerThreads(1);
        serverConfig.setServerSelectorThreads(1);
        NettyRemotingServer server = new NettyRemotingServer(serverConfig);
        try (var executor = Executors.newSingleThreadExecutor()) {
            server.registerDefaultProcessor(new NettyRequestProcessor() {
                @Override
                public RemotingCommand processRequest(ChannelHandlerContext context, RemotingCommand request) {
                    codes.add(request.getCode());
                    signed.add(request.getExtFields() != null && request.getExtFields().containsKey("Signature"));
                    RemotingCommand response = RemotingCommand.createResponseCommand(ResponseCode.SUCCESS, null);
                    if (request.getCode() == RequestCode.GET_BROKER_CLUSTER_INFO) {
                        ClusterInfo cluster = new ClusterInfo();
                        cluster.setBrokerAddrTable(Map.of("fixture", new BrokerData("fixture", "fixture",
                                new HashMap<>(Map.of(0L, "127.0.0.1:" + server.localListenPort())))));
                        response.setBody(cluster.encode());
                    } else if (request.getCode() == RequestCode.GET_BROKER_RUNTIME_INFO && !denyBroker) {
                        KVTable runtime = new KVTable();
                        runtime.setTable(new HashMap<>(Map.of("brokerVersionDesc", "fixture")));
                        response.setBody(runtime.encode());
                    } else {
                        response.setCode(ResponseCode.NO_PERMISSION);
                    }
                    return response;
                }

                @Override
                public boolean rejectRequest() {
                    return false;
                }
            }, executor);
            server.start();
            String password = UUID.randomUUID().toString();
            ExternalMqConnectionConfig config = new ExternalMqConnectionConfig()
                    .setProtocol(ExternalMqConnectionConfig.Protocol.ROCKETMQ)
                    .setAddresses("127.0.0.1:" + server.localListenPort()).setTimeoutMillis(2000)
                    .setUsername("probe-only").setPassword(password);
            if (denyBroker) {
                assertThatThrownBy(() -> ExternalMqConnectionProbe.test(config))
                        .hasMessageContaining("只读探测失败").hasMessageNotContaining(password).hasNoCause();
            } else {
                ExternalMqConnectionProbe.test(config);
            }
            assertThat(codes).containsExactly(RequestCode.GET_BROKER_CLUSTER_INFO, RequestCode.GET_BROKER_RUNTIME_INFO);
            assertThat(signed).containsExactly(true, true);
        } finally {
            server.shutdown();
        }
    }

    @Test
    void rabbitRejectsUnavailableTargetAndDoesNotLeakAuthentication() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String password = UUID.randomUUID().toString();
        ExternalMqConnectionConfig config = new ExternalMqConnectionConfig()
                .setProtocol(ExternalMqConnectionConfig.Protocol.RABBITMQ)
                .setAddresses("127.0.0.1:" + closedPort).setUsername("probe-only").setPassword(password)
                .setTimeoutMillis(300);
        assertThatThrownBy(() -> ExternalMqConnectionProbe.test(config))
                .hasMessageContaining("RABBITMQ").hasMessageNotContaining(password).hasNoCause();
    }

    @Test
    void probeDoesNotInstallOrReplacePlatformMessageClients() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoRabbitMQAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(org.springframework.amqp.support.converter.MessageConverter.class)
                        .doesNotHaveBean(com.rabbitmq.client.ConnectionFactory.class));
    }
}
