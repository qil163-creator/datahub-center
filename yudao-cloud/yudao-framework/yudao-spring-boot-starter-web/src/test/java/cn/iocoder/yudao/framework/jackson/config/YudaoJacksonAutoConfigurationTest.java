package cn.iocoder.yudao.framework.jackson.config;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class YudaoJacksonAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    YudaoJacksonAutoConfiguration.class));

    @Test
    void registersJackson3MapperAndPreservesWireTypes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(JsonUtils.class);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            LocalDateTime timestamp = LocalDateTime.of(2026, 10, 9, 12, 34, 56);
            WirePayload payload = new WirePayload(42L, Long.MAX_VALUE, Long.MIN_VALUE,
                    timestamp, timestamp, new BigDecimal("12345678901234567890.12345678901234567890"),
                    timestamp.toLocalDate(), timestamp.toLocalTime());

            byte[] bytes = mapper.writeValueAsBytes(payload);
            var json = mapper.readTree(bytes);
            assertThat(json.get("safeId").isNumber()).isTrue();
            assertThat(json.get("safeId").longValue()).isEqualTo(42L);
            assertThat(json.get("largeId").isString()).isTrue();
            assertThat(json.get("largeId").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
            assertThat(json.get("negativeId").isString()).isTrue();
            assertThat(json.get("negativeId").asString()).isEqualTo(Long.toString(Long.MIN_VALUE));
            assertThat(json.get("timestamp").isNumber()).isTrue();
            assertThat(json.get("timestamp").longValue())
                    .isEqualTo(timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            assertThat(json.get("formatted").asString()).isEqualTo("2026-10-09 12:34:56");
            assertThat(json.get("amount").isString()).isTrue();
            assertThat(json.get("amount").asString())
                    .isEqualTo("12345678901234567890.12345678901234567890");
            assertThat(json.get("date").asString()).isEqualTo("2026-10-09");
            assertThat(json.get("time").asString()).isEqualTo("12:34:56");
            assertThat(JsonUtils.toJsonString(payload)).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
        });
    }

    @Test
    void readsEpochMillisecondsBackIntoLocalDateTime() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            LocalDateTime expected = LocalDateTime.of(2026, 10, 9, 12, 34, 56);
            long epochMillis = expected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            TimestampPayload payload = mapper.readValue("{\"timestamp\":" + epochMillis + "}",
                    TimestampPayload.class);
            assertThat(payload.timestamp()).isEqualTo(expected);
        });
    }

    private record WirePayload(Long safeId, Long largeId, long negativeId, LocalDateTime timestamp,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime formatted,
                               @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                               LocalDate date, LocalTime time) {
    }

    private record TimestampPayload(LocalDateTime timestamp) {
    }

}
