package cn.iocoder.yudao.framework.redis.config;

import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis 配置类
 */
@AutoConfiguration(before = RedissonAutoConfigurationV4.class) // 目的：使用自己定义的 RedisTemplate Bean
@EnableConfigurationProperties(YudaoRedisProperties.class)
public class YudaoRedisAutoConfiguration {

    private static final String MINIMUM_IDLE_PROPERTY =
            "yudao.redis.single-server-connection-minimum-idle-size";

    /**
     * 通过 Redisson 的公开扩展点调整单机连接预热，保留其余配置与连接管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "yudao.redis", name = "single-server-connection-minimum-idle-size")
    public RedissonAutoConfigurationCustomizer singleServerConnectionMinimumIdleSizeCustomizer(
            YudaoRedisProperties properties) {
        int minimumIdleSize = properties.getSingleServerConnectionMinimumIdleSize();
        if (minimumIdleSize < 0) {
            throw new IllegalArgumentException(MINIMUM_IDLE_PROPERTY + " must be greater than or equal to 0");
        }
        return config -> {
            if (!config.isSingleConfig()) {
                throw new IllegalArgumentException(MINIMUM_IDLE_PROPERTY
                        + " can only be configured in Redisson single-server mode");
            }
            var singleServer = config.useSingleServer();
            if (minimumIdleSize > singleServer.getConnectionPoolSize()) {
                throw new IllegalArgumentException(MINIMUM_IDLE_PROPERTY
                        + " must not exceed Redisson single-server connectionPoolSize ("
                        + singleServer.getConnectionPoolSize() + ")");
            }
            singleServer.setConnectionMinimumIdleSize(minimumIdleSize);
        };
    }

    /**
     * 创建 RedisTemplate Bean，使用 JSON 序列化方式
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        // 创建 RedisTemplate 对象
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        // 设置 RedisConnection 工厂。😈 它就是实现多种 Java Redis 客户端接入的秘密工厂。感兴趣的胖友，可以自己去撸下。
        template.setConnectionFactory(factory);
        // 使用 String 序列化方式，序列化 KEY 。
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        // 使用 JSON 序列化方式，序列化 VALUE
        RedisSerializer<?> redisSerializer = buildRedisSerializer();
        template.setValueSerializer(redisSerializer);
        template.setHashValueSerializer(redisSerializer);
        return template;
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    public static RedisSerializer<?> buildRedisSerializer() {
        RedisSerializer<Object> json = RedisSerializer.json();
        // 特殊：spring boot 4.x 无需解决 LocalDateTime 的序列化
        // 原因：Spring Data Redis 4 使用 Jackson 3，RedisSerializer.json() 已支持 Java Time 类型
        return json;
    }

}
