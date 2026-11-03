package cn.iocoder.yudao.framework.mq.core.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅显式提供一次性原生服务地址时运行，测试本身不创建任何队列或主题。 */
class ExternalMqNativeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_KAFKA_ADDR", matches = ".+")
    void kafkaDescribeCluster() throws Exception {
        ExternalMqConnectionConfig config = new ExternalMqConnectionConfig()
                .setProtocol(ExternalMqConnectionConfig.Protocol.KAFKA)
                .setAddresses(env("KAFKA_ADDR")).setUsername(env("KAFKA_USER")).setPassword(env("KAFKA_PASSWORD"))
                .setSecurityProtocol(env("KAFKA_SECURITY_PROTOCOL") == null ? "PLAINTEXT" : env("KAFKA_SECURITY_PROTOCOL"))
                .setTimeoutMillis(10000);
        ExternalMqConnectionProbe.test(config);
        if (config.getSecurityProtocol().startsWith("SASL_")) {
            config.setPassword(java.util.UUID.randomUUID().toString());
            assertThatThrownBy(() -> ExternalMqConnectionProbe.test(config)).hasMessageContaining("只读探测失败");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_RABBIT_ADDR", matches = ".+")
    void rabbitAuthenticationAndVhost() throws Exception {
        ExternalMqConnectionConfig config = new ExternalMqConnectionConfig()
                .setProtocol(ExternalMqConnectionConfig.Protocol.RABBITMQ)
                .setAddresses(env("RABBIT_ADDR")).setUsername(env("RABBIT_USER"))
                .setPassword(env("RABBIT_PASSWORD")).setVirtualHost(env("RABBIT_VHOST") == null ? "/" : env("RABBIT_VHOST"));
        ExternalMqConnectionProbe.test(config);
        config.setVirtualHost("missing-" + java.util.UUID.randomUUID());
        assertThatThrownBy(() -> ExternalMqConnectionProbe.test(config)).hasMessageContaining("只读探测失败");
        config.setVirtualHost(env("RABBIT_VHOST") == null ? "/" : env("RABBIT_VHOST"));
        config.setPassword(java.util.UUID.randomUUID().toString());
        assertThatThrownBy(() -> ExternalMqConnectionProbe.test(config)).hasMessageContaining("只读探测失败");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_ROCKET_NAMESRV", matches = ".+")
    void rocketNameServerAndBroker() throws Exception {
        ExternalMqConnectionConfig config = new ExternalMqConnectionConfig()
                .setProtocol(ExternalMqConnectionConfig.Protocol.ROCKETMQ)
                .setAddresses(env("ROCKET_NAMESRV")).setUsername(env("ROCKET_ACCESS_KEY"))
                .setPassword(env("ROCKET_SECRET")).setTimeoutMillis(10000);
        ExternalMqConnectionProbe.test(config);
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            config.setPassword(java.util.UUID.randomUUID().toString());
            assertThatThrownBy(() -> ExternalMqConnectionProbe.test(config)).hasMessageContaining("只读探测失败");
        }
    }

    private static String env(String key) {
        return System.getenv("DATAHUB_TEST_" + key);
    }
}
