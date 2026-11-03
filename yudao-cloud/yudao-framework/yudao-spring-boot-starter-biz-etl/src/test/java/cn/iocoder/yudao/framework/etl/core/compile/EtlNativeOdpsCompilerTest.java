package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeOdpsCompilerTest {
    private static LinkedHashMap<String, String> columns() {
        var columns = new LinkedHashMap<String, String>(); columns.put("id", "BIGINT"); columns.put("amount", "DECIMAL(24,4)");
        columns.put("day", "DATE"); columns.put("label", "STRING"); columns.put("empty", "STRING"); columns.put("optional", "STRING"); columns.put("reading", "FLOAT"); return columns;
    }
    private static EtlNativeOdpsCompiler.Binding binding() {
        return new EtlNativeOdpsCompiler.Binding("http://odps-wire:18088/api", "http://odps-wire:18088", "fixture", "odpsAccess",
                List.of(new EtlNativeOdpsCompiler.Resource("source", "ds='20261102'", columns(), true, false, false),
                        new EtlNativeOdpsCompiler.Resource("target", "ds='20261102'", columns(), true, true, false)), true, true);
    }
    private static Map<String, Object> config(boolean output) {
        var config = new LinkedHashMap<String, Object>(); config.put("databaseId", 1); config.put("tableName", output ? "target" : "source");
        config.put("partition", "ds='20261102'"); config.put("columns", new ArrayList<>(columns().keySet()));
        if (output) config.put("batchSize", 2); return config;
    }
    private static EtlNativeConnectorCompiler.TablePlan compile(boolean output, Map<String, Object> config) {
        return EtlNativeOdpsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, output ? "target" : "source", output ? "write-odps" : "read-odps",
                config, binding(), Set.of("DH_READ_ODPS"), Set.of("odpsAccess"));
    }
    @Test void tableAndPartitionBoundPlansConsumeRealSchemaBatchAndNoInlineKeys() throws Exception {
        var source = compile(false, config(false)); var target = compile(true, config(true));
        assertEquals(columns().keySet(), source.table().columns().keySet()); assertEquals("DECIMAL(24,4)", source.table().columns().get("amount"));
        assertEquals("DOUBLE", source.table().columns().get("reading"));
        assertTrue(source.initializationSql().contains("LATERAL VIEW DH_READ_ODPS")); assertTrue(target.initializationSql().contains("EtlOdpsProvider"));
        assertFalse(source.table().continuousSource());
        Path directory = Path.of("target/native-odps-sql"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("copy.sql"), source.initializationSql() + target.initializationSql() + "INSERT INTO `" + target.table().physicalTable() + "` SELECT * FROM `" + source.table().physicalTable() + "`;\n");
        var targetRead = new LinkedHashMap<>(config(true)); targetRead.remove("batchSize"); var readTarget = compile(false, targetRead);
        Files.writeString(directory.resolve("read-target.sql"), readTarget.initializationSql() + "SELECT * FROM `" + readTarget.table().physicalTable() + "`;\n");
    }
    @Test void wrongPartitionInlineCredentialsIgnoredOptionsAndOverwriteAreRejected() {
        for (var patch : List.of(Map.<String, Object>of("partition", "ds='other'"), Map.<String, Object>of("tableName", "other"),
                Map.<String, Object>of("password", "private-fixture"), Map.<String, Object>of("ifSql", 1), Map.<String, Object>of("readMode", "INCREMENT"),
                Map.<String, Object>of("preSql", "delete from target"))) {
            var config = new LinkedHashMap<>(config(false)); config.putAll(patch);
            var failure = assertThrows(IllegalArgumentException.class, () -> compile(false, config)); assertFalse(failure.toString().contains("private-fixture"));
        }
        var overwrite = new LinkedHashMap<>(config(true)); overwrite.put("writeModeType", 1); assertThrows(IllegalArgumentException.class, () -> compile(true, overwrite));
        var incomplete = new LinkedHashMap<>(config(true)); incomplete.put("columns", List.of("id")); assertThrows(IllegalArgumentException.class, () -> compile(true, incomplete));
    }
    @Test void sourceRequiresInstalledFunctionAndTaskSecretAndTlsOutsideExplicitFixture() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOdpsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, true, "n", "read-odps", config(false), binding(), Set.of("DH_READ_ODPS"), Set.of("odpsAccess")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOdpsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-odps", config(false), binding(), Set.of(), Set.of("odpsAccess")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOdpsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-odps", config(false), binding(), Set.of("DH_READ_ODPS"), Set.of()));
        var strict = new EtlNativeOdpsCompiler.Binding(binding().serviceEndpoint(), binding().tunnelEndpoint(), binding().project(), binding().credentialRef(), binding().resources(), true, false);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOdpsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-odps", config(false), strict, Set.of("DH_READ_ODPS"), Set.of("odpsAccess")));
    }
}
