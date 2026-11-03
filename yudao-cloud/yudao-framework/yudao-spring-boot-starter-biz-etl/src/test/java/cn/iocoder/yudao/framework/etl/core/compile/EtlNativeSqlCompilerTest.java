package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeSqlCompilerTest {
    @Test void compilesTableTransformOutputForBothNativeEnginesAndRetainsResourceFreeSql() throws Exception {
        var nodes = List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("format", "string-operation", Map.of("rules", List.of(Map.of("inputField", "name", "trim", "BOTH", "caseMode", "UPPER")))),
                node("constant", "add-constant", Map.of("fields", List.of(Map.of("name", "label", "fieldType", "String", "value", "q'Data")))),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target")));
        var edges = chain("read", "format", "constant", "write");
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = EtlNativeSqlCompiler.compile(engine, false, nodes, edges, (id, name, schema) -> table(engine, id, name, schema));
            assertTrue(sql.startsWith(engine == EtlNativeSqlCompiler.Engine.FLINK ? "INSERT INTO `default_catalog`.`default_database`.`target`" : "INSERT INTO `spark_catalog`.`default`.`target`"), sql);
            assertTrue(sql.contains("UPPER(REGEXP_REPLACE(CAST(`name` AS STRING)"), sql);
            assertTrue(sql.contains(engine == EtlNativeSqlCompiler.Engine.SPARK ? "DECODE(UNHEX('712744617461'), 'UTF-8') AS `label`" : "'q''Data' AS `label`"), sql); assertFalse(sql.contains("password"));
            assertFalse(sql.codePoints().anyMatch(value -> Character.isISOControl(value) && value != 10), "原生 SQL 不能携带会被终端解释的控制字符");
            Path output = Path.of("target", "native-sql", engine.name().toLowerCase(Locale.ROOT) + ".sql");
            Files.createDirectories(output.getParent()); Files.writeString(output, sql);
        }
    }

    @Test void rulesReadOriginalValuesAndPreserveNulls() {
        var rules = List.of(Map.of("sourceField", "id", "targetField", "name"), Map.of("sourceField", "name", "targetField", "id"));
        String sql = compile(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("swap", "set-field-value", Map.of("rules", rules)), node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), chain("read", "swap", "write"));
        assertTrue(sql.contains("`name` AS `id`, `id` AS `name`"), sql);
        assertFalse(sql.contains("COALESCE"));
    }

    @Test void unknownFieldsAndUnsupportedModesCannotBeIgnored() {
        assertThrows(IllegalArgumentException.class, () -> compile(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source", "readMode", "ID_INCREMENT")),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), chain("read", "write")));
        assertThrows(IllegalArgumentException.class, () -> compile(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                node("convert", "string-replace", Map.of("rules", List.of(Map.of("inputField", "missing", "search", "a")))),
                node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), chain("read", "convert", "write")));
    }

    @Test void rejectsKeyDedupStreamingSortAndCompilesLiteralRegex() {
        var read = node("read", "read-db", Map.of("databaseId", 1, "tableName", "source"));
        var write = node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"));
        assertThrows(IllegalArgumentException.class, () -> compile(List.of(read, node("convert", "data-clean", Map.of("enableDeduplication", true, "deduplicationStrategy", "KEY_FIELDS", "keyFields", List.of("id"))), write), chain("read", "convert", "write")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, true,
                List.of(read, node("convert", "sort-records", Map.of("sorts", List.of(Map.of("field", "id")))), write), chain("read", "convert", "write"), EtlNativeSqlCompilerTest::table));
        assertTrue(compile(List.of(read, node("convert", "string-replace", Map.of("rules", List.of(Map.of("inputField", "name", "search", "a", "regex", true)))), write), chain("read", "convert", "write")).contains("REGEXP_REPLACE"));
    }

    @Test void quoteEscapesAliasesAndRejectsSqlConditionInjection() {
        var read = node("read", "read-db", Map.of("databaseId", 1, "tableName", "source", "whereCondition", "id>0; DELETE FROM target"));
        assertThrows(IllegalArgumentException.class, () -> compile(List.of(read, node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), chain("read", "write")));
    }

    @Test void splitNeverIgnoresDeclaredConversions() {
        for (var option : List.of(Map.<String, Object>of("type", "DATE"), Map.<String, Object>of("length", 10))) {
            var output = new java.util.LinkedHashMap<String, Object>(option); output.put("name", "label");
            assertDoesNotThrow(() -> compile(List.of(node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                    node("split", "field-split", Map.of("sourceField", "name", "delimiter", ",", "keepSource", true, "outputs", List.of(output))),
                    node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), chain("read", "split", "write")));
        }
        for (var option : List.of(Map.<String, Object>of("format", "yyyy-MM-dd"), Map.<String, Object>of("precision", 0))) {
            var output = new java.util.LinkedHashMap<String, Object>(option); output.put("name", "label");
            var failure = assertThrows(IllegalArgumentException.class, () -> compile(List.of(
                    node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")),
                    node("split", "field-split", Map.of("sourceField", "name", "delimiter", ",", "outputs", List.of(output))),
                    node("write", "insert-db", Map.of("databaseId", 2, "tableName", "target"))), chain("read", "split", "write")));
            assertFalse(failure.getMessage().isBlank());
        }
    }

    private static String compile(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        return EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, nodes, edges, EtlNativeSqlCompilerTest::table);
    }
    private static EtlNativeSqlCompiler.Table table(Long id, String name, String schema) {
        return table(EtlNativeSqlCompiler.Engine.SPARK, id, name, schema);
    }
    private static EtlNativeSqlCompiler.Table table(EtlNativeSqlCompiler.Engine engine, Long id, String name, String schema) {
        var columns = new LinkedHashMap<String, String>(); columns.put("id", "BIGINT"); columns.put("name", "VARCHAR");
        if ("target".equals(name)) columns.put("label", "VARCHAR");
        return new EtlNativeSqlCompiler.Table(engine == EtlNativeSqlCompiler.Engine.FLINK ? List.of("default_catalog", "default_database", name) : List.of("spark_catalog", "default", name), columns, ProductTypeEnum.POSTGRESQL, "public", name);
    }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static List<EtlEdgeDef> chain(String... ids) {
        var result = new ArrayList<EtlEdgeDef>(); for (int i = 1; i < ids.length; i++) { var edge = new EtlEdgeDef(); edge.setSourceNo(ids[i - 1]); edge.setTargetNo(ids[i]); result.add(edge); } return result;
    }
}
