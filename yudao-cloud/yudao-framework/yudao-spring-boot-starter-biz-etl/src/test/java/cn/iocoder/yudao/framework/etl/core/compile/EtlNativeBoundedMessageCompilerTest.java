package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeBoundedMessageCompilerTest {
    private static final List<Map<String,Object>> COLUMNS = List.of(Map.of("name", "id", "type", "Integer"), Map.of("name", "label", "type", "String"));
    private static final Set<String> SECRETS = Set.of("test-secret");
    @Test void sparkKafkaBuildsIndependentBoundedProviderAndNeverBorrowsFlinkInstallation() {
        var binding = new EtlNativeConnectorCompiler.KafkaBinding("managed:9092", Set.of("in"), Set.of("out"), EtlNativeConnectorCompiler.Authentication.NONE, false, true);
        var input = EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.SPARK, false, "task:source", "read-kafka", Map.of("topic", "in", "columns", COLUMNS), binding);
        assertTrue(input.initializationSql().contains("EtlBoundedMessageProvider")); assertFalse(input.table().continuousSource());
        assertEquals("latest-offset", plan(input).getJSONObject("options").getStr("scan.bounded.mode")); assertEquals(1_000_000, plan(input).getInt("maxRows"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.SPARK, true, "x", "read-kafka", Map.of("topic", "in", "columns", COLUMNS), binding));
        var flinkOnly = new EtlNativeConnectorCompiler.KafkaBinding("managed:9092", Set.of("in"), Set.of(), EtlNativeConnectorCompiler.Authentication.NONE, true);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.SPARK, false, "x", "read-kafka", Map.of("topic", "in", "columns", COLUMNS), flinkOnly));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeConnectorCompiler.kafka(EtlNativeSqlCompiler.Engine.SPARK, false, "x", "write-kafka", Map.of("topic", "out", "columns", COLUMNS, "configParameters", Map.of("sink.delivery-guarantee", "exactly-once")), binding));
    }
    @Test void rabbitAndRocketHaveRealBatchPlansWithSeparateInstallFlagsAndOriginalMessageSchema() {
        var rabbit = new EtlNativeRabbitCompiler.Binding("managed", 5672, "/", Set.of("in"), Set.of("out"), "test-secret", false, true, true, true);
        var rocket = new EtlNativeRocketCompiler.Binding("managed:9876", Set.of("in"), Set.of("out"), Set.of("read"), Set.of("write"), "test-secret", false, true, true, true);
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var source = EtlNativeRabbitCompiler.compile(engine, false, "a", "read-rabbitmq", Map.of("topic", "in", "columns", COLUMNS), rabbit, SECRETS);
            var sink = EtlNativeRocketCompiler.compile(engine, false, "b", "write-rocketmq", Map.of("topic", "out", "groupId", "write", "columns", COLUMNS), rocket, SECRETS);
            assertFalse(source.table().continuousSource()); assertFalse(sink.table().continuousSource());
            assertEquals("test-secret", plan(source).getStr("credentialRef")); assertEquals("write", plan(sink).getStr("group"));
            assertEquals("INT", plan(source).getJSONObject("resultTypes").getJSONObject("id").getStr("sqlType"));
            assertTrue(source.initializationSql().contains(engine == EtlNativeSqlCompiler.Engine.FLINK ? "datahub-bounded" : "EtlBoundedMessageProvider"));
        }
        var realtimeOnly = new EtlNativeRabbitCompiler.Binding("managed", 5672, "/", Set.of("in"), Set.of(), "test-secret", false, true);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, false, "x", "read-rabbitmq", Map.of("topic", "in", "columns", COLUMNS), realtimeOnly, SECRETS));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "x", "read-rabbitmq", Map.of("topic", "in", "columns", COLUMNS, "config", Map.of("prefetchCount", 2)), rabbit, SECRETS));
    }
    @Test void tdengineBatchUsesOnlyBoundJdbcTableProjectionAndKeepsTimestampPrecision() {
        var binding = new EtlNativeTdengineCompiler.Binding("managed:6041", Set.of("tmq"), Set.of("g"), "test-secret", new LinkedHashMap<>(Map.of("ts", "TIMESTAMP(3)", "reading", "DOUBLE")), 1000, false, true,
                "jdbc:TAOS-WS://managed:6041/sensors", "sensors", "values", true, true);
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var source = EtlNativeTdengineCompiler.compile(engine, false, "td", Map.of("tableName", "values", "columns", List.of(Map.of("name", "ts", "type", "Date"))), binding, SECRETS);
            var plan = plan(source); assertEquals("SELECT `ts` FROM `sensors`.`values`", plan.getStr("query")); assertFalse(source.table().continuousSource());
            assertFalse(source.initializationSql().contains("tmq")); assertEquals(engine == EtlNativeSqlCompiler.Engine.FLINK ? "TIMESTAMP(3)" : "TIMESTAMP", plan.getJSONObject("resultTypes").getJSONObject("ts").getStr("sqlType"));
            assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.compile(engine, false, "td", Map.of("tableName", "values", "topic", "tmq"), binding, SECRETS));
            assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.compile(engine, false, "td", Map.of("tableName", "foreign"), binding, SECRETS));
        }
    }
    private static JSONObject plan(EtlNativeConnectorCompiler.TablePlan plan) {
        var matcher = Pattern.compile("'[A-Za-z0-9+/=]{60,}'").matcher(plan.initializationSql()); assertTrue(matcher.find());
        String value = matcher.group(); return JSONUtil.parseObj(new String(Base64.getDecoder().decode(value.substring(1, value.length()-1)), StandardCharsets.UTF_8));
    }
}
