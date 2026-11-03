package cn.iocoder.yudao.framework.quality.core.clean;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.quality.core.clean.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.core.type.TypeReference;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 四十条目录逐项具有执行语义；新增策略验证真实转换、替换、集合选择和时间边界。 */
class QdataFortyCleanRulesTest {
    static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 10, 19, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private final CleanFunctionRegistry registry = new CleanFunctionRegistry();
    @BeforeEach void fixedTime() {
        for (var kind : DateBoundaryCleanFunction.Kind.values()) registry.register(new DateBoundaryCleanFunction(kind, CLOCK));
        registry.register(new NullFillCleanFunction(NullFillCleanFunction.Kind.TIME, CLOCK));
        registry.register(new DateFillNullFunction(CLOCK));
    }
    record Example(String key, Map<String, Object> config, List<Map<String, Object>> input, List<Map<String, Object>> expected) {
        @Override public String toString() { return key; }
    }
    @ParameterizedTest(name = "{0}") @MethodSource("examples")
    void executesMissingCatalogRulesWithoutChangingInput(Example example) {
        var input = example.input().stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        var config = registry.normalizeConfig(example.key(), example.config());
        AtomicInteger checkpoints = new AtomicInteger();
        var actual = registry.applyControlled(example.key(), input, config, row -> { }, checkpoints::incrementAndGet);
        assertEquals(example.expected(), actual);
        assertEquals(example.input(), input);
        assertTrue(checkpoints.get() >= input.size());
    }
    static Stream<Example> examples() {
        List<Example> result = new ArrayList<>();
        result.add(values("STRING_TO_NUMBER", Map.of(), list("9007199254740993.1234567890123", "bad", null),
                list(new BigDecimal("9007199254740993.1234567890123"), "bad", null)));
        result.add(values("STRING_TO_DATE", Map.of("inputFormats", List.of("yyyy-MM-dd")), list("2024-02-29", "2023-02-29", null),
                list(LocalDate.of(2024, 2, 29), "2023-02-29", null)));
        result.add(values("TO_BOOLEAN", Map.of(), list("TRUE", "否", 1, "unknown", null), list(true, false, true, "unknown", null)));
        result.add(values("ZERO_PAD", Map.of("length", 4), list("42", "123456", "😀", null), list("0042", "123456", "000😀", null)));
        result.add(values("NUMBER_FILL_NULL", Map.of("value", "-1"), list(null, " ", "3.5", 0), list(new BigDecimal("-1"), new BigDecimal("-1"), "3.5", 0)));
        result.add(values("NUMBER_FILL_NULL", Map.of("mode", "MEAN", "scale", 2), list(null, "1", "2", "bad", ""),
                list(new BigDecimal("1.50"), "1", "2", "bad", new BigDecimal("1.50"))));
        result.add(values("STRING_FILL_NULL", Map.of(), list(null, " ", "hello"), list("未知", "未知", "hello")));
        result.add(values("BOOLEAN_FILL_NULL", Map.of("value", true), list(null, "", false, 0), list(true, true, false, 0)));
        var emptyRows = List.of(row("a", null, "b", " "), row("a", Double.NaN, "b", Float.NaN), row("a", 0, "b", ""));
        result.add(new Example("REMOVE_EMPTY_ROW", Map.of(), emptyRows, List.of(emptyRows.get(2))));
        result.add(values("UNIT_CONVERSION", Map.of("factor", "0.001", "scale", 3), list("9007199254740993123.123", "bad", null),
                list(new BigDecimal("9007199254740993.123"), "bad", null)));
        result.add(values("CAPITALIZE_WORDS", Map.of(), list("hELLO wORLD", "éCOLE-日本 😀TEST", null), list("Hello World", "École-日本 😀Test", null)));
        var duplicates = List.of(row("id", "a", "time", "2026-01-01", "source", "high", "phone", "", "name", "A"),
                row("id", "b", "time", "2026-01-01", "source", "low", "phone", "1", "name", "B"),
                row("id", "a", "time", "2026-10-01", "source", "low", "phone", "1", "name", "A"),
                row("id", "a", "time", "2026-03-01", "source", "high", "phone", "2", "name", "A"));
        result.add(new Example("PK_DEDUP_LATEST", Map.of("columns", List.of("id"), "timeField", "time"), duplicates, List.of(duplicates.get(2), duplicates.get(1))));
        result.add(new Example("DEDUP_BY_PRIORITY", Map.of("columns", List.of("id"), "priorityField", "source", "priorityValues", List.of("high", "low"), "timeField", "time"),
                duplicates, List.of(duplicates.get(3), duplicates.get(1))));
        result.add(new Example("DEDUP_PREFER_NON_NULL", Map.of("columns", List.of("id"), "preferredFields", List.of("phone", "name"), "timeField", "time"),
                duplicates, List.of(duplicates.get(2), duplicates.get(1))));
        result.add(values("INVALID_NUMBER_REPLACE", Map.of("min", "0", "max", "100", "invalidValues", List.of("99"), "replacement", "0"),
                list("-1", "0", "100", "99", "bad", null), list(new BigDecimal("0"), "0", "100", new BigDecimal("0"), new BigDecimal("0"), null)));
        result.add(values("INVALID_ENUM_REPLACE", Map.of("validValues", List.of("ON", "OFF"), "ignoreCase", true, "replacement", "unknown"),
                list("on", "OFF", "bad", null), list("on", "OFF", "unknown", null)));
        result.add(values("INVALID_DATE_REPLACE", Map.of("inputFormats", List.of("yyyy-MM-dd"), "replacement", "1970-01-01"),
                list("2024-02-29", "2023-02-29", "2024-01-01 trailing", null), list("2024-02-29", "1970-01-01", "1970-01-01", null)));
        result.add(values("INVALID_BOOLEAN_REPLACE", Map.of(), list("TRUE", "0", "bad", null), list("TRUE", "0", false, null)));
        result.add(values("KEYWORD_MASK", Map.of("keywords", List.of("敏感", "敏感词")), list("一条敏感词内容", "正常", null), list("一条***内容", "正常", null)));
        result.add(values("SENSITIVE_WORD_REPLACE", Map.of("keywords", List.of("bad", "new"), "replacement", "new", "ignoreCase", true),
                list("BAD bad", "other", null), list("new new", "other", null)));
        result.add(values("FIX_FUTURE_DATE", Map.of(), list("2099-01-01", "2026-10-19 12:00:00", "2000-01-01", "bad", null),
                list("2026-10-19 12:00:00", "2026-10-19 12:00:00", "2000-01-01", "bad", null)));
        result.add(values("CLAMP_FUTURE_OFFSET", Map.of("amount", 7), list("2026-10-27", "2026-10-26 12:00:00", "2026-10-20", null),
                list("2026-10-26 12:00:00", "2026-10-26 12:00:00", "2026-10-20", null)));
        result.add(values("TIME_FILL_NULL", Map.of(), list(null, " ", "2020-01-01"), list("2026-10-19 12:00:00", "2026-10-19 12:00:00", "2020-01-01")));
        result.add(values("FIX_HISTORICAL_DATE", Map.of("boundary", "2000-01-01"), list("1900-01-01", "2000-01-01", "2026-01-01", null),
                list("2000-01-01 00:00:00", "2000-01-01", "2026-01-01", null)));
        return result.stream();
    }
    @Test void allFortyCatalogStrategiesAreExecutableAndCanonicalExamplesAreIdempotent() {
        assertEquals(40, registry.listStrategies().size());
        assertEquals(23, examples().map(Example::key).distinct().count());
        registry.listStrategies().forEach(meta -> {
            Map<String, Object> example = JsonUtils.parseObject(meta.getConfigExample(), new TypeReference<>() {});
            var canonical = registry.normalizeConfig(meta.getStrategyKey(), example);
            assertEquals(canonical, registry.normalizeConfig(meta.getStrategyKey(), canonical), meta.getStrategyKey());
            var invalid = new LinkedHashMap<>(example); invalid.put("unexpected", true);
            assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig(meta.getStrategyKey(), invalid), meta.getStrategyKey());
        });
    }
    @Test void integerConversionAndFailurePoliciesNeverUseFloatingPoint() {
        assertEquals(new BigInteger("900719925474099312345"), apply("STRING_TO_NUMBER", Map.of("targetType", "INTEGER"), "900719925474099312345"));
        assertNull(apply("STRING_TO_NUMBER", Map.of("targetType", "INTEGER", "onInvalid", "NULL"), "1.5"));
        assertThrows(IllegalArgumentException.class, () -> apply("STRING_TO_NUMBER", Map.of("onInvalid", "ERROR"), "NaN"));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("TO_BOOLEAN", Map.of("trueValues", List.of("İ"), "falseValues", List.of("i"))));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("UNIT_CONVERSION", Map.of("factor", 0.001)));
    }
    @Test void affineUnitsPaddingAndTypedDateConversionHandleRealBranches() {
        assertEquals(new BigDecimal("32.00"), apply("UNIT_CONVERSION", Map.of("factor", "1.8", "offset", "32"), "0"));
        assertEquals("42000", apply("ZERO_PAD", Map.of("length", 5, "direction", "RIGHT"), "42"));
        assertEquals(LocalDateTime.of(2024, 2, 29, 12, 30), apply("STRING_TO_DATE", Map.of("targetType", "DATETIME"), "2024-02-29 12:30:00"));
        assertEquals("$1", apply("SENSITIVE_WORD_REPLACE", Map.of("keywords", List.of("a.b"), "replacement", "$1"), "a.b"));
        assertEquals("aXb", apply("KEYWORD_MASK", Map.of("keywords", List.of("😀"), "mask", "X"), "a😀b"));
    }

    @Test void combiningMarksRemainInsideTheSameCapitalizedWord() {
        assertEquals("E\u0301cole", apply("CAPITALIZE_WORDS", Map.of(), "e\u0301COLE"));
        assertEquals("A\u0903bc", apply("CAPITALIZE_WORDS", Map.of(), "a\u0903BC"));
        assertEquals("A\u20DDbc", apply("CAPITALIZE_WORDS", Map.of(), "a\u20DDBC"));
        assertEquals("\u0301Hello", apply("CAPITALIZE_WORDS", Map.of(), "\u0301hELLO"));
    }
    @Test void allBlankMeansUseTheFallbackAndFixedTimesUseTheInjectedClock() {
        assertEquals(new BigDecimal("-1"), apply("NUMBER_FILL_NULL", Map.of("mode", "MEAN", "value", "-1"), null));
        assertEquals("2000-02-29 00:00:00", apply("TIME_FILL_NULL", Map.of("mode", "FIXED", "value", "2000-02-29"), null));
        var date = registry.getFunction("DATE_FILL_NULL");
        var config = registry.normalizeConfig("DATE_FILL_NULL", Map.of("columns", List.of("value"), "fillType", "YESTERDAY", "format", "yyyy-MM-dd"));
        assertEquals("2026-10-18", date.apply(valueRows(list((Object) null)), config).getFirst().get("value"));
    }
    @Test void futureBoundaryAndCalendarOffsetsAreStrictAndDeterministic() {
        assertEquals("2020-01-01 00:00:00", apply("FIX_FUTURE_DATE", Map.of("replacement", "BOUNDARY", "boundary", "2020-01-01"), "2099-01-01"));
        assertEquals("2027-10-19 12:00:00", apply("CLAMP_FUTURE_OFFSET", Map.of("amount", 1, "unit", "YEAR"), "2030-01-01"));
        assertEquals("2026-11-19 12:00:00", apply("CLAMP_FUTURE_OFFSET", Map.of("amount", 1, "unit", "MONTH"), "2030-01-01"));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("FIX_HISTORICAL_DATE", Map.of("boundary", "2023-02-29")));
    }
    @ParameterizedTest @ValueSource(strings = {"PK_DEDUP_LATEST", "DEDUP_BY_PRIORITY", "DEDUP_PREFER_NON_NULL"})
    void newDedupStrategiesPreserveTypedKeysAndStableTies(String key) {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("columns", List.of("a", "b"), "timeField", "time"));
        if (key.equals("DEDUP_BY_PRIORITY")) config.putAll(Map.of("priorityField", "source", "priorityValues", List.of("high")));
        if (key.equals("DEDUP_PREFER_NON_NULL")) config.put("preferredFields", List.of("name"));
        var rows = List.of(row("a", "a\u0001b", "b", "c", "time", "2026-01-01", "source", "high", "name", "A"),
                row("a", "a", "b", "b\u0001c", "time", "2026-01-01", "source", "high", "name", "A"),
                row("a", null, "b", "x", "time", "bad", "source", "high", "name", "A"),
                row("a", "null", "b", "x", "time", "bad", "source", "high", "name", "A"),
                row("a", 1, "b", "x", "time", "2026-01-01", "source", "high", "name", "A"),
                row("a", "1", "b", "x", "time", "2026-01-01", "source", "high", "name", "A"));
        assertEquals(rows, registry.getFunction(key).apply(rows, registry.normalizeConfig(key, config)));
    }
    @Test void bindingAndReferencedFieldsAreIndependentOfRowScope() {
        assertFalse(registry.supportsRowTransform("NUMBER_FILL_NULL"));
        assertEquals(CleanFunction.ColumnBinding.SINGLE, registry.getColumnBinding("NUMBER_FILL_NULL"));
        assertEquals(CleanFunction.ColumnBinding.ALL, registry.getColumnBinding("REMOVE_EMPTY_ROW"));
        assertEquals(Map.of(), registry.normalizeConfig("REMOVE_EMPTY_ROW", Map.of("columns", List.of())));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("ZERO_PAD", Map.of("columns", List.of("a", "b"), "length", 4)));
        assertEquals(Set.of("source", "time"), new HashSet<>(registry.getReferencedColumns("DEDUP_BY_PRIORITY", Map.of("priorityField", "source", "priorityValues", List.of("high"), "timeField", "time"))));
    }
    @Test void sensitiveExpansionAndControlledMeanRespectResourceBudgets() {
        assertThrows(IllegalArgumentException.class, () -> apply("SENSITIVE_WORD_REPLACE", Map.of("keywords", List.of("x"), "replacement", "a".repeat(1024)), "x".repeat(1024)));
        assertThrows(IllegalArgumentException.class, () -> apply("ZERO_PAD", Map.of("length", 4), "x".repeat(65_537)));
        var config = registry.normalizeConfig("NUMBER_FILL_NULL", Map.of("columns", List.of("value"), "mode", "MEAN"));
        AtomicInteger count = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> registry.applyControlled("NUMBER_FILL_NULL", valueRows(list(null, "1", "2")), config,
                row -> { if (count.incrementAndGet() == 2) throw new IllegalStateException("预算耗尽"); }, () -> { }));
        assertEquals(2, count.get());
    }
    private Object apply(String key, Map<String, Object> parameters, Object value) {
        Map<String, Object> config = new LinkedHashMap<>(parameters); config.put("columns", List.of("value"));
        return registry.getFunction(key).apply(valueRows(list(value)), registry.normalizeConfig(key, config)).getFirst().get("value");
    }
    private static Example values(String key, Map<String, Object> parameters, List<?> input, List<?> expected) {
        Map<String, Object> config = new LinkedHashMap<>(parameters); config.put("columns", List.of("value"));
        return new Example(key, config, valueRows(input), valueRows(expected));
    }
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static List<Map<String, Object>> valueRows(List<?> values) { return values.stream().map(value -> row("value", value)).toList(); }
    private static Map<String, Object> row(Object... fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) row.put((String) fields[index], fields[index + 1]);
        return row;
    }
}
