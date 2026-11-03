package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeFileCompilerTest {
    private static EtlDataxConnectorCompiler.Binding binding() {
        return new EtlDataxConnectorCompiler.Binding(Set.of("read-csv", "read-excel", "read-json"), "/tmp/datahub-native-files", null,
                List.of(), null, EtlDataxConnectorCompiler.Authentication.NONE, Set.of());
    }
    private static Map<String, Object> csv() {
        var config = new LinkedHashMap<String, Object>();
        config.put("relativePath", "input.csv"); config.put("hasHeader", true); config.put("encoding", "UTF-8");
        config.put("fieldDelimiter", ";"); config.put("startRow", 2); config.put("startColumn", 2);
        config.put("columns", List.of(Map.of("name", "ignored", "type", "String", "index", 0),
                Map.of("name", "id", "type", "Integer", "index", 1), Map.of("name", "name", "alias", "label", "type", "String", "index", 2)));
        return config;
    }
    private static Map<String, Object> json() {
        return new LinkedHashMap<>(Map.of("path", "input.jsonl", "columns", List.of(
                Map.of("name", "id", "key", "$.source_id", "type", "Long"),
                Map.of("name", "label", "jsonPath", "$.name", "type", "String"),
                Map.of("name", "amount", "type", "DECIMAL(20,4)"))));
    }

    @Test void emitsNativeViewsWithCompleteCsvSchemaAndConsumesOffsetHeaderDelimiterAndAliases() throws Exception {
        var plan = EtlNativeFileCompiler.compile(SPARK, false, "csv-input", "read-csv", csv(), binding());
        assertEquals(List.of("id", "label"), new ArrayList<>(plan.table().columns().keySet()));
        assertEquals("BIGINT", plan.table().columns().get("id")); assertFalse(plan.table().continuousSource());
        assertTrue(plan.initializationSql().contains("USING csv OPTIONS"));
        assertTrue(plan.initializationSql().contains("`delimiter` ';'"));
        assertTrue(plan.initializationSql().contains("`encoding` 'UTF-8'"));
        assertTrue(plan.initializationSql().contains("`header` 'true'"));
        assertTrue(plan.initializationSql().contains("`enforceSchema` 'false'"));
        assertTrue(plan.initializationSql().contains("SELECT `id` AS `id`, `name` AS `label`"));
        assertFalse(plan.initializationSql().contains("SELECT `ignored`"));
        assertThrows(UnsupportedOperationException.class, () -> plan.table().columns().put("injected", "STRING"));
        writeFixture("csv.sql", plan.initializationSql() + "SELECT id, label FROM `" + plan.table().physicalTable() + "` ORDER BY id;\n");
        writeFixture("csv-malformed.sql", plan.initializationSql().replace("/input.csv", "/malformed.csv") + "SELECT * FROM `" + plan.table().physicalTable() + "`;\n");
        writeFixture("csv-header-mismatch.sql", plan.initializationSql().replace("/input.csv", "/wrong-header.csv") + "SELECT * FROM `" + plan.table().physicalTable() + "`;\n");
    }

    @Test void emitsStrictJsonlWithExplicitTypesAndTopLevelFieldMapping() throws Exception {
        var plan = EtlNativeFileCompiler.compile(SPARK, false, "json-input", "read-json", json(), binding());
        assertEquals(Map.of("id", "BIGINT", "label", "STRING", "amount", "DECIMAL(20,4)"), plan.table().columns());
        assertTrue(plan.initializationSql().contains("`source_id` BIGINT"));
        assertTrue(plan.initializationSql().contains("`multiLine` 'false'"));
        assertTrue(plan.initializationSql().contains("`mode` 'FAILFAST'"));
        assertTrue(plan.initializationSql().contains("`allowSingleQuotes` 'false'"));
        writeFixture("jsonl.sql", plan.initializationSql() + "SELECT id, label, amount FROM `" + plan.table().physicalTable() + "` ORDER BY id;\n");
        writeFixture("jsonl-malformed.sql", plan.initializationSql().replace("/input.jsonl", "/malformed.jsonl") + "SELECT * FROM `" + plan.table().physicalTable() + "`;\n");
    }

    @Test void appliesDeclaredGbkEncodingInsteadOfOnlyValidatingItsName() throws Exception {
        var config = csv(); config.put("encoding", "GBK"); config.put("relativePath", "input-gbk.csv");
        var plan = EtlNativeFileCompiler.compile(SPARK, false, "csv-gbk-input", "read-csv", config, binding());
        assertTrue(plan.initializationSql().contains("`encoding` 'GBK'"));
        writeFixture("csv-gbk.sql", plan.initializationSql() + "SELECT id, label FROM `" + plan.table().physicalTable() + "` ORDER BY id;\n");
    }

    @Test void decimalUiMetadataIsConsumedAsExplicitDecimalPrecisionAndScale() {
        var config = json(); config.put("columns", List.of(Map.of("name", "amount", "type", "BigNumber", "length", 30, "precision", 4)));
        var plan = EtlNativeFileCompiler.compile(SPARK, false, "decimal", "read-json", config, binding());
        assertEquals("DECIMAL(30,4)", plan.table().columns().get("amount"));
        assertTrue(plan.initializationSql().contains("`amount` DECIMAL(30,4)"));
    }

    @Test void csvKeepsQuotedEmptyTextDistinctFromExplicitNullMarkers() throws Exception {
        var config = new LinkedHashMap<String, Object>(Map.of("path", "null-empty.csv", "hasHeader", true,
                "columns", List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "name", "type", "String"))));
        var plan = EtlNativeFileCompiler.compile(SPARK, false, "csv-null-empty", "read-csv", config, binding());
        assertTrue(plan.initializationSql().contains("`nullValue` '\\\\N'"));
        assertTrue(plan.initializationSql().contains("`emptyValue` ''"));
        writeFixture("csv-null-empty.sql", plan.initializationSql() + "SELECT id, name IS NULL, LENGTH(name), HEX(name) FROM `" + plan.table().physicalTable() + "` ORDER BY id;\n");
        config.put("nullValue", "__NULL__"); config.put("path", "null-empty-custom.csv");
        plan = EtlNativeFileCompiler.compile(SPARK, false, "csv-null-custom", "read-csv", config, binding());
        assertTrue(plan.initializationSql().contains("`nullValue` '__NULL__'"));
        writeFixture("csv-null-empty-custom.sql", plan.initializationSql() + "SELECT id, name IS NULL, LENGTH(name), HEX(name) FROM `" + plan.table().physicalTable() + "` ORDER BY id;\n");
        config.put("nullValue", "");
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "csv-null-empty", "read-csv", config, binding()));
    }

    @Test void excelConsumesTheWholeWorkbookSelectionAndBuildsStrictTypedViews() throws Exception {
        var config = new LinkedHashMap<String, Object>();
        config.put("relativePath", "input.xlsx"); config.put("sheetName", "所选工作表"); config.put("headerRow", 1);
        config.put("startRow", 2); config.put("endRow", 2); config.put("startColumn", 2); config.put("formulaMode", "LITERAL");
        config.put("columns", List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "amount", "type", "DECIMAL(30,4)"),
                Map.of("name", "day", "type", "Date"), Map.of("name", "formula", "type", "String"),
                Map.of("name", "blank", "type", "String"), Map.of("name", "empty", "type", "String")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "excel", "read-excel", config, binding()));
        var plan = EtlNativeFileCompiler.compile(SPARK, false, "excel", "read-excel", config, binding(), Set.of("DH_READ_EXCEL"));
        assertTrue(plan.initializationSql().contains("LATERAL VIEW DH_READ_EXCEL("));
        assertTrue(plan.initializationSql().contains("'所选工作表', 1, 2, 2, 2, 6, true, 'LITERAL'"));
        assertEquals("DECIMAL(30,4)", plan.table().columns().get("amount"));
        assertTrue(plan.initializationSql().contains("RAISE_ERROR('Excel 字段类型转换失败')"));
        String query = "SELECT id, amount, day, formula, blank IS NULL, LENGTH(empty) FROM `" + plan.table().physicalTable() + "`;\n";
        writeFixture("excel.sql", plan.initializationSql() + query);
        writeFixture("excel-bad-type.sql", plan.initializationSql().replace("'input.xlsx'", "'bad-type.xlsx'") + query);
        writeFixture("excel-missing-sheet.sql", plan.initializationSql().replace("'所选工作表'", "'missing'") + query);
        writeFixture("excel-symlink.sql", plan.initializationSql().replace("'input.xlsx'", "'link.xlsx'") + query);
        writeFixture("excel-oversized.sql", plan.initializationSql().replace("'input.xlsx'", "'oversized.xlsx'") + query);
        config.put("formulaMode", "CACHED");
        var columns = new ArrayList<Object>(config.get("columns") instanceof List<?> list ? list : List.of());
        columns.set(3, Map.of("name", "formula", "type", "Integer")); config.put("columns", columns);
        var cached = EtlNativeFileCompiler.compile(SPARK, false, "excel-cached", "read-excel", config, binding(), Set.of("DH_READ_EXCEL"));
        writeFixture("excel-cached.sql", cached.initializationSql() + "SELECT formula FROM `" + cached.table().physicalTable() + "`;\n");
        config.put("relativePath", "input.xls");
        var binary = EtlNativeFileCompiler.compile(SPARK, false, "excel-binary", "read-excel", config, binding(), Set.of("DH_READ_EXCEL"));
        writeFixture("excel-binary.sql", binary.initializationSql() + "SELECT id, amount, day, formula FROM `" + binary.table().physicalTable() + "`;\n");
    }

    @Test void neverTreatsAnUploadIdUrlOrUnboundPathAsWorkerAuthorization() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-csv", csv(), null));
        for (var option : List.of(Map.<String, Object>of("fileId", 123), Map.<String, Object>of("fileUrl", "https://example.invalid/file.csv"),
                Map.<String, Object>of("relativePath", "../secret.csv"), Map.<String, Object>of("relativePath", "/etc/secret.csv"),
                Map.<String, Object>of("relativePath", "*.csv"), Map.<String, Object>of("relativePath", "input.csv;SELECT.csv"))) {
            var config = csv(); config.putAll(option);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-csv", config, binding()));
        }
        var credentials = new EtlDataxConnectorCompiler.Binding(Set.of("read-csv"), "/tmp/files", null, List.of(), null, EtlDataxConnectorCompiler.Authentication.REQUIRED, Set.of());
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-csv", csv(), credentials));
    }

    @Test void rejectsUnsupportedRuntimeOffsetsSchemaOptionsAndNestedPathsRatherThanIgnoringThem() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(FLINK, false, "n", "read-csv", csv(), binding()));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, true, "n", "read-csv", csv(), binding()));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-excel", csv(), binding()));
        for (var option : List.of(Map.<String, Object>of("startRow", 3), Map.<String, Object>of("startColumn", 1.5),
                Map.<String, Object>of("skipEmptyRows", false), Map.<String, Object>of("readMode", "INCREMENT"),
                Map.<String, Object>of("encoding", "UTF-16"), Map.<String, Object>of("fieldDelimiter", "||"),
                Map.<String, Object>of("unknownOption", true), Map.<String, Object>of("hasHeader", "yes"))) {
            var config = csv(); config.putAll(option);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-csv", config, binding()));
        }
        for (var column : List.of(Map.<String, Object>of("name", "id", "type", "String", "length", 5),
                Map.<String, Object>of("name", "id", "type", "String", "index", 1),
                Map.<String, Object>of("name", "id", "type", "DECIMAL(39,1)"), Map.<String, Object>of("name", "id", "type", "DECIMAL(5,6)"))) {
            var config = csv(); config.put("columns", List.of(column)); config.put("startColumn", 1);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-csv", config, binding()));
        }
        var nested = json(); nested.put("columns", List.of(Map.of("name", "x", "jsonPath", "$.parent.child", "type", "String")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-json", nested, binding()));
        var offset = json(); offset.put("startRow", 2);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeFileCompiler.compile(SPARK, false, "n", "read-json", offset, binding()));
    }

    private static void writeFixture(String name, String sql) throws Exception {
        Path destination = Path.of("target", "native-file-sql", name);
        Files.createDirectories(destination.getParent()); Files.writeString(destination, sql);
    }
}
