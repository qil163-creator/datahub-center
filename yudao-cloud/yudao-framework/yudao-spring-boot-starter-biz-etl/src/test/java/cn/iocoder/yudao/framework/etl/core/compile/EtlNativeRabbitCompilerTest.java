package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeRabbitCompilerTest {
    private static final EtlNativeRabbitCompiler.Binding BINDING = new EtlNativeRabbitCompiler.Binding("rabbit-native", 5672, "/components",
            Set.of("components.in"), Set.of("components.out"), "rabbit.fixture", false, true);
    private static Map<String, Object> config(boolean output) {
        return new LinkedHashMap<>(Map.of("databaseId", output ? 2 : 1, "topic", output ? "components.out" : "components.in", "config", "{}",
                "columns", List.of(Map.of("name", "id", "type", "LONG"), Map.of("name", "name", "type", "STRING"))));
    }
    @Test void createsSecretFreeSourceAndConfirmedSinkAndRealNativeSql() throws Exception {
        var source = EtlNativeRabbitCompiler.compile(FLINK, true, "rabbit:read", "read-rabbitmq", config(false), BINDING, Set.of("rabbit.fixture"));
        var target = EtlNativeRabbitCompiler.compile(FLINK, true, "rabbit:write", "write-rabbitmq", config(true), BINDING, Set.of("rabbit.fixture"));
        assertTrue(source.table().continuousSource()); assertFalse(target.table().continuousSource());
        assertTrue(source.initializationSql().contains("'credential-ref'='rabbit.fixture'"));
        assertTrue(source.initializationSql().contains("'json.fail-on-missing-field'='true'"));
        assertTrue(target.initializationSql().contains("'confirm.timeout.ms'='10000'"));
        assertFalse(source.initializationSql().contains("password")); assertFalse(target.initializationSql().contains("username"));
        var read = node("read", "read-db", 1, source.table().physicalTable()); var write = node("write", "insert-db", 2, target.table().physicalTable());
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = source.initializationSql() + target.initializationSql() + EtlNativeSqlCompiler.compile(FLINK, true,
                List.of(read, write), List.of(edge), (database, name, schema) -> database == 1 ? source.table() : target.table());
        assertTrue(sql.contains("INSERT INTO"));
        Path file = Path.of("target/native-rabbit-sql/rabbit.sql"); Files.createDirectories(file.getParent()); Files.writeString(file, sql);
    }
    @Test void rejectsWrongModeUninstalledOrUnauthorizedQueuesAndSecrets() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(SPARK, true, "n", "read-rabbitmq", config(false), BINDING, Set.of("rabbit.fixture")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(FLINK, false, "n", "read-rabbitmq", config(false), BINDING, Set.of("rabbit.fixture")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(FLINK, true, "n", "read-rabbitmq", config(false), BINDING, Set.of()));
        for (var patch : List.of(Map.<String,Object>of("topic", "components.out"), Map.<String,Object>of("host", "attacker"),
                Map.<String,Object>of("config", "{\"password\":\"plaintext\"}"), Map.<String,Object>of("config", "{\"prefetchCount\":0}"),
                Map.<String,Object>of("config", "{\"autoAck\":true}"), Map.<String,Object>of("batchSize", 1))) {
            var values = config(false); values.putAll(patch);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(FLINK, true, "n", "read-rabbitmq", values, BINDING, Set.of("rabbit.fixture")));
        }
    }
    @Test void unsupportedFieldRulesAndUnconfirmedBatchSemanticsCannotBeIgnored() {
        for (var field : List.of(Map.of("name", "name", "type", "STRING", "key", "$.nested.name"),
                Map.of("name", "name", "type", "STRING", "format", "custom"), Map.of("name", "name", "type", "STRING", "length", 20))) {
            var values = config(false); values.put("columns", List.of(field));
            assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(FLINK, true, "n", "read-rabbitmq", values, BINDING, Set.of("rabbit.fixture")));
        }
        var write = config(true); write.put("batchSize", 1000);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(FLINK, true, "n", "write-rabbitmq", write, BINDING, Set.of("rabbit.fixture")));
        write.put("batchSize", 1);
        assertDoesNotThrow(() -> EtlNativeRabbitCompiler.compile(FLINK, true, "n", "write-rabbitmq", write, BINDING, Set.of("rabbit.fixture")));
    }
    @Test void consumesActualUiMappingsAndRenamesOnlyTheSelectedOutputFields() throws Exception {
        var input = config(false); var output = config(true);
        output.put("columns", List.of(Map.of("name", "id", "key", "id", "type", "LONG"), Map.of("name", "标签", "key", "name", "type", "STRING")));
        var mappings = List.of(Map.<String,Object>of("source", "id", "target", "id", "enabled", true), Map.<String,Object>of("source", "name", "target", "标签", "enabled", true));
        output.put("fieldMappings", mappings);
        var source = EtlNativeRabbitCompiler.compile(FLINK, true, "rabbit:read", "read-rabbitmq", input, BINDING, Set.of("rabbit.fixture"));
        var target = EtlNativeRabbitCompiler.compile(FLINK, true, "rabbit:write", "write-rabbitmq", output, BINDING, Set.of("rabbit.fixture"));
        assertEquals(mappings, target.fieldMappings());
        var read = node("read", "read-db", 1, source.table().physicalTable()); var write = node("write", "insert-db", 2, target.table().physicalTable());
        var writeConfig = JSONUtil.parseObj(write.getNodeJson()).set("fieldMappings", target.fieldMappings()); write.setNodeJson(writeConfig.toString());
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = source.initializationSql() + target.initializationSql() + EtlNativeSqlCompiler.compile(FLINK, true, List.of(read, write), List.of(edge), (database, name, schema) -> database == 1 ? source.table() : target.table());
        assertTrue(sql.contains("(`id`, `标签`)")); assertTrue(sql.contains("SELECT `id`, `name` FROM"));
        Path file = Path.of("target/native-rabbit-sql/rabbit-mapped.sql"); Files.createDirectories(file.getParent()); Files.writeString(file, sql);
        output.put("fieldMappings", List.of(Map.of("source", "wrong", "target", "id", "enabled", true)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRabbitCompiler.compile(FLINK, true, "n", "write-rabbitmq", output, BINDING, Set.of("rabbit.fixture")));
    }
    @Test void consumesUnstrippedConfigsGeneratedByTheActualFrontendDefaultsAndColumnEditor() throws Exception {
        var fixtures = EtlCompilerUiFixtures.configs();
        var read = EtlNativeRabbitCompiler.compile(FLINK, true, "ui:read", "read-rabbitmq", fixtures.getJSONObject("read-rabbitmq"), BINDING, Set.of("rabbit.fixture"));
        var write = EtlNativeRabbitCompiler.compile(FLINK, true, "ui:write", "write-rabbitmq", fixtures.getJSONObject("write-rabbitmq"), BINDING, Set.of("rabbit.fixture"));
        assertTrue(read.table().continuousSource());
        assertEquals("STRING", write.table().columns().get("标签"));
        assertEquals(List.of(Map.<String,Object>of("source", "id", "target", "id", "enabled", true), Map.<String,Object>of("source", "name", "target", "标签", "enabled", true)), write.fieldMappings());
    }
    private static EtlNodeDef node(String no, String code, int database, String table) {
        var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode(code);
        node.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId", database, "tableName", table, "writeMode", "INSERT", "dataSyncType", 1))); return node;
    }
}
