package cn.iocoder.yudao.framework.quality.core.clean;

import cn.iocoder.yudao.framework.quality.core.clean.function.CheckExpirationFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 验证源项目十三类实际清洗逻辑及本组件保留的无损、有界契约。 */
class CleanFunctionBehaviorTest {
    private final CleanFunctionRegistry registry = new CleanFunctionRegistry();

    record Scenario(String name, String strategy, Map<String, Object> config,
                    List<Map<String, Object>> rows, List<Map<String, Object>> expected) {
        @Override public String toString() { return name; }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void executesRealRuleBranchesWithoutMutatingInputOrConfiguration(Scenario scenario) {
        List<Map<String, Object>> input = scenario.rows().stream()
                .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        List<Map<String, Object>> before = new ArrayList<>();
        input.forEach(row -> before.add(new LinkedHashMap<>(row)));
        Map<String, Object> config = Collections.unmodifiableMap(new LinkedHashMap<>(scenario.config()));
        Map<String, Object> normalized = registry.normalizeConfig(scenario.strategy(), config);
        List<Map<String, Object>> result = registry.getFunction(scenario.strategy()).apply(input,
                Collections.unmodifiableMap(normalized));
        assertEquals(scenario.expected(), result);
        assertEquals(before, input, "清洗不能原地修改输入行");
        assertEquals(scenario.config(), config, "参数规范化不能原地修改调用方配置");
        assertNotSame(input, result, "返回新集合");
    }

    static Stream<Scenario> scenarios() {
        List<Scenario> cases = new ArrayList<>();
        for (int mode = 1; mode <= 3; mode++) {
            cases.add(values("边界处理 " + mode, "WITHIN_BOUNDARY",
                    Map.of("min", "0.10", "max", "1.00", "handleType", mode),
                    Arrays.asList("-0.01", "0.10", "1.00", "1.01", "不是数字", null),
                    Arrays.asList(new BigDecimal(mode == 3 ? "1.00" : "0.10"), "0.10", "1.00",
                            new BigDecimal(mode == 2 ? "0.10" : "1.00"), "不是数字", null)));
        }
        cases.add(values("去首尾空格", "REMOVE_WHITESPACE", Map.of("handleType", 1),
                Arrays.asList(" a b ", "", null), Arrays.asList("a b", "", null)));
        cases.add(values("去全部空白", "REMOVE_WHITESPACE", Map.of("handleType", 2),
                Arrays.asList(" a\tb\n ", "", null), Arrays.asList("ab", "", null)));
        cases.add(values("正则分组替换", "SIMPLE_REPLACE",
                Map.of("regex", "(\\d{4})/(\\d{2})/(\\d{2})", "replacement", "$1-$2-$3"),
                Arrays.asList("2026/10/19", "不匹配", null), Arrays.asList("2026-10-19", "不匹配", null)));
        cases.add(values("正则替换为空", "SIMPLE_REPLACE", Map.of("regex", "[ -]", "replacement", ""),
                List.of("a-b c"), List.of("abc")));
        cases.add(values("转大写", "TO_UPPERCASE", Map.of(), Arrays.asList("aBc中国", "", null),
                Arrays.asList("ABC中国", "", null)));
        cases.add(values("转小写", "TO_LOWERCASE", Map.of(), Arrays.asList("aBc中国", "", null),
                Arrays.asList("abc中国", "", null)));
        cases.add(values("添加前缀不重复且大小写敏感", "ADD_PREFIX_SUFFIX",
                Map.of("handleType", 1, "stringValue", "P"),
                Arrays.asList("a", "Pa", "pa", null), Arrays.asList("Pa", "Pa", "Ppa", null)));
        cases.add(values("添加后缀不重复", "ADD_PREFIX_SUFFIX", Map.of("handleType", 2, "stringValue", "P"),
                Arrays.asList("a", "aP", null), Arrays.asList("aP", "aP", null)));
        cases.add(values("仅删除命中的前缀", "ADD_PREFIX_SUFFIX", Map.of("handleType", 3, "stringValue", "P"),
                Arrays.asList("Pa", "aP", "P", null), Arrays.asList("a", "aP", "", null)));
        cases.add(values("仅删除命中的后缀", "ADD_PREFIX_SUFFIX", Map.of("handleType", 4, "stringValue", "P"),
                Arrays.asList("aP", "Pa", "P", null), Arrays.asList("a", "Pa", "", null)));
        cases.add(values("枚举命中和保留未知值", "MENU_CUSTOM",
                Map.of("stringValue", List.of(Map.of("value", "1", "name", "启用"), Map.of("value", "0", "name", "停用"))),
                Arrays.asList("1", 0, "unknown", null), Arrays.asList("启用", "停用", "unknown", null)));
        cases.add(values("精确四舍五入不经过浮点数", "FIX_TO_PRECISION", Map.of("scale", 2),
                Arrays.asList("9007199254740993.125", "-1.235", "1", "不是数字", null),
                Arrays.asList("9007199254740993.13", "-1.24", "1.00", "不是数字", null)));
        cases.add(values("零位保留大整数", "FIX_TO_PRECISION", Map.of("scale", 0),
                List.of("9007199254740993.5", "-1.5"), List.of(new BigInteger("9007199254740994"), new BigInteger("-2"))));
        cases.add(values("源参数名先规范为scale", "FIX_TO_PRECISION", Map.of("stringValue", 3),
                List.of("1.2345"), List.of("1.235")));
        cases.add(values("日期尝试后续格式且保留非法日历", "DATE_FORMAT_STD",
                Map.of("targetFormat", "yyyy-MM-dd", "inputFormats", List.of("yyyyMMdd", "yyyy/MM/dd", "yyyy-MM-dd")),
                Arrays.asList("20240229", "2024/02/29", "2023-02-29", "2024-02-30", "2024-02-29extra", null),
                Arrays.asList("2024-02-29", "2024-02-29", "2023-02-29", "2024-02-30", "2024-02-29extra", null)));
        cases.add(values("日期原生类型", "DATE_FORMAT_STD", Map.of("targetFormat", "yyyy/MM/dd"),
                List.of(LocalDate.of(2024, 2, 29), LocalDateTime.of(2026, 10, 19, 1, 2)),
                List.of("2024/02/29", "2026/10/19")));
        cases.add(values("从头按Unicode字符截断", "STRING_SUBSTR", Map.of("maxLength", 2, "direction", 1),
                Arrays.asList("A😀B", "😀B", "", null), Arrays.asList("A😀", "😀B", "", null)));
        cases.add(values("从尾按Unicode字符截断", "STRING_SUBSTR", Map.of("maxLength", 2, "direction", 2),
                Arrays.asList("A😀B", "😀B", "", null), Arrays.asList("😀B", "😀B", "", null)));
        List<Map<String, Object>> emptyRows = List.of(row("a", null, "b", " "), row("a", Double.NaN, "b", Float.NaN),
                row("a", 0, "b", null), row("a", "NaN", "b", ""));
        cases.add(new Scenario("删除全空字段组合且保留零和文本NaN", "REMOVE_EMPTY_COMBINATION",
                Map.of("columns", List.of("a", "b")), emptyRows, emptyRows.subList(2, 4)));
        List<Map<String, Object>> duplicateRows = List.of(row("id", "a", "updated", 1, "marker", "old"),
                row("id", "b", "updated", 5, "marker", "other"), row("id", "a", "updated", 2, "marker", "new"),
                row("id", "a", "updated", 2, "marker", "tie"));
        cases.add(new Scenario("组合去重保留首次出现且输出组顺序稳定", "KEEP_LATEST_OR_FIRST",
                Map.of("columns", List.of("id")), duplicateRows, duplicateRows.subList(0, 2)));
        cases.add(new Scenario("组合去重排序取最新且同值保留首行", "KEEP_LATEST_OR_FIRST",
                Map.of("columns", List.of("id", "updated"), "stringValue", List.of(Map.of("columns", "updated", "type", 0, "sort", 1))),
                duplicateRows, List.of(duplicateRows.get(2), duplicateRows.get(1))));
        cases.add(new Scenario("组合去重排序取最早", "KEEP_LATEST_OR_FIRST",
                Map.of("columns", List.of("id", "updated"), "stringValue", List.of(Map.of("columns", "updated", "type", 1, "sort", 1))),
                duplicateRows, duplicateRows.subList(0, 2)));
        List<Map<String, Object>> expiredRows = List.of(row("value", "2026-10-18", "status", "原值"),
                row("value", "2026-10-19", "status", "原值"), row("value", "2026-10-20", "status", "原值"),
                row("value", "2026-02-30", "status", "原值"), row("value", null, "status", "原值"));
        cases.add(new Scenario("过期早于固定日期标记不包含边界", "CHECK_EXPIRATION",
                Map.of("columns", List.of("value"), "dataRange", 1, "dataRangeType", 1, "dataRangeValue", "2026-10-19",
                        "handleType", 0, "handleColumns", "status", "handleValue", "过期"),
                expiredRows, List.of(row("value", "2026-10-18", "status", "过期"), expiredRows.get(1), expiredRows.get(2), expiredRows.get(3), expiredRows.get(4))));
        cases.add(new Scenario("过期晚于固定日期删除不包含边界", "CHECK_EXPIRATION",
                Map.of("columns", List.of("value"), "dataRange", 1, "dataRangeType", 2, "dataRangeValue", "2026-10-19", "handleType", 1),
                expiredRows, List.of(expiredRows.get(0), expiredRows.get(1), expiredRows.get(3), expiredRows.get(4))));
        return cases.stream();
    }

    @Test
    void completeSourceRuleMatrixIsCovered() {
        assertEquals(Set.of("WITHIN_BOUNDARY", "REMOVE_WHITESPACE", "SIMPLE_REPLACE", "REMOVE_EMPTY_COMBINATION",
                "TO_UPPERCASE", "TO_LOWERCASE", "ADD_PREFIX_SUFFIX", "MENU_CUSTOM", "KEEP_LATEST_OR_FIRST",
                "CHECK_EXPIRATION", "FIX_TO_PRECISION", "DATE_FORMAT_STD", "STRING_SUBSTR"),
                scenarios().map(Scenario::strategy).collect(java.util.stream.Collectors.toSet()));
    }

    @ParameterizedTest
    @MethodSource("dedupStrategies")
    void compositeKeysPreserveNullTypesAndEmbeddedSeparators(String strategy) {
        List<Map<String, Object>> rows = List.of(row("a", "a\u0001b", "b", "c", "id", 1),
                row("a", "a", "b", "b\u0001c", "id", 2), row("a", null, "b", "x", "id", 3),
                row("a", "null", "b", "x", "id", 4), row("a", 1, "b", "x", "id", 5),
                row("a", "1", "b", "x", "id", 6), row("a", 1L, "b", "x", "id", 7),
                row("a", 1, "b", "x", "id", 8));
        Map<String, Object> config = registry.normalizeConfig(strategy, Map.of("columns", List.of("a", "b")));
        assertEquals(rows.subList(0, 7), registry.getFunction(strategy).apply(rows, config));
    }

    static Stream<String> dedupStrategies() { return Stream.of("PK_DEDUP_FIRST", "KEEP_LATEST_OR_FIRST"); }

    @Test
    void sortingUsesDeclaredPriorityAndSparkNullOrdering() {
        List<Map<String, Object>> rows = List.of(row("id", "a", "time", null, "rank", 1),
                row("id", "a", "time", 2, "rank", 5), row("id", "a", "time", 2, "rank", 3));
        var function = registry.getFunction("KEEP_LATEST_OR_FIRST");
        Map<String, Object> config = registry.normalizeConfig("KEEP_LATEST_OR_FIRST", Map.of("columns", List.of("id"),
                "stringValue", List.of(Map.of("columns", "rank", "type", 1, "sort", 2),
                        Map.of("columns", "time", "type", 0, "sort", 1))));
        assertEquals(List.of(rows.get(2)), function.apply(rows, config));
        config = registry.normalizeConfig("KEEP_LATEST_OR_FIRST", Map.of("columns", List.of("id"),
                "stringValue", List.of(Map.of("columns", "time", "type", 1, "sort", 1))));
        assertEquals(List.of(rows.getFirst()), function.apply(rows, config));
    }

    @Test
    void expiryUsesCalendarDaysMonthsAndYearsWithOneFixedBatchDate() {
        var function = new CheckExpirationFunction(Clock.fixed(Instant.parse("2024-03-31T12:00:00Z"), ZoneOffset.UTC));
        List<String> boundaries = List.of("2024-03-30", "2024-02-29", "2023-03-31");
        for (int unit = 1; unit <= 3; unit++) {
            LocalDate boundary = LocalDate.parse(boundaries.get(unit - 1));
            var rows = valueRows(List.of(boundary.minusDays(1).toString(), boundary.toString(), boundary.plusDays(1).toString()));
            Map<String, Object> config = function.normalizeConfig(Map.of("columns", List.of("value"), "dataRange", 0,
                    "dataRangeType", unit, "dataRangeValue", 1, "handleType", 1));
            assertEquals(rows.subList(1, 3), function.apply(rows, config));
        }
    }

    @Test
    void timestampSecondsAndMillisMatchAndMalformedTimestampTextStaysUnchanged() {
        Object seconds = "1709164800";
        Object millis = "1709164800000";
        String expected = Instant.ofEpochSecond(1709164800L).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        var rows = valueRows(List.of(seconds, millis, 1709164800L, 1709164800000L, "x1709164800", "1709164800 trailing"));
        var config = registry.normalizeConfig("DATE_FORMAT_STD", Map.of("columns", List.of("value"),
                "targetFormat", "yyyy-MM-dd HH:mm:ss", "inputFormats", List.of("timestamp")));
        assertEquals(valueRows(List.of(expected, expected, expected, expected, "x1709164800", "1709164800 trailing")),
                registry.getFunction("DATE_FORMAT_STD").apply(rows, config));
    }

    @Test
    void caseTransformsDoNotDependOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(valueRows(List.of("I")), registry.getFunction("TO_UPPERCASE").apply(valueRows(List.of("i")), Map.of("columns", List.of("value"))));
            assertEquals(valueRows(List.of("i")), registry.getFunction("TO_LOWERCASE").apply(valueRows(List.of("I")), Map.of("columns", List.of("value"))));
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void strictConfigRejectsDangerousOrAmbiguousParameters() {
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("WITHIN_BOUNDARY", Map.of("min", "2", "max", "1")));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("FIX_TO_PRECISION", Map.of("scale", -1)));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("FIX_TO_PRECISION", Map.of("scale", 13)));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("MENU_CUSTOM", Map.of("stringValue", List.of(
                Map.of("value", "x", "name", "a"), Map.of("value", "x", "name", "b")))));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("SIMPLE_REPLACE", Map.of("regex", "[")));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("CHECK_EXPIRATION",
                Map.of("dataRange", 1, "dataRangeValue", "2026-02-30", "handleType", 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("CHECK_EXPIRATION", Map.of("handleType", 0)));
    }

    @Test
    void rowCapabilityDefaultsConservativelyAndBuiltinsDeclareTheirBoundary() {
        Set<String> rowSet = Set.of("REMOVE_EMPTY_COMBINATION", "PK_DEDUP_FIRST", "KEEP_LATEST_OR_FIRST", "CHECK_EXPIRATION",
                "NUMBER_FILL_NULL", "REMOVE_EMPTY_ROW", "PK_DEDUP_LATEST", "DEDUP_BY_PRIORITY", "DEDUP_PREFER_NON_NULL");
        registry.listStrategies().forEach(meta -> assertEquals(!rowSet.contains(meta.getStrategyKey()),
                registry.supportsRowTransform(meta.getStrategyKey()), meta.getStrategyKey()));
        assertFalse(registry.supportsRowTransform("unknown"));
        assertFalse(registry.supportsRowTransform(null));
        CleanFunction extension = new CleanFunction() {
            public String getStrategyKey() { return "extension"; }
            public String getStrategyName() { return "扩展"; }
            public String getConfigExample() { return "{}"; }
            public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) { return rows; }
        };
        registry.register(extension);
        assertEquals(CleanFunction.ExecutionScope.ROW_SET, extension.executionScope());
        assertFalse(registry.supportsRowTransform("extension"));
    }

    private static Scenario values(String name, String strategy, Map<String, Object> parameters,
                                   List<?> input, List<?> output) {
        Map<String, Object> config = new LinkedHashMap<>(parameters);
        config.put("columns", List.of("value"));
        return new Scenario(name, strategy, config, valueRows(input), valueRows(output));
    }

    private static List<Map<String, Object>> valueRows(List<?> values) {
        return values.stream().map(value -> row("value", value, "untouched", "保留")).toList();
    }

    private static Map<String, Object> row(Object... fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) row.put((String) fields[index], fields[index + 1]);
        return row;
    }
}
