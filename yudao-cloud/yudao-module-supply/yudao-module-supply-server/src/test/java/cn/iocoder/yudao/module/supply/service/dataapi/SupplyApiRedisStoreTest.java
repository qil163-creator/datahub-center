package cn.iocoder.yudao.module.supply.service.dataapi;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SupplyApiRedisStoreTest {

    @Test
    void nonceShouldUseAtomicSetIfAbsentWithAppScope() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(Boolean.TRUE);

        SupplyApiRedisStore store = new SupplyApiRedisStore(stringRedisTemplate, redisTemplate);

        assertThat(store.reserveNonce( "app-a", "nonce-1", 60_000L)).isTrue();
        verify(values).setIfAbsent("supply:api:nonce:app-a:nonce-1", "1",
                60_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void rateAndBreakerCountersShouldBeSharedByRedisKeys() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(2L);

        SupplyApiRedisStore store = new SupplyApiRedisStore(stringRedisTemplate, redisTemplate);

        assertThat(store.tryAcquireRate( "app-a", 7L, 60_000L, 2L)).isTrue();
        assertThat(store.incrementRate( "app-a", 7L, 60_000L)).isEqualTo(2L);
        verify(stringRedisTemplate, atLeastOnce()).execute(any(RedisScript.class),
                argThat((List<String> keys) -> keys.size() == 1
                        && keys.get(0).startsWith("supply:api:rate:app-a:7:")),
                eq("60000"));
    }

    @Test
    void nonceMustFailClosedWhenRedisUnavailable() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("redis down"));
        SupplyApiRedisStore store = new SupplyApiRedisStore(stringRedisTemplate, redisTemplate);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> store.reserveNonce( "app-a", "nonce-1", 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void nonceRejectsBlankOrOverlongValue() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        SupplyApiRedisStore store = new SupplyApiRedisStore(stringRedisTemplate, redisTemplate);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> store.reserveNonce("a", "x".repeat(65), 1000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
