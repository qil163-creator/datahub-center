package cn.iocoder.yudao.framework.seatunnel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeSeatunnelConfigTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "json", " JSON "})
    void defaultsAndNormalizesJsonFormat(String format) {
        assertThat(NativeSeatunnelConfig.normalizeFormat(format)).isEqualTo("json");
    }

    @Test
    void normalizesHoconFormat() {
        assertThat(NativeSeatunnelConfig.normalizeFormat(" HOCON ")).isEqualTo("hocon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"yaml", "sql", "json&jobName=other"})
    void rejectsUnsupportedFormatWithoutEchoingIt(String format) {
        assertThatThrownBy(() -> NativeSeatunnelConfig.validate("{}", format))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SeaTunnel 配置格式仅支持 json 或 hocon").hasNoCause();
    }

    @Test
    void acceptsNativeJsonWithMultipleConnectorsTransformsAndOptions() {
        String source = """
                {
                  "env": {"job.mode": "STREAMING", "job.name": "user-defined", "parallelism": 3},
                  "source": [
                    {"plugin_name": "Kafka", "topic": "events", "plugin_output": "events"},
                    {"plugin_name": "FakeSource", "plugin_output": "test", "schema": {"fields": {"id": "long"}}}
                  ],
                  "transform": [{"plugin_name": "Sql", "query": "select id from test", "plugin_input": "test"}],
                  "sink": [
                    {"plugin_name": "Elasticsearch", "hosts": ["http://fixture.invalid:9200"], "password": "fixture-secret"},
                    {"plugin_name": "Console"}
                  ],
                  "custom": {"decimal": 1234567890.12345678901234567890}
                }
                """;
        assertThatCode(() -> NativeSeatunnelConfig.validate(source, "json")).doesNotThrowAnyException();
    }

    @Test
    void leavesNativeSemanticValidationToEngine() {
        assertThatCode(() -> NativeSeatunnelConfig.validate("{}", null)).doesNotThrowAnyException();
        assertThatCode(() -> NativeSeatunnelConfig.validate("env { unfinished", "hocon"))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotReadHoconIncludesOrResolveEnvironmentLocally() {
        String source = """
                include required(file("/no-local-config/fixture.conf"))
                include required(url("http://127.0.0.1:1/fixture.conf"))
                env { job.name = ${NATIVE_CONFIG_TEST_UNDEFINED} }
                source { FakeSource { row.num = 1 } }
                sink { Console {} }
                """;
        assertThatCode(() -> NativeSeatunnelConfig.validate(source, "hocon")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsEmptyConfigurationInEitherFormat(String source) {
        for (String format : new String[]{"json", "hocon"}) {
            assertThatThrownBy(() -> NativeSeatunnelConfig.validate(source, format))
                    .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "1", "\"fixture-secret\"", "{} {}", "{} trailing",
            "{\"env\":{},}", "{'env':{}}", "{env:{}}", "{\"env\":{},\"env\":{}}", "{\"secret\":fixture-secret}"})
    void rejectsInvalidJsonWithoutLeakingOriginalText(String source) {
        assertThatThrownBy(() -> NativeSeatunnelConfig.validate(source, "json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SeaTunnel JSON 配置必须是合法的 JSON 对象").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "hocon"})
    void enforcesLengthBoundaryWithoutRewritingText(String format) {
        String source = "{}" + " ".repeat(NativeSeatunnelConfig.MAX_CONFIG_LENGTH - 2);
        assertThatCode(() -> NativeSeatunnelConfig.validate(source, format)).doesNotThrowAnyException();
        assertThatThrownBy(() -> NativeSeatunnelConfig.validate(source + " ", format))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }
}
