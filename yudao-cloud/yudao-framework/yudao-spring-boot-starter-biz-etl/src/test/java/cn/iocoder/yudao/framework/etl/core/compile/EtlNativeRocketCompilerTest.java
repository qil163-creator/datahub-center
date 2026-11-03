package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeRocketCompilerTest {
    private static final EtlNativeRocketCompiler.Binding BINDING = new EtlNativeRocketCompiler.Binding("rocket-native-namesrv:9876", Set.of("components_input"), Set.of("components_output"),
            Set.of("components_read"), Set.of("components_write"), "rocket.fixture", false, true);
    private static Map<String, Object> config(boolean output) {
        return new LinkedHashMap<>(Map.of("databaseId", output ? 2 : 1, "topic", output ? "components_output" : "components_input", "groupId", output ? "components_write" : "components_read", "config", "{}",
                "columns", List.of(Map.of("name", "id", "type", "LONG"), Map.of("name", "name", "type", "STRING"))));
    }
    @Test void emitsRealNativeContinuousSqlWithoutInlineIdentityOrCredentials() throws Exception {
        var read = EtlNativeRocketCompiler.compile(FLINK, true, "rocket:read", "read-rocketmq", config(false), BINDING, Set.of("rocket.fixture"));
        var write = EtlNativeRocketCompiler.compile(FLINK, true, "rocket:write", "write-rocketmq", config(true), BINDING, Set.of("rocket.fixture"));
        assertTrue(read.table().continuousSource()); assertFalse(write.table().continuousSource());
        String initialization = read.initializationSql() + write.initializationSql();
        assertTrue(initialization.contains("'connector'='datahub-rocketmq'")); assertTrue(initialization.contains("'group.id'='components_read'"));
        assertFalse(initialization.contains("secretKey")); assertFalse(initialization.contains("accessKey"));
        var source = node("read", "read-db", 1, read.table().physicalTable()); var sink = node("write", "insert-db", 2, write.table().physicalTable());
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = initialization + EtlNativeSqlCompiler.compile(FLINK, true, List.of(source, sink), List.of(edge), (id, table, namespace) -> id == 1 ? read.table() : write.table());
        Path file = Path.of("target/native-rocket-sql/rocket.sql"); Files.createDirectories(file.getParent()); Files.writeString(file, sql);
    }
    @Test void engineModeTopicDirectionGroupAndSecretReferencesMustBeExplicitlyAuthorized() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(SPARK, true, "n", "read-rocketmq", config(false), BINDING, Set.of("rocket.fixture")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(FLINK, false, "n", "read-rocketmq", config(false), BINDING, Set.of("rocket.fixture")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(FLINK, true, "n", "read-rocketmq", config(false), BINDING, Set.of()));
        for (var patch : List.of(Map.<String,Object>of("topic", "components_output"), Map.<String,Object>of("groupId", "components_write"), Map.<String,Object>of("nameservers", "attacker:9876"),
                Map.<String,Object>of("config", "{\"secretKey\":\"plaintext\"}"), Map.<String,Object>of("offsetReset", "unknown"), Map.<String,Object>of("config", "{\"pullTimeoutMillis\":0}"))) {
            var input = config(false); input.putAll(patch);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(FLINK, true, "n", "read-rocketmq", input, BINDING, Set.of("rocket.fixture")));
        }
    }
    @Test void fieldsAndOutputParametersCannotBeSilentlyIgnored() {
        var output = config(true); output.put("batchSize", 1000);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(FLINK, true, "n", "write-rocketmq", output, BINDING, Set.of("rocket.fixture")));
        output.put("batchSize", 1); assertDoesNotThrow(() -> EtlNativeRocketCompiler.compile(FLINK, true, "n", "write-rocketmq", output, BINDING, Set.of("rocket.fixture")));
        output.put("offsetReset", "earliest");
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(FLINK, true, "n", "write-rocketmq", output, BINDING, Set.of("rocket.fixture")));
        for (var field : List.of(Map.of("name", "id", "type", "LONG", "key", "$.nested.id"), Map.of("name", "id", "type", "LONG", "length", 20), Map.of("name", "day", "type", "DATE", "format", "custom"))) {
            var input = config(false); input.put("columns", List.of(field));
            assertThrows(IllegalArgumentException.class, () -> EtlNativeRocketCompiler.compile(FLINK, true, "n", "read-rocketmq", input, BINDING, Set.of("rocket.fixture")));
        }
    }
    @Test void uiFieldMappingsRenameUnicodeKeysWithoutDroppingTheSourceProjection() throws Exception {
        var output = config(true); output.put("columns", List.of(Map.of("name", "id", "type", "LONG", "key", "id"), Map.of("name", "标签", "type", "STRING", "key", "name")));
        var mappings = List.of(Map.<String,Object>of("source", "id", "target", "id", "enabled", true), Map.<String,Object>of("source", "name", "target", "标签", "enabled", true)); output.put("fieldMappings", mappings);
        var source = EtlNativeRocketCompiler.compile(FLINK, true, "rocket:read", "read-rocketmq", config(false), BINDING, Set.of("rocket.fixture"));
        var target = EtlNativeRocketCompiler.compile(FLINK, true, "rocket:write", "write-rocketmq", output, BINDING, Set.of("rocket.fixture"));
        assertEquals(mappings, target.fieldMappings());
        var read = node("read", "read-db", 1, source.table().physicalTable()); var write = node("write", "insert-db", 2, target.table().physicalTable());
        write.setNodeJson(JSONUtil.parseObj(write.getNodeJson()).set("fieldMappings", target.fieldMappings()).toString());
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = source.initializationSql() + target.initializationSql() + EtlNativeSqlCompiler.compile(FLINK, true, List.of(read, write), List.of(edge), (database, name, namespace) -> database == 1 ? source.table() : target.table());
        assertTrue(sql.contains("(`id`, `标签`)")); assertTrue(sql.contains("SELECT `id`, `name` FROM"));
        Path file = Path.of("target/native-rocket-sql/rocket-mapped.sql"); Files.createDirectories(file.getParent()); Files.writeString(file, sql);
    }
    @Test void consumesUnstrippedConfigsGeneratedByTheActualFrontendDefaultsAndColumnEditor() throws Exception {
        var fixtures = EtlCompilerUiFixtures.configs();
        var read = EtlNativeRocketCompiler.compile(FLINK, true, "ui:read", "read-rocketmq", fixtures.getJSONObject("read-rocketmq"), BINDING, Set.of("rocket.fixture"));
        var write = EtlNativeRocketCompiler.compile(FLINK, true, "ui:write", "write-rocketmq", fixtures.getJSONObject("write-rocketmq"), BINDING, Set.of("rocket.fixture"));
        assertTrue(read.table().continuousSource());
        assertEquals("STRING", write.table().columns().get("标签"));
        assertEquals(List.of(Map.<String,Object>of("source", "id", "target", "id", "enabled", true), Map.<String,Object>of("source", "name", "target", "标签", "enabled", true)), write.fieldMappings());
    }
    private static EtlNodeDef node(String no, String code, int id, String table) {
        var result = new EtlNodeDef(); result.setNo(no); result.setName(no); result.setToolCode(code);
        result.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId", id, "tableName", table, "writeMode", "INSERT", "dataSyncType", 1))); return result;
    }
}
