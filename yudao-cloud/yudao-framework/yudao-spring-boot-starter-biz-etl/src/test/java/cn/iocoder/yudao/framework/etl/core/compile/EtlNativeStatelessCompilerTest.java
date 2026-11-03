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

class EtlNativeStatelessCompilerTest {
    private static final List<String> CALCULATIONS = List.of("ADD", "SUB", "MUL", "DIV", "ADD3", "MIN", "MAX", "ABS", "ROUND", "CEIL", "FLOOR", "MOD", "POWER", "SQRT", "ADD_DAYS", "DIFF_DAYS", "YEAR", "MONTH", "DAY", "STR_LEN", "UPPER", "LOWER", "INIT_CAP", "TRIM");

    @Test void allCalculatorOperationsCompileForBothEnginesIncludingStreaming() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) for (String operation : CALCULATIONS) {
            boolean date = Set.of("ADD_DAYS", "DIFF_DAYS", "YEAR", "MONTH", "DAY").contains(operation);
            var rule = new LinkedHashMap<String, Object>();
            rule.put("targetField", "result"); rule.put("calcType", operation); rule.put("valueType", "STRING");
            rule.put("fieldA", date ? "created" : "a"); rule.put("fieldB", "DIFF_DAYS".equals(operation) ? "created" : "b"); rule.put("fieldC", "c");
            String sql = compile(engine, "calculator", Map.of("rules", List.of(rule)));
            assertTrue(sql.contains("AS `result`"), operation + ": " + sql);
        }
    }

    @Test void calculatorRulesResolvePreviousResultsAndRejectUnknownFieldsOrOperations() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, "calculator", Map.of("rules", List.of(
                    Map.of("targetField", "sum", "calcType", "ADD", "fieldA", "a", "fieldB", "b", "valueType", "DOUBLE"),
                    Map.of("targetField", "result", "calcType", "MUL", "fieldA", "sum", "fieldB", "c", "valueType", "INTEGER"))));
            assertTrue(sql.contains("`sum`")); assertTrue(sql.contains("AS BIGINT"));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, "calculator", Map.of("rules", List.of(Map.of("targetField", "x", "calcType", "ADD", "fieldA", "missing", "fieldB", "b")))));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, "calculator", Map.of("rules", List.of(Map.of("targetField", "x", "calcType", "DROP TABLE x", "fieldA", "a")))));
        }
    }

    @Test void derivesEveryConfiguredOperationWithoutAcceptingRawSqlExpressions() {
        var configs = List.of(
                Map.<String, Object>of("operateType", "SUBSTRING", "sourceFields", List.of("name"), "beginIndex", 2, "length", 3),
                Map.<String, Object>of("operateType", "HASH", "sourceFields", List.of("name", "a"), "algorithm", "SHA256"),
                Map.<String, Object>of("operateType", "REGEX_EXTRACT", "sourceFields", List.of("name"), "pattern", "([a-z]+)", "groupIndex", 1),
                Map.<String, Object>of("operateType", "REPLACE", "sourceFields", List.of("name"), "search", "(a)", "replacement", "$1$1", "useRegex", true),
                Map.<String, Object>of("operateType", "EXPRESSION", "expression", "(${a} + ${b}) * 2"),
                Map.<String, Object>of("operateType", "EXPRESSION", "expression", "name=${name}; DROP TABLE test"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) for (var config : configs) {
            var values = new LinkedHashMap<>(config); values.put("targetField", "result");
            String sql = compile(engine, "field-derive", values);
            assertTrue(sql.contains("AS `result`"), sql);
            if (sql.contains("DROP TABLE test")) assertTrue(sql.contains("'; DROP TABLE test'"), sql);
        }
    }

    @Test void paddingNeverTruncatesAndReplacementSupportsUnicodeWordsAndLiteralFields() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, "string-operation", Map.of("rules", List.of(Map.of("inputField", "name", "padDirection", "BOTH", "padLength", 12, "padCharacter", "xy"))));
            assertTrue(sql.contains("GREATEST(0,"), sql); assertTrue(sql.contains("LPAD(''"), sql);
            String replacement = compile(engine, "string-replace", Map.of("rules", List.of(Map.of("inputField", "name", "search", "a", "wholeWord", true, "caseSensitive", false, "replacementField", "other"))));
            assertTrue(replacement.contains("DH_REGEX_REPLACE")); assertTrue(replacement.contains("`other`"));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, "string-replace", Map.of("rules", List.of(Map.of("inputField", "name", "search", "(", "regex", true)))));
        }
    }

    @Test void dateConstantsAndDeclaredFieldConversionsAreCompiledWithSafeTypes() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            assertTrue(compile(engine, "add-constant", Map.of("fields", List.of(Map.of("name", "result", "fieldType", "Date", "value", "2026-01-02 03:04:05")))).contains("TIMESTAMP"));
            assertTrue(compile(engine, "add-constant", Map.of("fields", List.of(Map.of("name", "result", "fieldType", "Boolean", "value", " true ")))).contains("TRUE AS `result`"));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, "add-constant", Map.of("fields", List.of(Map.of("name", "result", "fieldType", "Date", "value", "not-a-date")))));
            String sql = compile(engine, "column-map", Map.of("columnMappingMode", "FIELDS", "fieldSelections", List.of(Map.of("name", "a", "alias", "result", "type", "DECIMAL", "length", "18", "precision", "2"))));
            assertTrue(sql.contains("DECIMAL(18,2)"), sql);
            assertThrows(IllegalArgumentException.class, () -> compile(engine, "column-map", Map.of("columnMappingMode", "FIELDS", "fieldSelections", List.of(Map.of("name", "a", "type", "INT); DROP TABLE source")))));
        }
    }

    @Test void capabilitiesAreTheSameGateUsedByCompilation() {
        assertFalse(EtlNativeSqlCapabilities.componentLimitations(EtlNativeSqlCompiler.Engine.FLINK, true).containsKey("multi-join"));
        assertFalse(EtlNativeSqlCapabilities.componentLimitations(EtlNativeSqlCompiler.Engine.FLINK, true).containsKey("sort-records"));
        assertTrue(EtlNativeSqlCapabilities.componentLimitations(EtlNativeSqlCompiler.Engine.SPARK, false).containsKey("calculator"));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.FLINK, "invented-node", Map.of()));
    }

    @Test void regexSplitUsesTheSameManagedContractAndCompilesTypedWrappedTokens() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, "field-split", Map.of("sourceField", "name", "splitType", "REGEX", "pattern", "[,;]", "wrapper", "\"", "keepSource", true,
                    "outputs", List.of(Map.of("name", "result", "type", "Decimal", "length", "18", "precision", "2", "trimType", "BOTH"))));
            assertTrue(sql.contains("DH_SPLIT_TOKEN"), sql);
            assertTrue(sql.contains("DECIMAL(18,2)"), sql);
        }
    }

    @Test void managedFunctionsCannotBeClaimedByANodeOrLostWhileWrappingSecrets() {
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.FLINK, "string-replace", Map.of("rules", List.of(Map.of("inputField", "name", "search", "(a)", "regex", true, "replacement", "$1")), "managedFunctions", List.of("DH_REGEX_REPLACE")), Set.of()));
        var base = EtlNativeSqlCompiler.withManagedSecretRefs((id, name, schema) -> null, Set.of("secret-v1"));
        var wrapped = EtlNativeSqlCompiler.withManagedFunctions(base, Set.of("DH_REGEX_REPLACE"));
        assertEquals(Set.of("secret-v1"), wrapped.managedSecretRefs());
        assertEquals(Set.of("DH_REGEX_REPLACE"), EtlNativeSqlCompiler.withManagedSecretRefs(wrapped, Set.of("secret-v2")).managedFunctions());
    }

    @Test void sparkCliVariablesRemainLiteralDataAndCannotAppearInIdentifiers() {
        String sql = compile(EtlNativeSqlCompiler.Engine.SPARK, "add-constant", Map.of("fields", List.of(Map.of("name", "result", "fieldType", "String", "value", "${system:user.home}"))));
        assertFalse(sql.contains("${")); assertTrue(sql.contains("UNHEX"));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, "column-map", Map.of("columnMappingMode", "FIELDS", "fieldSelections", List.of(Map.of("name", "a", "alias", "${env:HOME}")))));
    }

    /** 生成可独立提交给真实引擎的有界样例；生成成功不等于已经在引擎执行。 */
    @Test void writesNativeEngineAcceptanceFixtures() throws Exception {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var nodes = new ArrayList<EtlNodeDef>(); var edges = new ArrayList<EtlEdgeDef>();
            var sourceFields = new LinkedHashMap<String, String>();
            for (String name : List.of("a", "b", "c")) sourceFields.put(name, "DECIMAL(20,4)");
            for (String name : List.of("name", "other", "created", "word_text", "null_text")) sourceFields.put(name, "STRING");
            var targetFields = new LinkedHashMap<String, String>(); var rules = new ArrayList<Map<String, Object>>();
            nodes.add(node("source", "read-db", Map.of("databaseId", 1, "tableName", "source")));
            for (String operation : CALCULATIONS) {
                String target = "r_" + operation; targetFields.put(target, "STRING");
                boolean date = Set.of("ADD_DAYS", "DIFF_DAYS", "YEAR", "MONTH", "DAY").contains(operation);
                rules.add(Map.of("targetField", target, "calcType", operation, "fieldA", date ? "created" : Set.of("STR_LEN", "UPPER", "LOWER", "INIT_CAP", "TRIM").contains(operation) ? "name" : "a",
                        "fieldB", "DIFF_DAYS".equals(operation) ? "created" : "b", "fieldC", "c", "valueType", "STRING"));
            }
            nodes.add(node("calc", "calculator", Map.of("rules", rules))); edges.add(edge("source", "calc"));
            var derives = List.of(
                    Map.<String, Object>of("operateType", "CONCAT", "sourceFields", List.of("name", "a"), "separator", "|", "prefix", "[", "suffix", "]"),
                    Map.<String, Object>of("operateType", "SUBSTRING", "sourceFields", List.of("name"), "beginIndex", 3, "length", 1),
                    Map.<String, Object>of("operateType", "HASH", "sourceFields", List.of("name"), "algorithm", "SHA256"),
                    Map.<String, Object>of("operateType", "REGEX_EXTRACT", "sourceFields", List.of("name"), "pattern", "(a)", "groupIndex", 1),
                    Map.<String, Object>of("operateType", "REPLACE", "sourceFields", List.of("name"), "search", "(a)", "replacement", "$1$1", "useRegex", true),
                    Map.<String, Object>of("operateType", "EXPRESSION", "expression", "(${a} + ${b}) * 2"),
                    Map.<String, Object>of("operateType", "CONSTANT", "constantValue", "O'Reilly\\$1"));
            String previous = "calc";
            for (int i = 0; i < derives.size(); i++) {
                String id = "derive_" + i; String target = "d_" + derives.get(i).get("operateType");
                var config = new LinkedHashMap<>(derives.get(i)); config.put("targetField", target); targetFields.put(target, "STRING");
                nodes.add(node(id, "field-derive", config)); edges.add(edge(previous, id)); previous = id;
            }
            var stringRules = new ArrayList<Map<String, Object>>();
            for (String side : List.of("LEFT", "RIGHT", "BOTH")) { String target = "s_" + side; targetFields.put(target, "STRING"); stringRules.add(Map.of("inputField", "name", "outputField", target, "padDirection", side, "padLength", 12, "padCharacter", "xy")); }
            nodes.add(node("padding", "string-operation", Map.of("rules", stringRules))); edges.add(edge(previous, "padding"));
            targetFields.put("s_replacement", "STRING"); targetFields.put("s_word", "STRING"); targetFields.put("s_word_alt", "STRING"); targetFields.put("s_named", "STRING"); targetFields.put("s_null", "STRING");
            nodes.add(node("replacement", "string-replace", Map.of("rules", List.of(
                    Map.of("inputField", "name", "outputField", "s_replacement", "search", "A", "caseSensitive", false, "replacementField", "other"),
                    Map.of("inputField", "name", "outputField", "s_word", "search", "a", "wholeWord", true, "replacement", "W"),
                    Map.of("inputField", "word_text", "outputField", "s_word_alt", "search", "a|ab", "wholeWord", true, "regex", true, "replacement", "W"),
                    Map.of("inputField", "name", "outputField", "s_named", "search", "(?<token>a)", "regex", true, "replacement", "${token}${token}"),
                    Map.of("inputField", "null_text", "outputField", "s_null", "search", "a", "wholeWord", true, "replacement", "W"))))); edges.add(edge("padding", "replacement"));
            targetFields.put("c_date", "STRING"); nodes.add(node("date", "add-constant", Map.of("fields", List.of(
                    Map.of("name", "c_date", "fieldType", "Date", "value", "2026-01-02 03:04:05"),
                    Map.of("name", "csv_text", "fieldType", "String", "value", "\" 12 \";\"34\";"),
                    Map.of("name", "date_text", "fieldType", "String", "value", "02/01/2026 03:04:05"))))); edges.add(edge("replacement", "date"));
            for (String name : List.of("part_int", "part_decimal", "part_empty", "part_missing", "part_date")) targetFields.put(name, "STRING");
            nodes.add(node("split", "field-split", Map.of("sourceField", "csv_text", "splitType", "REGEX", "pattern", "[,;]", "wrapper", "\"", "outputs", List.of(
                    Map.of("name", "part_int", "type", "Integer", "trimType", "BOTH"),
                    Map.of("name", "part_decimal", "type", "Decimal", "length", "18", "precision", "2"),
                    Map.of("name", "part_empty"), Map.of("name", "part_missing"))))); edges.add(edge("date", "split"));
            nodes.add(node("date_split", "field-split", Map.of("sourceField", "date_text", "delimiter", ";", "outputs", List.of(Map.of("name", "part_date", "type", "Date", "format", "dd/MM/yyyy HH:mm:ss"))))); edges.add(edge("split", "date_split"));
            nodes.add(node("fields", "column-map", Map.of("columnMappingMode", "FIELDS", "fieldSelections", targetFields.keySet().stream().map(name -> Map.of("name", name, "type", "STRING")).toList()))); edges.add(edge("date_split", "fields"));
            nodes.add(node("sink", "insert-db", Map.of("databaseId", 2, "tableName", "sink"))); edges.add(edge("fields", "sink"));
            String sql = EtlNativeSqlCompiler.compile(engine, false, nodes, edges, EtlNativeSqlCompiler.withManagedFunctions((id, table, schema) -> new EtlNativeSqlCompiler.Table(List.of(table), id == 1L ? sourceFields : targetFields, ProductTypeEnum.POSTGRESQL, "public", table, false), Set.of("DH_REGEX_REPLACE", "DH_SPLIT_TOKEN")));
            String source = "CREATE TEMPORARY VIEW source AS SELECT CAST(6 AS DECIMAL(20,4)) AS a, CAST(2 AS DECIMAL(20,4)) AS b, CAST(1.25 AS DECIMAL(20,4)) AS c, '  a😀b  ' AS name, '$1tail' AS other, '2026-01-02 03:04:05' AS created, 'ab a á _a_' AS word_text, CAST(NULL AS STRING) AS null_text;\n";
            String columns = String.join(", ", targetFields.keySet().stream().map(name -> "`" + name + "` STRING").toList());
            String suffix = engine.name().toLowerCase(Locale.ROOT);
            String sink = engine == EtlNativeSqlCompiler.Engine.FLINK ? "SET 'execution.runtime-mode' = 'batch';\nSET 'table.dml-sync'='true';\nCREATE TEMPORARY TABLE sink (" + columns + ") WITH ('connector'='filesystem', 'path'='file:///tmp/datahub-native-stateless-" + suffix + "-result', 'format'='json');\n"
                    : "CREATE TABLE sink (" + columns + ") USING json OPTIONS (path '/tmp/datahub-native-stateless-" + suffix + "-result');\n";
            Path artifact = Path.of("target", "native-sql", "stateless-" + suffix + ".sql"); Files.createDirectories(artifact.getParent()); Files.writeString(artifact, source + sink + sql);
        }
    }

    private static String compile(EtlNativeSqlCompiler.Engine engine, String code, Map<String, Object> config) {
        return compile(engine, code, config, Set.of("DH_REGEX_REPLACE", "DH_SPLIT_TOKEN"));
    }
    private static String compile(EtlNativeSqlCompiler.Engine engine, String code, Map<String, Object> config, Set<String> functions) {
        var fields = new LinkedHashMap<String, String>();
        for (String name : List.of("a", "b", "c")) fields.put(name, "DECIMAL(20,4)");
        for (String name : List.of("name", "other", "created")) fields.put(name, "STRING");
        fields.put("result", "STRING"); fields.put("sum", "DOUBLE");
        return EtlNativeSqlCompiler.compile(engine, engine == EtlNativeSqlCompiler.Engine.FLINK,
                List.of(node("source", "read-db", Map.of("databaseId", 1, "tableName", "source")), node("convert", code, config), node("sink", "insert-db", Map.of("databaseId", 2, "tableName", "sink"))),
                List.of(edge("source", "convert"), edge("convert", "sink")),
                EtlNativeSqlCompiler.withManagedFunctions((id, table, schema) -> new EtlNativeSqlCompiler.Table(List.of(table), fields, ProductTypeEnum.POSTGRESQL, "public", table, true), functions));
    }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) { var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node; }
    private static EtlEdgeDef edge(String source, String target) { var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); return edge; }
}
