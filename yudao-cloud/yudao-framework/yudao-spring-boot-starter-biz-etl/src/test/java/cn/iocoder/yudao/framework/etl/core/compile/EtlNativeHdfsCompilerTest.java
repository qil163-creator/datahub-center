package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeHdfsCompilerTest {
    private static final EtlDataxConnectorCompiler.Binding BINDING = new EtlDataxConnectorCompiler.Binding(
            Set.of("read-hdfs", "write-hdfs"), "/datahub_components", "hdfs://hdfs-native-namenode:8020", List.of(), null,
            EtlDataxConnectorCompiler.Authentication.NONE, Set.of("spark-hdfs"));

    @Test void declaresCompleteTypedInputAndConsumesHadoopAndCsvParameters() {
        var config = new LinkedHashMap<>(source()); config.put("hadoopConfig", "{\"dfs.client.socket-timeout\":30000}");
        var plan = compile(false, config);
        assertEquals("BIGINT", plan.table().columns().get("id")); assertEquals("DECIMAL(24,4)", plan.table().columns().get("amount"));
        assertTrue(plan.initializationSql().contains("datahub.hadoop.dfs.client.socket-timeout` '30000'"));
        assertTrue(plan.initializationSql().contains("datahub.root` 'hdfs://hdfs-native-namenode:8020/datahub_components'"));
        assertTrue(plan.initializationSql().contains("mode` 'FAILFAST'")); assertFalse(plan.table().continuousSource());
        assertTrue(plan.initializationSql().contains("nullValue` '\\\\N'"));
    }

    @Test void outputConsumesFilenamePrefixEncodingCompressionAndOnlyAllowsNewFiles() {
        var plan = compile(true, target("csv", "gzip", "UTF-8"));
        assertTrue(plan.initializationSql().contains("datahub.filename` 'measurements'"));
        assertTrue(plan.initializationSql().contains("compression` 'gzip'"));
        assertTrue(plan.initializationSql().contains("datahub.mode` 'write'"));
        for (var change : List.of(Map.<String, Object>of("writeModeType", 1), Map.<String, Object>of("writeMode", "OVERWRITE"),
                Map.<String, Object>of("fileName", "../bad"), Map.<String, Object>of("compression", "lzo"))) {
            var config = new LinkedHashMap<>(target("text", "", "UTF-8")); config.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> compile(true, config));
        }
    }

    @Test void pathConnectionOverridesCredentialsAndUnknownFieldsAreRejected() {
        for (var change : List.of(Map.<String, Object>of("path", "../outside.csv"), Map.<String, Object>of("path", "hdfs://foreign/data.csv"),
                Map.<String, Object>of("path", "/absolute.csv"), Map.<String, Object>of("path", "*.csv"),
                Map.<String, Object>of("hadoopConfig", "{\"fs.defaultFS\":\"hdfs://foreign\"}"),
                Map.<String, Object>of("nullFormat", ""), Map.<String, Object>of("nullFormat", "a,b"),
                Map.<String, Object>of("password", "forbidden"), Map.<String, Object>of("readMode", "INCREMENT"))) {
            var config = new LinkedHashMap<>(source()); config.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> compile(false, config));
        }
        var uninstalled = new EtlDataxConnectorCompiler.Binding(BINDING.connectorCodes(), BINDING.rootPath(), BINDING.defaultFs(),
                List.of(), null, BINDING.authentication(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHdfsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                "n", "read-hdfs", source(), uninstalled));
    }

    @Test void emitsCsvGzipAndTextBzipAndGbkProgramsForRealHdfsAcceptance() throws Exception {
        for (var mode : List.of(new String[]{"csv-gzip", "csv", "gzip", "UTF-8"},
                new String[]{"csv-gbk", "csv", "", "GBK"}, new String[]{"text-bzip2", "text", "bzip2", "UTF-8"})) {
            var source = compile(false, source()); var target = compile(true, target(mode[1], mode[2], mode[3]));
            var nodes = List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", source.table().physicalTable())),
                    node("write", "insert-db", Map.of("databaseId", 2, "tableName", target.table().physicalTable())));
            var sql = EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, nodes, List.of(edge()),
                    (id, name, schema) -> id == 1 ? source.table() : target.table());
            var path = Path.of("target", "native-hdfs-sql", mode[0] + ".sql"); Files.createDirectories(path.getParent());
            Files.writeString(path, source.initializationSql() + target.initializationSql() + sql);
        }
    }

    private static EtlNativeConnectorCompiler.TablePlan compile(boolean output, Map<String, Object> config) {
        return EtlNativeHdfsCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, output ? "target" : "source",
                output ? "write-hdfs" : "read-hdfs", config, BINDING);
    }
    private static Map<String, Object> source() { return Map.of("path", "native_hdfs_input.csv", "fileType", "csv", "hasHeader", true, "columns", fields()); }
    private static Map<String, Object> target(String type, String compression, String encoding) {
        return Map.of("path", "native_hdfs_output", "fileName", "measurements", "fileType", type, "compression", compression,
                "encoding", encoding, "fieldDelimiter", type.equals("text") ? "|" : ",", "columns", fields());
    }
    private static List<Map<String, Object>> fields() {
        return List.of(Map.of("name", "id", "type", "Long", "index", 0),
                Map.of("name", "amount", "type", "BigNumber", "length", 24, "precision", 4, "index", 1),
                Map.of("name", "day", "type", "Date", "format", "yyyy-MM-dd", "index", 2),
                Map.of("name", "label", "type", "String", "index", 3),
                Map.of("name", "empty", "type", "String", "index", 4), Map.of("name", "optional", "type", "String", "index", 5));
    }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) { var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node; }
    private static EtlEdgeDef edge() { var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write"); return edge; }
}
