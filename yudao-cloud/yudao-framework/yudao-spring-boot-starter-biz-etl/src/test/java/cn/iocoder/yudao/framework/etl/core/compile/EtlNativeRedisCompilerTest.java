package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeRedisCompilerTest {
    private static final EtlNativeRedisCompiler.Binding BINDING = new EtlNativeRedisCompiler.Binding("redis-native", 6379, 0, false,
            "redisAccess", Set.of("input:string:*", "input:hash:*", "input:list:*"), Set.of("result:"), true);
    @Test void threeModelsMapReadSelectorsAndWriteBatchTemplateWithoutInlineSecrets() throws Exception {
        var directory = Path.of("target/native-redis-sql"); Files.createDirectories(directory);
        for (String model : List.of("string", "hash", "list")) {
            var source = compile(false, config(model, false)); var target = compile(true, config(model, true));
            assertEquals("BIGINT", source.table().columns().get("id")); assertEquals("DECIMAL(24,4)", source.table().columns().get("amount"));
            assertFalse(source.table().continuousSource()); assertTrue(source.initializationSql().contains("LATERAL VIEW DH_READ_REDIS"));
            assertTrue(target.initializationSql().contains("EtlRedisProvider")); assertFalse(target.initializationSql().contains("password"));
            String sql = source.initializationSql() + target.initializationSql() + "INSERT INTO `" + target.table().physicalTable() + "` SELECT * FROM `" + source.table().physicalTable() + "`;\n";
            Files.writeString(directory.resolve(model + ".sql"), sql);
        }
    }
    @Test void unsupportedModesOverridesUnapprovedKeysAndUnregisteredFunctionsFailClosed() {
        for (var change : List.of(Map.<String, Object>of("keyPattern", "foreign:*"), Map.<String, Object>of("password", "no-inline"),
                Map.<String, Object>of("readMode", "INCREMENT"), Map.<String, Object>of("maxKeys", 0), Map.<String, Object>of("config", "ignored"))) {
            var config = new LinkedHashMap<>(config("hash", false)); config.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> compile(false, config));
        }
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRedisCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, true, "n", "read-redis", config("hash", false), BINDING, Set.of("DH_READ_REDIS"), Set.of("redisAccess")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRedisCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-redis", config("hash", false), BINDING, Set.of(), Set.of("redisAccess")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRedisCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-redis", config("hash", false), BINDING, Set.of("DH_READ_REDIS"), Set.of()));
    }
    @Test void writeKeyTemplatesAndSourceSelectorsCannotSilentlyChangeMeaning() {
        for (String pattern : List.of("foreign:${id}", "result:${missing}", "result:*", "result:${id", "${id}")) {
            var config = new LinkedHashMap<>(config("hash", true)); config.put("keyPattern", pattern);
            assertThrows(IllegalArgumentException.class, () -> compile(true, config));
        }
        var config = new LinkedHashMap<>(config("hash", false)); config.put("columns", List.of(Map.of("name", "id", "redisField", "@index", "type", "Long")));
        assertThrows(IllegalArgumentException.class, () -> compile(false, config));
    }
    private static EtlNativeConnectorCompiler.TablePlan compile(boolean output, Map<String, Object> config) {
        return EtlNativeRedisCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, (output ? "write" : "read") + config.get("dataType"),
                output ? "write-redis" : "read-redis", config, BINDING, Set.of("DH_READ_REDIS"), Set.of("redisAccess"));
    }
    private static Map<String, Object> config(String model, boolean output) {
        var config = new LinkedHashMap<String, Object>(); config.put("databaseId", 1); config.put("dataType", model);
        config.put("keyPattern", output ? model.equals("list") ? "result:list" : "result:" + model + ":${id}" : "input:" + model + ":*");
        config.put("columns", List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "amount", "type", "BigNumber", "length", 24, "precision", 4),
                Map.of("name", "day", "type", "Date"), Map.of("name", "label", "type", "String"), Map.of("name", "empty", "type", "String"), Map.of("name", "optional", "type", "String")));
        if (output) config.put("batchSize", 2);
        return config;
    }
}
