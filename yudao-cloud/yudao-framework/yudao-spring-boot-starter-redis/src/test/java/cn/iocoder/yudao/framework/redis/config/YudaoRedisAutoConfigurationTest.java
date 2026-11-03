package cn.iocoder.yudao.framework.redis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SslVerificationMode;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class YudaoRedisAutoConfigurationTest {

    private static final String MINIMUM_IDLE_PROPERTY =
            "yudao.redis.single-server-connection-minimum-idle-size";

    private static final String OVERRIDE_BEAN = "singleServerConnectionMinimumIdleSizeCustomizer";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YudaoRedisAutoConfiguration.class,
                    RedissonAutoConfigurationV4.class))
            .withUserConfiguration(LazyRedisClientConfiguration.class)
            .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=16379");

    @Test
    void absentPropertyKeepsRedissonDefaultsAndTemplate() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(OVERRIDE_BEAN);
            assertThat(context.getBean(YudaoRedisProperties.class)
                    .getSingleServerConnectionMinimumIdleSize()).isNull();
            var singleServer = context.getBean(RedissonClient.class).getConfig().useSingleServer();
            assertThat(singleServer.getConnectionMinimumIdleSize()).isEqualTo(24);
            assertThat(singleServer.getConnectionPoolSize()).isEqualTo(64);
            assertThat(singleServer.getTimeout()).isEqualTo(3000);
            assertThat(context.getBean("redisTemplate", RedisTemplate.class).getValueSerializer())
                    .isInstanceOf(GenericJacksonJsonRedisSerializer.class);
        });
    }

    @Test
    void bindsOptionalMinimumAndPreservesStandardRedisConfiguration(CapturedOutput output) {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=2",
                "spring.data.redis.username=test-only-user",
                "spring.data.redis.password=test-only-password",
                "spring.data.redis.database=3", "spring.data.redis.ssl.enabled=true",
                "spring.data.redis.timeout=5s", "spring.data.redis.connect-timeout=7s")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean(OVERRIDE_BEAN);
                    assertThat(context.getBean(YudaoRedisProperties.class)
                            .getSingleServerConnectionMinimumIdleSize()).isEqualTo(2);
                    Config config = context.getBean(RedissonClient.class).getConfig();
                    var singleServer = config.useSingleServer();
                    assertThat(singleServer.getConnectionMinimumIdleSize()).isEqualTo(2);
                    assertThat(singleServer.getConnectionPoolSize()).isEqualTo(64);
                    assertThat(singleServer.getAddress()).isEqualTo("rediss://127.0.0.1:16379");
                    assertThat(singleServer.getDatabase()).isEqualTo(3);
                    assertThat(singleServer.getTimeout()).isEqualTo(5000);
                    assertThat(singleServer.getConnectTimeout()).isEqualTo(7000);
                    assertThat(singleServer.getSubscriptionConnectionMinimumIdleSize()).isEqualTo(1);
                    assertThat(singleServer.getSubscriptionConnectionPoolSize()).isEqualTo(50);
                    assertThat(config.getUsername()).isEqualTo("test-only-user");
                    assertThat(config.getPassword()).isEqualTo("test-only-password");
                    assertThat(config.getSslVerificationMode()).isEqualTo(SslVerificationMode.STRICT);
                });
        assertThat(output).doesNotContain("test-only-user", "test-only-password");
    }

    @Test
    void preservesExplicitRedissonMaximumAndTimeouts() {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=2",
                singleServerYaml(8)).run(context -> {
            assertThat(context).hasNotFailed();
            var singleServer = context.getBean(RedissonClient.class).getConfig().useSingleServer();
            assertThat(singleServer.getAddress()).isEqualTo("redis://127.0.0.1:16379");
            assertThat(singleServer.getConnectionMinimumIdleSize()).isEqualTo(2);
            assertThat(singleServer.getConnectionPoolSize()).isEqualTo(8);
            assertThat(singleServer.getTimeout()).isEqualTo(4100);
            assertThat(singleServer.getConnectTimeout()).isEqualTo(6100);
            assertThat(singleServer.getSubscriptionConnectionPoolSize()).isEqualTo(7);
        });
    }

    @Test
    void acceptsZeroMinimum() {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RedissonClient.class).getConfig().useSingleServer()
                    .getConnectionMinimumIdleSize()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"cluster", "sentinel", "master-slave", "replicated"})
    void absentPropertyPreservesNonSingleServerModes(String mode) {
        contextRunner.withPropertyValues(nonSingleServerYaml(mode)).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(OVERRIDE_BEAN);
            Config config = context.getBean(RedissonClient.class).getConfig();
            assertThat(config.isSingleConfig()).isFalse();
            switch (mode) {
                case "cluster" -> assertThat(config.isClusterConfig()).isTrue();
                case "sentinel" -> assertThat(config.isSentinelConfig()).isTrue();
                case "master-slave" -> assertThat(config.useMasterSlaveServers().getMasterAddress())
                        .isEqualTo("redis://127.0.0.1:16379");
                case "replicated" -> assertThat(config.useReplicatedServers().getNodeAddresses())
                        .containsExactly("redis://127.0.0.1:16379", "redis://127.0.0.1:16380");
                default -> throw new AssertionError();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"cluster", "sentinel", "master-slave", "replicated"})
    void rejectsOverrideForNonSingleServerModes(String mode) {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=2",
                nonSingleServerYaml(mode)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining(MINIMUM_IDLE_PROPERTY)
                    .hasStackTraceContaining("can only be configured in Redisson single-server mode");
        });
    }

    @Test
    void rejectsNegativeMinimum() {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining(MINIMUM_IDLE_PROPERTY)
                    .hasStackTraceContaining("must be greater than or equal to 0");
        });
    }

    @Test
    void rejectsMinimumAboveConfiguredMaximum(CapturedOutput output) {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=9", singleServerYaml(8))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining(MINIMUM_IDLE_PROPERTY)
                            .hasStackTraceContaining("must not exceed Redisson single-server connectionPoolSize (8)");
                });
        assertThat(output).doesNotContain("test-only-user", "test-only-password");
    }

    @Test
    void rejectsNonIntegerProperty() {
        contextRunner.withPropertyValues(MINIMUM_IDLE_PROPERTY + "=invalid").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("single-server-connection-minimum-idle-size");
        });
    }

    private static String singleServerYaml(int maximum) {
        return "spring.redis.redisson.config=" + """
                username: test-only-user
                password: test-only-password
                singleServerConfig:
                  address: redis://127.0.0.1:16379
                  connectionPoolSize: %d
                  connectionMinimumIdleSize: 2
                  timeout: 4100
                  connectTimeout: 6100
                  subscriptionConnectionPoolSize: 7
                """.formatted(maximum);
    }

    private static String nonSingleServerYaml(String mode) {
        String server = switch (mode) {
            case "cluster" -> """
                    clusterServersConfig:
                      nodeAddresses: [redis://127.0.0.1:16379, redis://127.0.0.1:16380]
                    """;
            case "sentinel" -> """
                    sentinelServersConfig:
                      masterName: test-master
                      sentinelAddresses: [redis://127.0.0.1:26379]
                    """;
            case "master-slave" -> """
                    masterSlaveServersConfig:
                      masterAddress: redis://127.0.0.1:16379
                      slaveAddresses: [redis://127.0.0.1:16380]
                    """;
            case "replicated" -> """
                    replicatedServersConfig:
                      nodeAddresses: [redis://127.0.0.1:16379, redis://127.0.0.1:16380]
                    """;
            default -> throw new IllegalArgumentException(mode);
        };
        return "spring.redis.redisson.config=" + server;
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyRedisClientConfiguration {

        @Bean
        RedissonAutoConfigurationCustomizer lazyRedisClientForContextTests() {
            return config -> config.setLazyInitialization(true);
        }

    }

}
