package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只手工声明隔离 VALUES 输入与临时 sink，所有转换 SQL 均来自正式 Compiler/Expander。 */
class EtlNativeControlTransformFixtureTest {
    private static final String MARKER = "O'Reilly\\$1😀", TRACE = "子流程'O\\$😀";
    private static final Map<String, LinkedHashMap<String, String>> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("a_source", fields("id", "BIGINT", "amount", "DECIMAL(24,4)", "name", "STRING", "junk", "STRING"));
        TABLES.put("a_out", fields("id", "BIGINT", "amount", "DECIMAL(24,4)", "名称", "STRING", "bucket", "STRING", "marker", "STRING", "trace", "STRING", "which", "STRING", "value", "STRING"));
        TABLES.put("c_source", fields("group", "STRING", "id", "INT", "payload", "STRING")); TABLES.put("c_out", new LinkedHashMap<>(TABLES.get("c_source")));
        TABLES.put("d_source", fields("id", "STRING", "n", "BIGINT")); TABLES.put("d_out", new LinkedHashMap<>(TABLES.get("d_source")));
    }

    @Test void keyAndCaseInsensitiveDedupRequireExplicitTotalOrderAndPreserveOriginalFields() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            for (String strategy : List.of("KEY_FIELDS", "ALL_FIELDS")) {
                var config = dedup(strategy, true); if (strategy.equals("ALL_FIELDS")) { config.put("orderBy", order("id")); config.put("uniqueFields", List.of("id")); }
                var source = strategy.equals("ALL_FIELDS") ? "d_source" : "c_source"; var sink = strategy.equals("ALL_FIELDS") ? "d_out" : "c_out";
                String sql = compileDedup(engine, false, source, sink, config);
                assertTrue(sql.contains(strategy.equals("KEY_FIELDS") ? "PARTITION BY LOWER(`group`)" : "PARTITION BY LOWER(`id`), `n`"), sql);
                assertTrue(sql.contains("唯一排序字段存在重复值")); assertTrue(sql.contains("ROW_NUMBER() OVER"));
                var missing = new LinkedHashMap<>(config); missing.remove("orderBy"); assertThrows(IllegalArgumentException.class, () -> compileDedup(engine, false, source, sink, missing));
                var duplicate = new LinkedHashMap<>(config); duplicate.put("uniqueFields", List.of("id", "id")); assertThrows(IllegalArgumentException.class, () -> compileDedup(engine, false, source, sink, duplicate));
            }
            String sensitive = compileDedup(engine, false, "c_source", "c_out", dedup("KEY_FIELDS", false));
            assertTrue(sensitive.contains("PARTITION BY `group` ORDER BY")); assertFalse(sensitive.contains("LOWER(`group`)"));
            var all = new LinkedHashMap<String, Object>(); all.put("enableDeduplication", true); all.put("deduplicationStrategy", "ALL_FIELDS");
            assertTrue(compileDedup(engine, false, "c_source", "c_out", all).contains("SELECT DISTINCT"));
        }
        assertThrows(IllegalArgumentException.class, () -> compileDedup(EtlNativeSqlCompiler.Engine.FLINK, true, "c_source", "c_out", dedup("KEY_FIELDS", true)));
        var empty = dedup("KEY_FIELDS", false); empty.put("keyFields", List.of()); assertThrows(IllegalArgumentException.class, () -> compileDedup(EtlNativeSqlCompiler.Engine.SPARK, false, "c_source", "c_out", empty));
    }

    @Test void terminalSortRemainsOutermostAndScriptMaterializationKeepsItsOrderedInput() {
        var sort = node("sort", "sort-records", Map.of("sorts", order("id")));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var nodes = List.of(read("c_source"), sort, write("c_out")); var edges = List.of(edge("c_source", "sort", "main", "in"), edge("sort", "c_out", "main", "in"));
            String sql = EtlNativeSqlCompiler.compile(engine, false, nodes, edges, EtlNativeControlTransformFixtureTest::table);
            assertTrue(sql.endsWith("dh_output ORDER BY `id` ASC NULLS LAST;"), sql);
            var bridge = node("boundary", "boundary-bridge", Map.of("fields", List.of(Map.of("name", "id", "type", "Integer"))));
            assertTrue(EtlNativeSqlCompiler.compile(engine, false, List.of(read("c_source"), sort, bridge, write("c_out")),
                    List.of(edge("c_source", "sort", "main", "in"), edge("sort", "boundary", "main", "in"), edge("boundary", "c_out", "main", "in")), EtlNativeControlTransformFixtureTest::table).endsWith("dh_output ORDER BY `id` ASC NULLS LAST;"));
            var transform = node("change", "set-field-value", Map.of("rules", List.of(Map.of("sourceField", "payload", "targetField", "group"))));
            var error = assertThrows(IllegalArgumentException.class, () -> EtlNativeSqlCompiler.compile(engine, false, List.of(read("c_source"), sort, transform, write("c_out")),
                    List.of(edge("c_source", "sort", "main", "in"), edge("sort", "change", "main", "in"), edge("change", "c_out", "main", "in")), EtlNativeControlTransformFixtureTest::table));
            assertTrue(error.getMessage().contains("排序后须直接连接"));
        }
        var script = node("java", "java-script", Map.of("code", "public class Source {}", "resourceBindingId", "proof", "workerState", "BATCH", "batchPolicy", "PARTITION", "maximumRows", 100));
        var plan = EtlNativeScriptCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, 1, List.of(read("c_source"), sort, script, write("c_out")),
                List.of(edge("c_source", "sort", "main", "in"), edge("sort", "java", "main", "in"), edge("java", "c_out", "main", "in")), EtlNativeControlTransformFixtureTest::table, "", definition ->
                        new cn.iocoder.yudao.framework.etl.core.script.EtlScriptBrokerClient.Binding("https://broker.internal/v1/script", "00000000-0000-0000-0000-000000000000", "proof", EtlNativeScriptCompiler.sha(definition.source()), "1", "proof", "SPARK", "key", "cert", "ca"));
        assertTrue(plan.program().stages().getFirst().inputSql().endsWith("ORDER BY `id` ASC NULLS LAST"));
    }

    @Test void actualEditorDeduplicationPayloadsCompileAndKeepCompleteRowSchemas() {
        var fixture = ui();
        for (var engine : EtlNativeSqlCompiler.Engine.values()) for (String scenario : List.of("keyed", "wholeRow", "caseInsensitiveWholeRow")) {
            String source = scenario.equals("caseInsensitiveWholeRow") ? "d_source" : "c_source", sink = source.replace("source", "out");
            var analysis = EtlNativeSqlCompiler.analyze(engine, false, List.of(read(source), node("dedup", "data-clean", fixture.getJSONObject(scenario)), write(sink)),
                    List.of(edge(source, "dedup", "main", "in"), edge("dedup", sink, "main", "in")), EtlNativeControlTransformFixtureTest::table);
            assertEquals(TABLES.get(source), analysis.nodes().get("dedup").outputFields());
        }
    }

    @Test void generatesRealCompilerAndExpanderFixturesForBothEngines() throws Exception {
        var directory = Path.of("target/native-control"); Files.createDirectories(directory); var manifest = new ArrayList<Map<String, Object>>();
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var original = graph(); var expanded = EtlSubflowExpander.expand(original.nodes(), original.edges(), (pipeline, version) -> child(), false);
            assertEquals(1, expanded.dependencies().size()); assertEquals("7:9", expanded.dependencies().getFirst().key());
            assertTrue(expanded.nodes().stream().anyMatch(node -> node.getToolCode().equals("boundary-bridge")));
            var analysis = EtlNativeSqlCompiler.analyze(engine, false, expanded.nodes(), expanded.edges(), EtlNativeControlTransformFixtureTest::table);
            String sql = analysis.sql(); assertTrue(sql.contains("SELECT DISTINCT")); assertTrue(sql.contains("ROW_NUMBER() OVER"));
            assertEquals(TABLES.get("a_out"), analysis.nodes().get("a_out").inputFields());
            assertEquals(engine == EtlNativeSqlCompiler.Engine.FLINK, sql.startsWith("EXECUTE STATEMENT SET"));
            String name = "all-" + engine.name().toLowerCase(Locale.ROOT) + ".sql";
            Files.writeString(directory.resolve(name), setup(engine, false) + sql);
            manifest.add(Map.of("engine", engine.name(), "case", "all", "file", name, "compilerSqlSha256", DigestUtil.sha256Hex(sql),
                    "components", List.of("data-clean", "dedup-records", "value-map", "number-range", "add-constant", "set-field-value", "column-map", "column-to-row", "sort-records", "sub-pipeline"),
                    "expandedNodes", expanded.nodes().size(), "subflowDependency", expanded.dependencies().getFirst()));
            String negative = compileDedup(engine, false, "c_source", "c_out", dedup("KEY_FIELDS", true));
            String duplicate = "dedup-duplicate-" + engine.name().toLowerCase(Locale.ROOT) + ".sql";
            Files.writeString(directory.resolve(duplicate), setup(engine, true) + negative);
            manifest.add(Map.of("engine", engine.name(), "case", "dedup-duplicate", "file", duplicate, "compilerSqlSha256", DigestUtil.sha256Hex(negative), "expectedFailure", "唯一排序字段存在重复值"));
        }
        Files.writeString(directory.resolve("manifest.json"), JSONUtil.toJsonPrettyStr(manifest));
    }

    private record Graph(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) { }
    private static Graph graph() {
        var nodes = new ArrayList<EtlNodeDef>(); var edges = new ArrayList<EtlEdgeDef>();
        nodes.add(read("a_source")); nodes.add(node("call", "sub-pipeline", Map.of("pipelineId", "7", "versionId", "9", "parameters", Map.of("label", TRACE))));
        nodes.add(node("clean", "data-clean", Map.of("enableFieldRemoval", true, "fieldsToRemove", "junk", "enableFormatting", true, "formatRules", List.of(Map.of("fieldName", "name", "type", "TRIM")))));
        nodes.add(node("distinct", "data-clean", ui().getJSONObject("wholeRow")));
        nodes.add(node("map", "value-map", Map.of("sourceField", "name", "targetField", "mapped", "mappings", List.of(Map.of("from", "水🌊", "to", "江河")), "unmatched", "KEEP")));
        nodes.add(node("range", "number-range", Map.of("sourceField", "amount", "targetField", "bucket", "ranges", List.of(Map.of("min", "0", "max", "1", "value", "small"), Map.of("min", "1", "max", "20", "value", "big")), "defaultValue", "missing")));
        nodes.add(node("constant", "add-constant", Map.of("fields", List.of(Map.of("name", "marker", "fieldType", "String", "value", MARKER)))));
        nodes.add(node("set", "set-field-value", Map.of("rules", List.of(Map.of("sourceField", "mapped", "targetField", "copied"), Map.of("sourceField", "name", "targetField", "mapped")))));
        nodes.add(node("select", "column-map", Map.of("columnMappingMode", "FIELDS", "fieldSelections", List.of(Map.of("name", "id"), Map.of("name", "amount"), Map.of("name", "name", "alias", "名称"), Map.of("name", "mapped"), Map.of("name", "copied"), Map.of("name", "bucket"), Map.of("name", "marker"), Map.of("name", "trace")), "removedFields", List.of())));
        nodes.add(node("unpivot", "row-column", Map.of("mode", "COL2ROW", "unpivotFields", List.of("mapped", "copied"), "keyField", "which", "valueField", "value", "skipNull", false)));
        nodes.add(node("sort", "sort-records", Map.of("sorts", List.of(Map.of("field", "id", "order", "ASC"), Map.of("field", "which", "order", "ASC"))))); nodes.add(write("a_out"));
        edges.add(edge("a_source", "call", "main", "input")); edges.add(edge("call", "clean", "result", "in")); chain(edges, "clean", "distinct", "map", "range", "constant", "set", "select", "unpivot", "sort", "a_out");
        nodes.add(read("c_source")); nodes.add(node("key_dedup", "data-clean", ui().getJSONObject("keyed"))); nodes.add(node("key_sort", "sort-records", Map.of("sorts", order("id")))); nodes.add(write("c_out")); chain(edges, "c_source", "key_dedup", "key_sort", "c_out");
        nodes.add(read("d_source")); var folded = ui().getJSONObject("caseInsensitiveWholeRow");
        nodes.add(node("fold_dedup", "data-clean", folded)); nodes.add(node("fold_sort", "sort-records", Map.of("sorts", order("id")))); nodes.add(write("d_out")); chain(edges, "d_source", "fold_dedup", "fold_sort", "d_out");
        return new Graph(nodes, edges);
    }

    private static EtlSubflowExpander.Definition child() {
        var contract = List.of(Map.of("name", "id", "type", "Long"), Map.of("name", "amount", "type", "Decimal", "precision", 24, "scale", 4), Map.of("name", "name", "type", "String"), Map.of("name", "junk", "type", "String"));
        return new EtlSubflowExpander.Definition("7:9", DigestUtil.sha256Hex("immutable-control-fixture-v1"), List.of(
                node("in", "boundary-input", Map.of("portName", "input", "fields", contract)), node("tag", "add-constant", Map.of("fields", List.of(Map.of("name", "trace", "fieldType", "String", "value", "${params.label}")))),
                node("out", "boundary-output", Map.of("portName", "result", "fields", List.of(Map.of("name", "trace", "type", "String"))))),
                List.of(edge("in", "tag", "main", "in"), edge("tag", "out", "main", "in")), Set.of("label"));
    }
    private static cn.hutool.json.JSONObject ui() {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path fixture = root.resolve("deploy/dolphinscheduler-local/verification/components-20261102-dedup-ui-payloads.json");
            if (Files.isRegularFile(fixture)) try { return JSONUtil.parseObj(Files.readString(fixture)); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        throw new IllegalStateException("缺少实际去重编辑器保存合同");
    }
    private static LinkedHashMap<String, Object> dedup(String strategy, boolean ignoreCase) {
        var config = new LinkedHashMap<String, Object>(); config.put("enableDeduplication", true); config.put("deduplicationStrategy", strategy); config.put("ignoreCase", ignoreCase);
        if (strategy.equals("KEY_FIELDS")) config.put("keyFields", List.of("group")); config.put("orderBy", order("id")); config.put("uniqueFields", List.of("id")); return config;
    }
    private static String compileDedup(EtlNativeSqlCompiler.Engine engine, boolean streaming, String source, String sink, Map<String, Object> config) {
        return EtlNativeSqlCompiler.compile(engine, streaming, List.of(read(source), node("dedup", "data-clean", config), write(sink)), List.of(edge(source, "dedup", "main", "in"), edge("dedup", sink, "main", "in")), EtlNativeControlTransformFixtureTest::table);
    }
    private static EtlNativeSqlCompiler.Table table(Long id, String name, String schema) {
        return new EtlNativeSqlCompiler.Table(List.of(name), TABLES.get(name), ProductTypeEnum.POSTGRESQL, "public", name, true);
    }
    private static String setup(EtlNativeSqlCompiler.Engine engine, boolean duplicate) {
        String text = str(engine, "  O'Reilly  "), river = str(engine, "  水🌊  ");
        var source = new StringBuilder("CREATE TEMPORARY VIEW a_source AS SELECT * FROM (VALUES (CAST(9007199254740993 AS BIGINT),CAST(12.3400 AS DECIMAL(24,4))," + river + ",'x'),(CAST(9007199254740993 AS BIGINT),CAST(12.3400 AS DECIMAL(24,4))," + river + ",'x'),(CAST(2 AS BIGINT),CAST(0.0100 AS DECIMAL(24,4))," + text + ",'x'),(CAST(NULL AS BIGINT),CAST(NULL AS DECIMAL(24,4)),CAST(NULL AS STRING),CAST(NULL AS STRING))) AS t(id,amount,name,junk);\n");
        source.append("CREATE TEMPORARY VIEW c_source AS SELECT * FROM (VALUES ('MiXeD',1,'first'),('MiXeD',1,'first'),('mixed'," + (duplicate ? "1" : "2") + ",'second'),(CAST(NULL AS STRING),3,'null-first'),(CAST(NULL AS STRING),3,'null-first'),(CAST(NULL AS STRING),4,'null-second'),('H',5,CAST(NULL AS STRING))) AS t(`group`,id,payload);\n");
        source.append("CREATE TEMPORARY VIEW d_source AS SELECT * FROM (VALUES ('Ab',CAST(1 AS BIGINT)),('Ab',CAST(1 AS BIGINT)),('ab',CAST(1 AS BIGINT)),(CAST(NULL AS STRING),CAST(2 AS BIGINT)),(CAST(NULL AS STRING),CAST(2 AS BIGINT)),('Other',CAST(3 AS BIGINT))) AS t(id,n);\n");
        for (String table : List.of("a_out", "c_out", "d_out")) {
            String columns = String.join(", ", TABLES.get(table).entrySet().stream().map(field -> "`" + field.getKey() + "` " + field.getValue()).toList());
            source.append(engine == EtlNativeSqlCompiler.Engine.SPARK ? "CREATE TABLE `" + table + "` (" + columns + ") USING JSON OPTIONS (path '__DATAHUB_CONTROL_RESULT__/" + table + "');\n"
                    : "CREATE TEMPORARY TABLE `" + table + "` (" + columns + ") WITH ('connector'='filesystem','path'='file://__DATAHUB_CONTROL_RESULT__/" + table + "','format'='json');\n");
        }
        return (engine == EtlNativeSqlCompiler.Engine.FLINK ? "SET 'execution.runtime-mode'='batch';\nSET 'table.dml-sync'='true';\n" : "") + source;
    }
    private static String str(EtlNativeSqlCompiler.Engine engine, String value) { return engine == EtlNativeSqlCompiler.Engine.SPARK ? "DECODE(UNHEX('" + java.util.HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "'), 'UTF-8')" : "'" + value.replace("'", "''") + "'"; }
    private static List<Map<String, String>> order(String field) { return List.of(Map.of("field", field, "order", "ASC")); }
    private static LinkedHashMap<String, String> fields(String... items) { var result = new LinkedHashMap<String, String>(); for (int index = 0; index < items.length; index += 2) result.put(items[index], items[index + 1]); return result; }
    private static EtlNodeDef read(String table) { return node(table, "read-db", Map.of("databaseId", 1, "tableName", table)); }
    private static EtlNodeDef write(String table) { return node(table, "insert-db", Map.of("databaseId", 2, "tableName", table)); }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) { var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(code.equals("read-db") || code.equals("boundary-input") ? 1 : code.equals("insert-db") || code.equals("boundary-output") ? 3 : 2); node.setNodeJson(JSONUtil.toJsonStr(config)); return node; }
    private static EtlEdgeDef edge(String from, String to, String output, String input) { var edge = new EtlEdgeDef(); edge.setSourceNo(from); edge.setTargetNo(to); edge.setOutputPort(output); edge.setInputPort(input); edge.setEdgeType("DATA"); return edge; }
    private static void chain(List<EtlEdgeDef> edges, String... ids) { for (int index = 1; index < ids.length; index++) edges.add(edge(ids[index - 1], ids[index], "main", "in")); }
}
