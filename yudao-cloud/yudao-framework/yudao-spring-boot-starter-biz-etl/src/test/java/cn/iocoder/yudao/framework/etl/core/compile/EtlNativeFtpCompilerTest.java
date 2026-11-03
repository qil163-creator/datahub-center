package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeFtpCompilerTest {
    @Test void compilesExplicitFileOrDirectoryModeAndRejectsUnimplementedDestructiveSync() {
        var config = config(false); config.put("syncMode", "DIRECTORY"); config.put("syncDelete", 0);
        assertNotNull(compile("directory", false, config));
        config.put("syncMode", "SINGLE_FILE"); assertNotNull(compile("file", false, config));
        config.put("syncMode", "RECURSIVE"); assertThrows(IllegalArgumentException.class, () -> compile("invalid", false, config));
        config.put("syncMode", "DIRECTORY"); config.put("syncDelete", 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> compile("delete", false, config)).getMessage().contains("同步删除"));
    }
    @Test void consumesActualEditorDefaultsIncludingJsonTextAndUnicodeRename() throws Exception {
        var configs = EtlCompilerUiFixtures.configs();
        for (String code : List.of("read-ftp", "write-ftp")) {
            var plan = EtlNativeFtpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, code, code, configs.getJSONObject(code), binding(), Set.of("DH_READ_FTP"), Set.of("ftp.fixture"));
            assertEquals("BIGINT", plan.table().columns().get("id"));
            if (code.equals("write-ftp")) {
                assertEquals("name", plan.fieldMappings().get(1).get("source"));
                assertEquals("标签", plan.fieldMappings().get(1).get("target"));
            }
        }
    }
    private EtlDataxConnectorCompiler.Binding binding() {
        return new EtlDataxConnectorCompiler.Binding(Set.of("read-ftp", "write-ftp"), "/", null, List.of("ftp://127.0.0.1:19086"), null,
                EtlDataxConnectorCompiler.Authentication.REQUIRED, Set.of("spark-ftp", "ftp-chroot"));
    }
    private Map<String, Object> config(boolean output) {
        var columns = List.of(Map.of("name", "id", "type", "LONG", "index", 0), Map.of("name", "label", "type", "STRING", "index", 1),
                Map.of("name", "amount", "type", "DECIMAL", "length", 38, "precision", 18, "index", 2), Map.of("name", "flag", "type", "BOOLEAN", "index", 3),
                Map.of("name", "day", "type", "DATE", "index", 4), Map.of("name", "observed", "type", "TIMESTAMP", "index", 5));
        var config = new LinkedHashMap<String, Object>(Map.of("resourceBindingId", "ftp", "credentialRef", "ftpAccess", "path", output ? "output" : "input.csv", "fileType", "csv",
                "columns", columns, "ftpConfig", output ? Map.of("includeHeader", true) : Map.of("hasHeader", true)));
        if (output) { config.put("fileName", "result"); config.put("writeModeType", 2); }
        return config;
    }
    private EtlNativeConnectorCompiler.TablePlan compile(String identity, boolean output, Map<String, Object> config) {
        return EtlNativeFtpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, identity, output ? "write-ftp" : "read-ftp", config, binding(), Set.of("DH_READ_FTP"), Set.of("ftpAccess"));
    }
    @Test void createsTypedSourceAndStouSinkWithoutInlineCredentialsAndWritesRealEngineFixtures() throws Exception {
        var source = compile("ftp-source", false, config(false)); var sink = compile("ftp-sink", true, config(true));
        assertEquals("DECIMAL(38,18)", source.table().columns().get("amount")); assertTrue(source.initializationSql().contains("DH_READ_FTP('"));
        assertTrue(sink.initializationSql().contains("EtlFtpTextProvider")); assertFalse(sink.initializationSql().toLowerCase().contains("password"));
        Path directory = Path.of("target/native-ftp-sql"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("write.sql"), source.initializationSql() + sink.initializationSql() + "INSERT INTO `" + sink.table().physicalTable() + "` SELECT * FROM `" + source.table().physicalTable() + "`;\n");
        var readConfig = config(false); readConfig.put("path", "output"); var read = compile("ftp-output-read", false, readConfig);
        String query = "SELECT TO_JSON(NAMED_STRUCT('id',CAST(id AS STRING),'label',label,'amount',CAST(amount AS STRING),'flag',flag,'day',CAST(day AS STRING),'observed',CAST(observed AS STRING))) FROM `" + read.table().physicalTable() + "` ORDER BY id;\n";
        Files.writeString(directory.resolve("read.sql"), read.initializationSql() + query);
        var gzipConfig = config(true); gzipConfig.put("path", "compressed"); gzipConfig.put("compression", "gzip"); var gzip = compile("ftp-gzip", true, gzipConfig);
        var gzipReadConfig = config(false); gzipReadConfig.put("path", "compressed"); gzipReadConfig.put("compression", "gzip"); var gzipRead = compile("ftp-gzip-read", false, gzipReadConfig);
        Files.writeString(directory.resolve("gzip.sql"), source.initializationSql() + gzip.initializationSql() + gzipRead.initializationSql()
                + "INSERT INTO `" + gzip.table().physicalTable() + "` SELECT * FROM `" + source.table().physicalTable() + "`;\n"
                + query.replace(read.table().physicalTable(), gzipRead.table().physicalTable()));
        var textConfig = config(true); textConfig.put("fileType", "text"); textConfig.put("path", "reject"); var text = compile("ftp-text", true, textConfig);
        Files.writeString(directory.resolve("text-invalid.sql"), source.initializationSql() + text.initializationSql() + "INSERT INTO `" + text.table().physicalTable() + "` SELECT * FROM `" + source.table().physicalTable() + "`;\n");
    }
    @Test void rejectsUnboundSecretsUnjailledAccountsTraversalIgnoredOptionsAndOverwrite() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFtpCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, true, "n", "read-ftp", config(false), binding(), Set.of("DH_READ_FTP"), Set.of("ftpAccess")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFtpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-ftp", config(false), binding(), Set.of("DH_READ_FTP"), Set.of()));
        var unjailled = new EtlDataxConnectorCompiler.Binding(binding().connectorCodes(), "/other", null, binding().addresses(), null, binding().authentication(), Set.of("spark-ftp"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFtpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-ftp", config(false), unjailled, Set.of("DH_READ_FTP"), Set.of("ftpAccess")));
        var config = config(false); config.put("path", "../private"); assertThrows(IllegalArgumentException.class, () -> compile("n", false, config));
        config.put("path", "input.csv"); config.put("ftpConfig", Map.of("password", "test-only"));
        var failure = assertThrows(IllegalArgumentException.class, () -> compile("n", false, config)); assertFalse(failure.toString().contains("test-only"));
        var output = config(true); output.put("writeModeType", 1); assertThrows(IllegalArgumentException.class, () -> compile("n", true, output));
    }
    @Test void preservesCompleteMappingsIncludingRenamesAndRejectsSilentFieldDrops() {
        var config = config(true); var columns = (List<Map<String, Object>>) config.get("columns");
        var mappings = columns.stream().map(column -> Map.of("source", column.get("name"), "target", column.get("name"), "enabled", true)).toList(); config.put("fieldMappings", mappings);
        assertNotNull(compile("n", true, config)); config.put("fieldMappings", List.of(Map.of("source", "id", "target", "label", "enabled", true)));
        assertThrows(IllegalArgumentException.class, () -> compile("n", true, config));
        var renamed = new java.util.ArrayList<Map<String, Object>>();
        for (var column : columns) { var copy = new LinkedHashMap<>(column); copy.put("key", "source_" + column.get("name")); renamed.add(copy); }
        config.put("columns", renamed); config.remove("fieldMappings");
        var plan = compile("renamed", true, config);
        assertEquals("source_id", plan.fieldMappings().getFirst().get("source"));
        assertEquals("id", plan.fieldMappings().getFirst().get("target"));
    }
}
