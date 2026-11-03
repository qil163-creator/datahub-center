package cn.iocoder.yudao.framework.redis.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class YudaoRedisSerializerTest {

    @Test
    void roundTripsJackson3CacheValuesWithoutLosingLongDecimalOrTime() {
        var serializer = assertInstanceOf(GenericJacksonJsonRedisSerializer.class,
                YudaoRedisAutoConfiguration.buildRedisSerializer());
        var original = new CacheValue(Long.MAX_VALUE,
                new BigDecimal("1234567890.123456789012345678901"),
                LocalDateTime.of(2026, 10, 10, 14, 0, 1, 123456789), List.of("redis", "cache"));

        byte[] bytes = serializer.serialize(original);

        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .contains("9223372036854775807", "1234567890.123456789012345678901");
        assertThat(serializer.deserialize(bytes)).isEqualTo(original);
        assertThat(serializer.deserialize(bytes, CacheValue.class)).isEqualTo(original);
    }

    @Test
    void roundTripsNullCacheValues() {
        var serializer = YudaoRedisAutoConfiguration.buildRedisSerializer();
        assertThat(serializer.deserialize(serializer.serialize(null))).isNull();
    }

    public record CacheValue(Long id, BigDecimal amount, LocalDateTime occurredAt, List<String> tags) {
    }

}
