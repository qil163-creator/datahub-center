package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeStreamingWindowCompilerTest {
    @Test void dedupAndPivotUseFormalCompilerAndRejectMissingEnvironmentOrWindow() throws Exception {
        var dedup = EtlNativeStreamingWindowTest.config().set("enableDeduplication", true);
        var fields = source();
        var analysis = compile("data-clean", dedup, fields, Set.of("DH_WINDOW_PICK"));
        assertTrue(analysis.sql().contains("DH_WINDOW_PICK")); assertFalse(analysis.sql().contains("ROW_NUMBER"));
        assertEquals(fields, analysis.nodes().get("convert").outputFields()); artifact("streaming-dedup", analysis.sql());
        assertThrows(IllegalArgumentException.class, () -> compile("data-clean", dedup, fields, Set.of()));
        var absent = new JSONObject(dedup); absent.remove("streamingWindow");
        assertThrows(IllegalArgumentException.class, () -> compile("data-clean", absent, fields, Set.of("DH_WINDOW_PICK")));
        var pivot = new JSONObject().set("rowColumnVersion", 2).set("mode", "ROW2COL").set("streamingWindow", EtlNativeStreamingWindowTest.window())
                .set("keyField", "key").set("groupFields", List.of("group_id"))
                .set("uniqueFields", List.of("seq")).set("orderBy", List.of(Map.of("field", "seq", "order", "ASC")))
                .set("pivotMappings", List.of(
                        Map.of("targetField", "total", "sourceField", "value", "keyValue", "a", "type", "DECIMAL", "length", "38", "precision", "18", "aggregation", "Sum"),
                        Map.of("targetField", "average", "sourceField", "value", "keyValue", "a", "type", "DOUBLE", "aggregation", "Average"),
                        Map.of("targetField", "minimum", "sourceField", "value", "keyValue", "a", "type", "DOUBLE", "aggregation", "Minimum"),
                        Map.of("targetField", "maximum", "sourceField", "value", "keyValue", "a", "type", "DOUBLE", "aggregation", "Maximum"),
                        Map.of("targetField", "last", "sourceField", "text", "keyValue", "a", "type", "STRING"),
                        Map.of("targetField", "last", "sourceField", "text", "keyValue", "b", "type", "STRING"),
                        Map.of("targetField", "day", "sourceField", "text", "keyValue", "day", "type", "DATE", "format", "yyyy-MM-dd", "nullIf", "missing", "aggregation", "Maximum")));
        var output = new LinkedHashMap<String, String>(); output.put("group_id", "BIGINT"); output.put("total", "DECIMAL(38,18)"); output.put("average", "DOUBLE");
        output.put("minimum", "DOUBLE"); output.put("maximum", "DOUBLE"); output.put("last", "STRING"); output.put("day", "TIMESTAMP");
        analysis = compile("row-column", pivot, output, Set.of("DH_WINDOW_PICK"));
        for (String token : List.of("SUM(CASE", "AVG(CASE", "MIN(CASE", "MAX(CASE", "FILTER (WHERE", "TO_TIMESTAMP", "1|L|", "__dh_window_budget")) assertTrue(analysis.sql().contains(token), analysis.sql());
        assertEquals(output, analysis.nodes().get("convert").outputFields()); artifact("streaming-pivot", analysis.sql());
        assertTrue(analysis.sql().contains("1|F|1000|1048576|1:A:N|1"), "窗口组预算同时核验完整unique合同");
    }
    private static LinkedHashMap<String, String> source() {
        var fields = EtlNativeStreamingWindowTest.fields(); fields.put("group_id", "BIGINT"); fields.put("text", "STRING"); return fields;
    }
    private static EtlNativeSqlCompiler.Analysis compile(String code, JSONObject config, Map<String, String> target, Set<String> functions) {
        var read = node("source", "read-db", new JSONObject().set("databaseId", 1).set("tableName", "window_source"));
        var transform = node("convert", code, config); var write = node("sink", "insert-db", new JSONObject().set("databaseId", 2).set("tableName", "window_target"));
        return EtlNativeSqlCompiler.analyze(EtlNativeSqlCompiler.Engine.FLINK, true, List.of(read, transform, write), List.of(edge("source", "convert"), edge("convert", "sink")),
                EtlNativeSqlCompiler.withManagedFunctions((id, name, schema) -> new EtlNativeSqlCompiler.Table(List.of(name), id == 1L ? source() : target, ProductTypeEnum.POSTGRESQL, "public", name, id == 1L), functions));
    }
    private static EtlNodeDef node(String id, String code, JSONObject config) { var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node; }
    private static EtlEdgeDef edge(String source, String target) { var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); return edge; }
    private static void artifact(String name, String sql) throws Exception { var path = Path.of("target/native-sql", name + ".sql"); Files.createDirectories(path.getParent()); Files.writeString(path, sql); }
}
