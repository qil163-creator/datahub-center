package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.util.*;

import static cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlDataxConnectorCompilerTest {
    @Test void uploadedFilesCannotSilentlyExecuteStaleManagedPaths() {
        var config = config("previous.csv"); config.put("fileId", "9007199254740993");
        assertTrue(assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")))
                .getMessage().contains("上传附件"));
        config.remove("fileId"); config.put("fileFormat", "CSV");
        assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")));
    }
    @Test void compilesCsvWithinAdminRootAndCreatesNonConflictingFiles() {
        var source = compile("read-csv", config("source.csv"), files("read-csv", "txtfilereader"));
        assertEquals("txtfilereader", source.name());
        assertEquals(List.of("/tmp/qdata-csv/source.csv"), source.parameter().get("path"));
        assertEquals(List.of(Map.of("index", 0, "type", "long"), Map.of("index", 1, "type", "string")), source.parameter().get("column"));
        var target = compile("write-csv", config("out"), files("write-csv", "txtfilewriter"));
        assertEquals("nonConflict", target.parameter().get("writeMode"));
        assertEquals("csv", target.parameter().get("fileFormat"));
        assertEquals(List.of("id", "名称"), target.parameter().get("header"));
        validatePair(config("source.csv"), config("out"));
    }

    @Test void rejectsPathEscapesUrlsWildcardsAndEngineTemplates() {
        for (String path : List.of("../secret", "a/../b", "/absolute.csv", "http://host/file", "*.csv", "a/%2e%2e/b", "${secret}", "a//b"))
            assertThrows(ConfigurationException.class, () -> compile("read-csv", config(path), files("read-csv", "txtfilereader")), path);
        var url = config("source.csv"); url.put("fileUrl", "https://example.invalid/source.csv");
        assertThrows(ConfigurationException.class, () -> compile("read-csv", url, files("read-csv", "txtfilereader")));
    }

    @Test void defaultsAreClosedAndMissingPluginsDoNotPretendToRun() {
        assertEquals(Failure.UNBOUND_RESOURCE, assertThrows(ConfigurationException.class,
                () -> compile("read-csv", config("source.csv"), null)).failure());
        var locked = new Binding(Set.of("read-csv"), "/safe", null, null, null, null, Set.of("txtfilereader"));
        assertEquals(Failure.SECURE_BINDING_REQUIRED, assertThrows(ConfigurationException.class,
                () -> compile("read-csv", config("source.csv"), locked)).failure());
        var missing = new Binding(Set.of("read-csv"), "/safe", null, null, null, Authentication.NONE, Set.of());
        assertEquals(Failure.UNSUPPORTED_PLUGIN, assertThrows(ConfigurationException.class,
                () -> compile("read-csv", config("source.csv"), missing)).failure());
        for (String code : List.of("read-json", "write-json", "read-redis", "write-redis", "read-hive"))
            assertEquals(Failure.UNSUPPORTED_PLUGIN, assertThrows(ConfigurationException.class,
                    () -> compile(code, Map.of(), null)).failure());
        for (String code : List.of("read-odps", "write-odps", "read-ftp", "write-ftp"))
            assertEquals(Failure.SECURE_BINDING_REQUIRED, assertThrows(ConfigurationException.class,
                    () -> compile(code, Map.of(), null)).failure());
    }

    @Test void hdfsSupportsPublicParametersButRejectsUnverifiedCsvWriterAndCustomHadoopSettings() {
        var binding = new Binding(Set.of("read-hdfs", "write-hdfs"), "/warehouse/safe", "hdfs://namenode:8020", null, null,
                Authentication.NONE, Set.of("hdfsreader", "hdfswriter"));
        var source = config("incoming/part.csv"); source.put("fileType", "csv");
        assertEquals("hdfs://namenode:8020", compile("read-hdfs", source, binding).parameter().get("defaultFS"));
        assertEquals(false, compile("read-hdfs", source, binding).parameter().get("haveKerberos"));
        assertThrows(ConfigurationException.class, () -> compile("write-hdfs", source, binding));
        var target = config("out"); target.put("fileType", "text");
        assertEquals(List.of(Map.of("name", "id", "type", "BIGINT"), Map.of("name", "名称", "type", "STRING")),
                compile("write-hdfs", target, binding).parameter().get("column"));
        source.put("hadoopConfig", "{\"fs.defaultFS\":\"file:///\"}");
        assertThrows(ConfigurationException.class, () -> compile("read-hdfs", source, binding));
    }

    @Test void mongoAnonymousRequiresExplicitAdministratorDecisionAndCannotOverwriteOrExecuteSql() {
        var binding = new Binding(Set.of("read-mongodb", "write-mongodb"), null, null,
                List.of("mongo.internal:27017"), "demo", Authentication.ANONYMOUS, Set.of("mongodbreader", "mongodbwriter"));
        var config = config("unused"); config.put("tableName", "readings");
        var plugin = compile("write-mongodb", config, binding);
        assertEquals(List.of("mongo.internal:27017"), plugin.parameter().get("address"));
        assertEquals(Map.of("isReplace", false), plugin.parameter().get("writeMode"));
        assertFalse(plugin.parameter().containsKey("userName")); assertFalse(plugin.parameter().containsKey("userPassword"));
        config.put("writeModeType", 1);
        assertThrows(ConfigurationException.class, () -> compile("write-mongodb", config, binding));
        config.remove("writeModeType"); config.put("sql", "select * from readings");
        assertThrows(ConfigurationException.class, () -> compile("read-mongodb", config, binding));
        config.remove("sql"); config.put("batchSize", 2000);
        assertThrows(ConfigurationException.class, () -> compile("write-mongodb", config, binding));
    }

    @Test void rejectsSecretsLossyTypesIncrementalReadsAndReorderedTargetFields() {
        for (String type : List.of("DECIMAL", "TIMESTAMP", "BINARY", "OBJECT")) {
            var config = config("source.csv"); config.put("columns", List.of(Map.of("name", "amount", "type", type, "index", 0)));
            assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")));
        }
        var config = config("source.csv"); config.put("credentials", Map.of("value", "not-a-real-secret"));
        assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")));
        config.remove("credentials"); config.put("readMode", "ID_INCREMENT");
        assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")));
        var target = config("out"); var columns = new ArrayList<>((List<?>) target.get("columns")); Collections.reverse(columns); target.put("columns", columns);
        assertThrows(ConfigurationException.class, () -> validatePair(config("source.csv"), target));
    }

    @Test void unknownColumnTypesNeverDefaultToString() {
        for (var column : List.of(Map.<String, Object>of("name", "value", "index", 0), Map.<String, Object>of("name", "value", "index", 0, "type", ""))) {
            var config = config("source.csv"); config.put("columns", List.of(column));
            var failure = assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")));
            assertTrue(failure.getMessage().contains("字段类型尚未确定"));
        }
    }

    @Test void unsupportedDateFormatsAreRejectedInsteadOfIgnored() {
        var config = config("source.csv"); config.put("columns", List.of(Map.of("name", "date", "index", 0, "type", "DATE", "format", "yyyy-MM-dd")));
        var failure = assertThrows(ConfigurationException.class, () -> compile("read-csv", config, files("read-csv", "txtfilereader")));
        assertTrue(failure.getMessage().contains("字段格式转换"));
    }

    private static Map<String, Object> config(String path) {
        var result = new LinkedHashMap<String, Object>(); result.put("path", path);
        result.put("columns", List.of(Map.of("name", "id", "type", "LONG", "index", 0), Map.of("name", "名称", "type", "STRING", "index", 1)));
        return result;
    }
    private static Binding files(String code, String plugin) {
        return new Binding(Set.of(code), "/tmp/qdata-csv", null, null, null, Authentication.NONE, Set.of(plugin));
    }
}
