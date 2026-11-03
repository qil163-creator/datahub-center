package cn.iocoder.yudao.framework.redis.core.connection;

import lombok.Data;
import lombok.ToString;

/** 独立目标 Redis 连接参数，不继承平台缓存配置。 */
@Data
public class ExternalRedisConnectionConfig {
    private String host;
    private int port = 6379;
    private String username;
    @ToString.Exclude
    private String password;
    private int database;
    private boolean ssl;
    private int timeoutMillis = 10000;
}
