package cn.iocoder.yudao.framework.redis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 客户端配置。
 */
@Data
@ConfigurationProperties(prefix = "yudao.redis")
public class YudaoRedisProperties {

    /**
     * 单机模式连接池的最小空闲连接数；未配置时保留 Redisson 默认值。
     * 较小值可减少开发环境启动时的连接预热，不能代替 Redis 连通性与容量排查。
     */
    private Integer singleServerConnectionMinimumIdleSize;

}
