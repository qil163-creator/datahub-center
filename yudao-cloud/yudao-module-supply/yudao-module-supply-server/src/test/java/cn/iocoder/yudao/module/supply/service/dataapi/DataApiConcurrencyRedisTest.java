package cn.iocoder.yudao.module.supply.service.dataapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 上验证并发租约脚本；未提供 -Ddatahub.test.redis.port 时跳过。
 */
class DataApiConcurrencyRedisTest {

    private RedissonClient client;
    private RedissonConnectionFactory factory;
    private SupplyApiRedisStore store;
    private StringRedisTemplate strings;
    private final String scope = "test-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        String port = System.getProperty("datahub.test.redis.port");
        Assumptions.assumeTrue(port != null && port.matches("[0-9]{2,5}"), "未配置本地测试 Redis");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port);
        client = Redisson.create(config);
        factory = new RedissonConnectionFactory(client);
        strings = new StringRedisTemplate(factory);
        strings.afterPropertiesSet();
        RedisTemplate<String, Object> objects = new RedisTemplate<>();
        objects.setConnectionFactory(factory);
        objects.afterPropertiesSet();
        store = new SupplyApiRedisStore(strings, objects);
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            strings.delete("supply:api:concurrency:" + scope);
            client.shutdown();
        }
    }

    @Test
    void limitIsSharedAndReleasedSlotIsReusable() {
        var first = store.acquireConcurrency(scope, 2, 60_000);
        var second = store.acquireConcurrency(scope, 2, 60_000);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(store.acquireConcurrency(scope, 2, 60_000)).isNull();

        store.releaseConcurrency(first);
        var third = store.acquireConcurrency(scope, 2, 60_000);
        assertThat(third).isNotNull();
        assertThat(store.acquireConcurrency(scope, 2, 60_000)).isNull();
        // 重复释放同一租约不会多放出名额
        store.releaseConcurrency(first);
        assertThat(store.acquireConcurrency(scope, 2, 60_000)).isNull();
    }

    @Test
    void leaseOfCrashedHolderExpiresAndFreesTheSlot() throws Exception {
        assertThat(store.acquireConcurrency(scope, 1, 300)).isNotNull();
        assertThat(store.acquireConcurrency(scope, 1, 300)).isNull();
        // 持有者未释放（模拟进程崩溃）：租约到期后名额自动回收
        Thread.sleep(450);
        assertThat(store.acquireConcurrency(scope, 1, 60_000)).isNotNull();
    }
}
