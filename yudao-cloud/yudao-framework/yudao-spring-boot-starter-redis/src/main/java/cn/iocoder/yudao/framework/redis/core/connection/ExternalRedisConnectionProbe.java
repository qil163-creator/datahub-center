package cn.iocoder.yudao.framework.redis.core.connection;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisClientConfig;
import org.redisson.client.RedisConnection;
import org.redisson.client.protocol.RedisCommands;

/** 目标连接按次创建和销毁；仅认证、选择数据库并执行 PING。 */
public final class ExternalRedisConnectionProbe {
    private ExternalRedisConnectionProbe() {
    }

    public static void test(ExternalRedisConnectionConfig config) {
        if (config == null || config.getHost() == null || config.getHost().isBlank()
                || config.getHost().contains("/") || config.getHost().contains("@")
                || config.getPort() < 1 || config.getPort() > 65535 || config.getDatabase() < 0
                || config.getTimeoutMillis() < 100 || config.getTimeoutMillis() > 30000) {
            throw new IllegalArgumentException("Redis 主机、端口、数据库编号或超时无效");
        }
        String host = config.getHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        RedisClient client = RedisClient.create(new RedisClientConfig()
                .setAddress((config.isSsl() ? "rediss://" : "redis://") + host + ":" + config.getPort())
                .setResolverGroup(DefaultAddressResolverGroup.INSTANCE)
                .setUsername(emptyToNull(config.getUsername())).setPassword(emptyToNull(config.getPassword()))
                .setDatabase(config.getDatabase()).setConnectTimeout(config.getTimeoutMillis())
                .setCommandTimeout(config.getTimeoutMillis()).setPingConnectionInterval(0));
        RedisConnection connection = null;
        try {
            connection = client.connect();
            if (!"PONG".equals(connection.sync(RedisCommands.PING))) {
                throw new IllegalStateException("Redis 未返回有效的 PONG 响应");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Redis 认证、数据库选择或 PING 失败，请检查连接参数、TLS 及权限");
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } finally {
                client.shutdown();
            }
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
