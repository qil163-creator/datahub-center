package cn.iocoder.yudao.framework.redis.core.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原生目标由外部测试编排提供，凭据不写入测试资源文件。 */
class ExternalRedisNativeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_REDIS_HOST", matches = ".+")
    void authenticatesPingsAndRejectsInvalidDatabase() {
        ExternalRedisConnectionConfig config = new ExternalRedisConnectionConfig()
                .setHost(env("HOST")).setPort(Integer.parseInt(env("PORT")))
                .setUsername(env("USER")).setPassword(env("PASSWORD"))
                .setDatabase(env("DB") == null ? 0 : Integer.parseInt(env("DB")));
        ExternalRedisConnectionProbe.test(config);
        config.setDatabase(1000000);
        assertThatThrownBy(() -> ExternalRedisConnectionProbe.test(config)).hasMessageContaining("数据库选择");
        config.setDatabase(0).setPassword(java.util.UUID.randomUUID().toString());
        assertThatThrownBy(() -> ExternalRedisConnectionProbe.test(config)).hasMessageContaining("认证");
    }

    private static String env(String key) {
        return System.getenv("DATAHUB_TEST_REDIS_" + key);
    }
}
