package cn.iocoder.yudao.module.supply.service.dataapi;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对外数据 API 使用的 Redis 共享状态。
 *
 * 所有 key 按应用与业务对象隔离；防重放、限流和熔断复用 Redis 原子操作。
 */
@Component
@RequiredArgsConstructor
public class SupplyApiRedisStore {

    private static final String PREFIX = "supply:api:";

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('incr', KEYS[1])
            if current == 1 then
                redis.call('pexpire', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 预留随机数。
     *
     * @param appKey 应用标识
     * @param nonce 随机数
     * @param ttlMillis 有效期毫秒数
     * @return 处理是否成功
     */
    public boolean reserveNonce(String appKey, String nonce, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("nonce ttl must be positive");
        }
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("nonce app must be present");
        }
        if (nonce == null || nonce.isBlank() || nonce.length() > 64) {
            throw new IllegalArgumentException("nonce length out of bounds");
        }
        try {
            Boolean created = stringRedisTemplate.opsForValue().setIfAbsent(
                    key("nonce", appKey, nonce), "1", ttlMillis, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(created);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("nonce store unavailable", ex);
        }
    }

    /** 固定窗口计数器；Redis INCR 可保证网关节点间的原子性。 */
    public long incrementRate(String appKey, Long apiId, long windowMillis) {
        String key = key("rate", appKey, String.valueOf(apiId),
                String.valueOf(System.currentTimeMillis() / Math.max(windowMillis, 1L)));
        Long count = stringRedisTemplate.execute(INCREMENT_WITH_TTL_SCRIPT, List.of(key),
                String.valueOf(Math.max(windowMillis, 1L)));
        return count == null ? 0L : count;
    }

    /**
     * 判断尝试获取频率条件是否成立。
     *
     * @param appKey 应用标识
     * @param apiId API 编号
     * @param windowMillis 窗口毫秒数
     * @param limit 读取或处理上限
     * @return 处理是否成功
     */
    public boolean tryAcquireRate(String appKey, Long apiId,
            long windowMillis, long limit) {
        if (limit <= 0) {
            return false;
        }
        return incrementRate(appKey, apiId, windowMillis) <= limit;
    }

    /**
     * 判断是否满足熔断打开条件。
     *
     * @param appKey 应用标识
     * @param apiId API 编号
     * @return 判断结果
     */
    public boolean isCircuitOpen(String appKey, Long apiId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key("breaker-open", appKey, String.valueOf(apiId))));
    }

    /**
     * 记录一次失败，达到阈值后打开熔断；计数器在配置的观察窗口后过期。
     */
    public long recordBreakerError(String appKey, Long apiId,
            long windowMillis, int threshold, long openMillis) {
        String countKey = key("breaker-count", appKey, String.valueOf(apiId));
        Long count = stringRedisTemplate.execute(INCREMENT_WITH_TTL_SCRIPT, List.of(countKey),
                String.valueOf(Math.max(windowMillis, 1L)));
        long actual = count == null ? 0L : count;
        if (actual >= threshold) {
            String openKey = key("breaker-open", appKey, String.valueOf(apiId));
            stringRedisTemplate.opsForValue().set(openKey, "1", Math.max(openMillis, 1L),
                    TimeUnit.MILLISECONDS);
            stringRedisTemplate.delete(countKey);
        }
        return actual;
    }

    /**
     * 读取结果。
     *
     * @param cacheKey 缓存键
     * @return 供应APIRedis
     */
    public Object getResult(String cacheKey) {
        return redisTemplate.opsForValue().get(cacheKey(cacheKey));
    }

    /**
     * 写入结果。
     *
     * @param cacheKey 缓存键
     * @param value 待处理的值
     * @param ttlSeconds 有效期秒数
     */
    public void putResult(String cacheKey, Object value, long ttlSeconds) {
        redisTemplate.opsForValue().set(cacheKey(cacheKey), value,
                Math.max(ttlSeconds, 1L), TimeUnit.SECONDS);
    }

    /**
     * 删除结果。
     *
     * @param cacheKey 缓存键
     */
    public void deleteResult(String cacheKey) {
        redisTemplate.delete(cacheKey(cacheKey));
    }

    /**
     * 获取供应APIRedis的结果键。
     *
     * @param appKey 应用标识
     * @param cacheKey 缓存键
     * @return 供应APIRedis的结果键文本
     */
    public static String resultKey(String appKey, String cacheKey) {
        return key("result", appKey, cacheKey);
    }

    /**
     * 获取供应APIRedis的缓存键。
     *
     * @param key 标识键
     * @return 供应APIRedis的缓存键文本
     */
    private static String cacheKey(String key) {
        return key.startsWith(PREFIX) ? key : PREFIX + key;
    }

    /**
     * 构造供应APIRedis标识键。
     *
     * @param kind 种类
     * @param parts 分段
     * @return 构造供应APIRedis标识键文本
     */
    private static String key(String kind, String... parts) {
        StringBuilder result = new StringBuilder(PREFIX).append(kind);
        for (String part : parts) {
            result.append(':').append(safe(part));
        }
        return result.toString();
    }

    /**
     * 获取供应APIRedis的安全。
     *
     * @param value 待处理的值
     * @return 供应APIRedis的安全文本
     */
    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(':', '_');
    }
}
