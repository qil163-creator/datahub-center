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

class EtlNativeRowColumnV2Test {
    @Test void wideToLongPreservesIndependentTypedOutputsAndLiteralKeys() throws Exception {
        var config = config("COL2ROW"); config.put("keyField", "metric");
        config.put("unpivotMappings", List.of(Map.of("sourceField", "amount", "keyValue", "sale's", "targetField", "value"),
                Map.of("sourceField", "other", "keyValue", "cost", "targetField", "value"), Map.of("sourceField", "raw", "keyValue", "note", "targetField", "text")));
        var output = source(); output.remove("amount"); output.remove("other"); output.remove("raw"); output.put("metric", "STRING"); output.put("value", "DECIMAL(20,4)"); output.put("text", "STRING");
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var result = analyze(engine, config, output); var fields = result.nodes().get("convert").outputFields();
            assertEquals(output, fields); assertEquals(2, occurrences(result.sql(), " UNION ALL "));
            assertTrue(result.sql().contains("CAST(NULL AS DECIMAL(20,4))"), result.sql());
            artifact("unpivot-v2", engine, result.sql());
        }
        var conflict = new LinkedHashMap<>(config); conflict.put("keyField", "group_id");
        assertThrows(IllegalArgumentException.class, () -> analyze(EtlNativeSqlCompiler.Engine.SPARK, conflict, output));
    }
    @Test void longToWideExecutesIndependentAggregationsTypesFormatsAndNullSentinels() throws Exception {
        var config = config("ROW2COL"); config.put("keyField", "code"); config.put("groupFields", List.of("group_id"));
        config.put("pivotMappings", List.of(
                Map.of("targetField", "total", "sourceField", "amount", "keyValue", "sale", "type", "DECIMAL", "length", "20", "precision", "4", "aggregation", "Sum"),
                Map.of("targetField", "average", "sourceField", "other", "keyValue", "cost", "type", "DOUBLE", "aggregation", "Average"),
                Map.of("targetField", "minimum", "sourceField", "amount", "keyValue", "sale", "type", "DOUBLE", "aggregation", "Minimum"),
                Map.of("targetField", "maximum", "sourceField", "amount", "keyValue", "sale", "type", "DOUBLE", "aggregation", "Maximum"),
                Map.of("targetField", "date_value", "sourceField", "day", "keyValue", "date", "type", "DATE", "format", "yyyy-MM-dd", "nullIf", "missing", "aggregation", "Maximum")));
        var output = columns("group_id", "BIGINT", "total", "DECIMAL(20,4)", "average", "DOUBLE", "minimum", "DOUBLE", "maximum", "DOUBLE", "date_value", "TIMESTAMP");
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var result = analyze(engine, config, output); assertEquals(output, result.nodes().get("convert").outputFields());
            for (String token : List.of("SUM(CASE", "AVG(CASE", "MIN(CASE", "MAX(CASE", "TO_TIMESTAMP", "'missing'")) assertTrue(result.sql().contains(token), result.sql());
            assertFalse(result.sql().contains("ROW_NUMBER"), "显式聚合不应要求任意排序");
            artifact("pivot-aggregate-v2", engine, result.sql());
        }
    }
    @Test void noAggregationNeedsARealCompleteOrderAndSupportsSeveralKeysForOneTarget() throws Exception {
        var config = config("ROW2COL"); config.put("keyField", "code"); config.put("groupFields", List.of("group_id"));
        config.put("pivotMappings", List.of(Map.of("targetField", "last", "sourceField", "raw", "keyValue", "sale", "type", "STRING", "length", "5"),
                Map.of("targetField", "last", "sourceField", "raw", "keyValue", "cost", "type", "STRING", "length", "5")));
        var output = columns("group_id", "BIGINT", "last", "VARCHAR(5)");
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            assertThrows(IllegalArgumentException.class, () -> analyze(engine, config, output));
            config.put("orderBy", List.of(Map.of("field", "seq", "order", "ASC"))); config.put("uniqueFields", List.of("seq"));
            var result = analyze(engine, config, output);
            assertTrue(result.sql().contains("ORDER BY CASE WHEN")); assertTrue(result.sql().contains("`seq` DESC"));
            assertTrue(result.sql().contains("SUBSTRING(CAST(")); artifact("pivot-last-v2", engine, result.sql());
            config.remove("orderBy"); config.remove("uniqueFields");
        }
    }
    @Test void invalidProtocolCannotSilentlyFallBackToAnOldModeOrIgnoreBadRules() {
        var output = columns("group_id", "BIGINT", "total", "DOUBLE");
        var base = config("ROW2COL"); base.put("groupFields", List.of("group_id")); base.put("keyField", "code");
        var valid = Map.<String,Object>of("targetField", "total", "sourceField", "amount", "keyValue", "sale", "type", "DOUBLE", "aggregation", "Sum");
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            for (var mutation : List.of(Map.<String,Object>of("aggregation", "Count"), Map.<String,Object>of("sourceField", "unknown"), Map.<String,Object>of("sourceField", "group_id"), Map.<String,Object>of("type", "STRING"), Map.<String,Object>of("format", "yyyy-MM-dd"))) {
                var rule = new LinkedHashMap<>(valid); rule.putAll(mutation); var invalid = new LinkedHashMap<>(base); invalid.put("pivotMappings", List.of(rule));
                assertThrows(IllegalArgumentException.class, () -> analyze(engine, invalid, output));
            }
            var invalid = new LinkedHashMap<>(base); invalid.put("pivotMappings", List.of(valid,valid)); assertThrows(IllegalArgumentException.class, () -> analyze(engine, invalid, output));
            invalid.put("rowColumnVersion", 2.5); assertThrows(IllegalArgumentException.class, () -> analyze(engine, invalid, output));
        }
    }
    private static LinkedHashMap<String,Object> config(String mode) { var value = new LinkedHashMap<String,Object>(); value.put("mode", mode); value.put("rowColumnVersion", 2); return value; }
    private static LinkedHashMap<String,String> source() { return columns("group_id","BIGINT","seq","BIGINT","code","STRING","amount","DECIMAL(20,4)","other","DECIMAL(20,4)","raw","STRING","day","STRING","flag","BOOLEAN"); }
    private static LinkedHashMap<String,String> columns(String... values) { var result = new LinkedHashMap<String,String>(); for (int i=0;i<values.length;i+=2) result.put(values[i],values[i+1]); return result; }
    private static EtlNativeSqlCompiler.Analysis analyze(EtlNativeSqlCompiler.Engine engine, Map<String,Object> config, Map<String,String> output) {
        var read=node("source","read-db",Map.of("databaseId",1,"tableName","row_column_source")); var transform=node("convert","row-column",config); var sink=node("sink","insert-db",Map.of("databaseId",2,"tableName","row_column_target"));
        return EtlNativeSqlCompiler.analyze(engine,false,List.of(read,transform,sink),List.of(edge("source","convert"),edge("convert","sink")),(id,name,schema) -> new EtlNativeSqlCompiler.Table(List.of(name),name.equals("row_column_source")?source():output,ProductTypeEnum.POSTGRESQL,"public",name,false));
    }
    private static EtlNodeDef node(String id,String code,Map<String,Object> config) { var node=new EtlNodeDef();node.setNo(id);node.setName(id);node.setToolCode(code);node.setNodeJson(JSONUtil.toJsonStr(config));return node; }
    private static EtlEdgeDef edge(String source,String target) {var edge=new EtlEdgeDef();edge.setSourceNo(source);edge.setTargetNo(target);return edge;}
    private static int occurrences(String value,String token) { return value.split(java.util.regex.Pattern.quote(token),-1).length-1; }
    private static void artifact(String name,EtlNativeSqlCompiler.Engine engine,String sql) throws Exception {var file=Path.of("target/native-sql",name+"-"+engine.name().toLowerCase(Locale.ROOT)+".sql");Files.createDirectories(file.getParent());Files.writeString(file,sql);}
}
