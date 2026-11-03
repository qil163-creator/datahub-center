package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 独立锁定旧 ETL 语义及原生执行门禁，不通过反射复用被测方法制造期望结果。 */
class EtlNativeSqlSemanticRegressionTest {
    @Test void integralConstantsRejectFractionsAndOverflowInsteadOfCoercingValues() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            for (var entry : List.of(Map.entry("Long", "1.5"), Map.entry("Integer", "2147483648"),
                    Map.entry("Integer", "-2147483649"), Map.entry("Long", "9223372036854775808"))) {
                assertThrows(IllegalArgumentException.class,
                        () -> transform(engine, "add-constant", constants(entry.getKey(), entry.getValue())), entry.toString());
            }
            for (String value : List.of("-9223372036854775808", "9223372036854775807")) {
                String sql = transform(engine, "add-constant", constants("Long", value));
                assertTrue(sql.contains(value), sql);
                assertTrue(sql.contains("BIGINT"), "Long 必须显式保持 BIGINT 类型：" + sql);
            }
        }
    }

    @Test void duplicateValueMappingsKeepLastDefinitionAndNumericKeepIsExplicitlyUnsupported() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var config = Map.<String, Object>of("sourceField", "name", "targetField", "label", "unmatched", "KEEP",
                    "mappings", List.of(Map.of("from", "same", "to", "first-definition"),
                            Map.of("from", "same", "to", "last-definition")));
            String sql = transform(engine, "value-map", config);
            assertFalse(sql.contains("first-definition"), sql);
            assertTrue(sql.contains("last-definition"), sql);
            assertEquals(1, occurrences(sql, "WHEN "), "重复源值不能生成首条胜出的两个 CASE 分支");
            assertThrows(IllegalArgumentException.class, () -> transform(engine, "value-map",
                    Map.of("sourceField", "id", "targetField", "label", "unmatched", "KEEP",
                            "mappings", List.of(Map.of("from", "1", "to", "one")))));
        }
    }

    @Test void dataCleaningComposesFormattingInOrderAndDoesNotResurrectRemovedFields() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = transform(engine, "data-clean", Map.of("enableFormatting", true,
                    "formatRules", List.of(Map.of("fieldName", "name", "type", "TRIM"),
                            Map.of("fieldName", "name", "type", "UPPER_CASE"))));
            int upper = sql.indexOf("UPPER(");
            int trim = sql.indexOf("REGEXP_REPLACE(");
            assertTrue(upper >= 0 && trim > upper, "后续 UPPER 必须包住前一步 TRIM，而不是再次读取原字段：" + sql);
            String whitespacePattern = onlyEncodedPattern(sql);
            String cleaned = Pattern.compile(whitespacePattern).matcher(" \u00a0a\u00a0 ").replaceAll("");
            assertEquals("\u00a0A\u00a0", cleaned.toUpperCase(Locale.ROOT), "DataClean BOTH 保留 NBSP，不能偷换为 Unicode trim");

            String removed = transform(engine, "data-clean", Map.of("enableFieldRemoval", true, "fieldsToRemove", "removed",
                    "enableFormatting", true, "formatRules", List.of(Map.of("fieldName", "removed", "type", "UPPER_CASE"))));
            assertFalse(removed.contains(" AS `removed`"), "已移除字段不能被格式化规则重新创建：" + removed);
            assertFalse(removed.contains("UPPER("), "删除后的字段不应执行格式化：" + removed);
        }
    }

    @Test void fieldSplitBothUsesStringTrimWhitespaceSetAndPreservesNbsp() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = transform(engine, "field-split", Map.of("sourceField", "name", "splitType", "DELIMITER",
                    "delimiter", ",", "keepSource", true, "outputs", List.of(Map.of("name", "part", "trimType", "BOTH"))));
            String whitespacePattern = onlyEncodedPattern(sql);
            assertEquals("\u00a0station\u00a0", Pattern.compile(whitespacePattern)
                    .matcher(" \t\u00a0station\u00a0\r ").replaceAll(""));
            assertEquals("station", Pattern.compile(whitespacePattern).matcher(" \tstation\r ").replaceAll(""));
            assertTrue(sql.contains(" AS `part`"), sql);
        }
    }

    @Test void uuidIsOnlyAllowedImmediatelyBeforeExactlyOneTableOutput() {
        var read = source(); var uuid = node("uuid", "id-generator", Map.of("mode", "UUID", "targetField", "uuid"));
        var write = target("write");
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, false, List.of(read, uuid, write), chain("read", "uuid", "write"), false);
            assertEquals(1, occurrences(sql, "UUID()"), sql);

            var passthrough = node("after", "string-operation", Map.of("rules", List.of(Map.of("inputField", "name", "trim", "NONE"))));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, false,
                    List.of(read, uuid, passthrough, write), chain("read", "uuid", "after", "write"), false));

            var second = target("second"); var fanout = new ArrayList<>(chain("read", "uuid", "write"));
            fanout.add(edge("uuid", "second"));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, false, List.of(read, uuid, write, second), fanout, false));
        }
    }

    @Test void realtimeRequiresExplicitContinuousSourceButDoesNotRequireContinuousSink() {
        var nodes = List.of(source(), target("write"));
        var edges = chain("read", "write");
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.FLINK, true, nodes, edges, false));
        assertDoesNotThrow(() -> compile(EtlNativeSqlCompiler.Engine.FLINK, true, nodes, edges, true));
        assertDoesNotThrow(() -> compile(EtlNativeSqlCompiler.Engine.FLINK, false, nodes, edges, false));
    }

    private static Map<String, Object> constants(String type, String value) {
        return Map.of("fields", List.of(Map.of("name", "constant", "fieldType", type, "value", value)));
    }

    private static String transform(EtlNativeSqlCompiler.Engine engine, String code, Map<String, Object> config) {
        return compile(engine, false, List.of(source(), node("transform", code, config), target("write")),
                chain("read", "transform", "write"), false);
    }

    private static String compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, List<EtlNodeDef> nodes,
                                  List<EtlEdgeDef> edges, boolean continuousSource) {
        return EtlNativeSqlCompiler.compile(engine, streaming, nodes, edges, (id, table, schema) -> {
            var fields = new LinkedHashMap<String, String>();
            fields.put("id", "BIGINT"); fields.put("name", "VARCHAR"); fields.put("removed", "VARCHAR");
            if (id == 2L) { fields.put("constant", "BIGINT"); fields.put("label", "VARCHAR"); fields.put("part", "VARCHAR"); fields.put("uuid", "VARCHAR"); }
            return new EtlNativeSqlCompiler.Table(List.of("catalog", "schema", table), fields,
                    ProductTypeEnum.POSTGRESQL, "public", table, id == 1L && continuousSource);
        });
    }

    /** 从公开 SQL 结果解码跨引擎的 UTF-8 字面量，验证实际输出的空白字符集合。 */
    private static String onlyEncodedPattern(String sql) {
        var matcher = Pattern.compile("(?:UNHEX\\('([0-9a-f]+)'\\)|X'([0-9a-f]+)')").matcher(sql);
        var values = new ArrayList<String>();
        while (matcher.find()) values.add(new String(HexFormat.of().parseHex(matcher.group(1) == null ? matcher.group(2) : matcher.group(1)), StandardCharsets.UTF_8));
        assertEquals(1, values.size(), "该测试 SQL 应包含唯一的编码 trim 模式：" + sql);
        return values.getFirst();
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int start = 0; (start = value.indexOf(needle, start)) >= 0; start += needle.length()) count++;
        return count;
    }
    private static EtlNodeDef source() { return node("read", "read-db", Map.of("databaseId", 1, "tableName", "source")); }
    private static EtlNodeDef target(String id) { return node(id, "insert-db", Map.of("databaseId", 2, "tableName", "target")); }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) {
        var result = new EtlNodeDef(); result.setNo(id); result.setName(id); result.setToolCode(code);
        result.setNodeJson(JSONUtil.toJsonStr(config)); return result;
    }
    private static EtlEdgeDef edge(String source, String target) {
        var result = new EtlEdgeDef(); result.setSourceNo(source); result.setTargetNo(target); return result;
    }
    private static List<EtlEdgeDef> chain(String... ids) {
        var result = new ArrayList<EtlEdgeDef>();
        for (int index = 1; index < ids.length; index++) result.add(edge(ids[index - 1], ids[index]));
        return result;
    }
}
