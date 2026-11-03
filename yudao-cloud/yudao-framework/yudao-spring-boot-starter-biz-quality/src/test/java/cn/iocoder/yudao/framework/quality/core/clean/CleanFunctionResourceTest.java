package cn.iocoder.yudao.framework.quality.core.clean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Time;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CleanFunctionResourceTest {
    private final CleanFunctionRegistry registry = new CleanFunctionRegistry();

    @ParameterizedTest
    @ValueSource(strings = {"SIMPLE_REPLACE", "VALUE_REPLACE"})
    void catastrophicBacktrackingFailsSynchronouslyWithinTheWorkBudget(String strategy) {
        var function = registry.getFunction(strategy);
        var config = registry.normalizeConfig(strategy, Map.of("columns", List.of("value"),
                "regex", "(a+)+$", "replacement", "x"));
        assertTimeout(Duration.ofSeconds(2), () -> {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> function.apply(rows("a".repeat(2000) + "!"), config));
            assertTrue(failure.getMessage().contains("工作量预算"), failure.getMessage());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIMPLE_REPLACE", "VALUE_REPLACE"})
    void interruptionFailsWithoutClearingTheThreadFlagOrMutatingInput(String strategy) {
        var input = rows("abc");
        var config = registry.normalizeConfig(strategy, Map.of("columns", List.of("value"),
                "regex", "abc", "replacement", "x"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalArgumentException.class, () -> registry.getFunction(strategy).apply(input, config));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(rows("abc"), input);
        } finally { Thread.interrupted(); }
    }

    @Test
    void rejectsCaptureGroupAmplificationBeforeBuildingTheOversizedReplacement() {
        var config = registry.normalizeConfig("SIMPLE_REPLACE", Map.of("columns", List.of("value"),
                "regex", "(.*)", "replacement", "$1".repeat(512)));
        assertTimeout(Duration.ofSeconds(2), () -> {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> registry.getFunction("SIMPLE_REPLACE").apply(rows("x".repeat(16_384)), config));
            assertTrue(failure.getMessage().contains("单值输出"), failure.getMessage());
        });
    }

    @Test
    void exactSingleValueLimitSucceedsButTheWholeBatchHasAnIndependentBudget() {
        var function = registry.getFunction("SIMPLE_REPLACE");
        var config = registry.normalizeConfig("SIMPLE_REPLACE", Map.of("columns", List.of("value"),
                "regex", "(.*)", "replacement", "$1".repeat(8)));
        String source = "x".repeat(CleanRegexSupport.MAX_INPUT_CHARACTERS);
        Object output = function.apply(rows(source), config).getFirst().get("value");
        assertEquals(CleanRegexSupport.MAX_OUTPUT_CHARACTERS, output.toString().length());
        assertEquals("x".repeat(CleanRegexSupport.MAX_OUTPUT_CHARACTERS), output);
        List<Map<String, Object>> input = Collections.nCopies(33, Map.of("value", source));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> function.apply(input, config));
        assertTrue(failure.getMessage().contains("本批次字符预算"), failure.getMessage());
        assertEquals(source, input.getFirst().get("value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIMPLE_REPLACE", "VALUE_REPLACE"})
    void oversizedInputIsRejectedEvenWhenThereWouldBeNoMatch(String strategy) {
        var config = registry.normalizeConfig(strategy, Map.of("columns", List.of("value"), "regex", "z", "replacement", ""));
        assertThrows(IllegalArgumentException.class, () -> registry.getFunction(strategy).apply(
                rows("a".repeat(CleanRegexSupport.MAX_INPUT_CHARACTERS + 1)), config));
    }

    record Replacement(String regex, String input, String replacement) { }

    static Stream<Replacement> replacements() {
        return Stream.of(new Replacement("(a)(b)?", "a ab", "$0:$1:$2"),
                new Replacement("(a)", "a", "$10/$01/$00"),
                new Replacement("(?<word>[a-z]+)", "abc 123 def", "${word}:$0"),
                new Replacement("(a)", "a", "\\$1\\\\"),
                new Replacement("(?=.)", "😀ab", "-"),
                new Replacement("(x)?(a)", "a xa", "$1$2"),
                new Replacement("z", "no match", "$missing"),
                new Replacement("a", "a", ""));
    }

    @ParameterizedTest
    @MethodSource("replacements")
    void boundedReplacementPreservesJavaGroupsEscapingAndZeroWidthSemantics(Replacement example) {
        String expected = Pattern.compile(example.regex()).matcher(example.input()).replaceAll(example.replacement());
        String actual = CleanRegexSupport.replaceAll(Pattern.compile(example.regex()), example.input(),
                example.replacement(), new CleanRegexSupport.OutputBudget());
        assertEquals(expected, actual);
    }

    @ParameterizedTest
    @ValueSource(strings = {"$9", "${missing}", "${bad_name}", "${word", "$", "\\"})
    void invalidReplacementIsRejectedOnlyForAnActualMatch(String replacement) {
        assertThrows(RuntimeException.class, () -> CleanRegexSupport.replaceAll(Pattern.compile("(a)"), "a",
                replacement, new CleanRegexSupport.OutputBudget()));
        assertEquals("b", CleanRegexSupport.replaceAll(Pattern.compile("(a)"), "b", replacement,
                new CleanRegexSupport.OutputBudget()));
    }

    @Test
    void sqlTimeIsPreservedWhileSqlDateAndTimestampRemainSupported() {
        Time time = Time.valueOf("12:34:56");
        var config = registry.normalizeConfig("DATE_FORMAT_STD", Map.of("columns", List.of("value"), "targetFormat", "yyyy-MM-dd"));
        var result = registry.getFunction("DATE_FORMAT_STD").apply(rows(time,
                java.sql.Date.valueOf("2024-02-29"), java.sql.Timestamp.valueOf("2026-10-19 12:34:56")), config);
        assertSame(time, result.getFirst().get("value"));
        assertEquals("2024-02-29", result.get(1).get("value"));
        assertEquals("2026-10-19", result.get(2).get("value"));
    }

    @Test
    void controlledMappingStopsAtTheFirstBudgetFailureAndRestoresTheScope() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger retained = new AtomicInteger();
        var input = rows(" a ", " b ", " c ");
        var config = registry.normalizeConfig("REMOVE_WHITESPACE", Map.of("columns", List.of("value")));
        assertThrows(IllegalStateException.class, () -> registry.applyControlled("REMOVE_WHITESPACE", input, config,
                row -> { if (retained.incrementAndGet() == 2) throw new IllegalStateException("测试预算耗尽"); },
                checks::incrementAndGet));
        assertEquals(2, retained.get());
        assertTrue(checks.get() >= 3);
        assertEquals(rows(" a ", " b ", " c "), input);
        assertEquals(rows("a"), registry.getFunction("REMOVE_WHITESPACE").apply(rows(" a "), config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PK_DEDUP_FIRST", "KEEP_LATEST_OR_FIRST", "REMOVE_EMPTY_COMBINATION", "CHECK_EXPIRATION"})
    void controlledSetRulesPriceRetainedRowsOnceAndCheckCancellation(String strategy) {
        var input = rows("2026-10-19", "2026-10-19", "2026-10-20");
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of("columns", List.of("value")));
        if ("CHECK_EXPIRATION".equals(strategy)) parameters.putAll(Map.of("dataRange", 1, "dataRangeValue", "2026-10-19", "handleType", 1));
        Map<String, Object> config = registry.normalizeConfig(strategy, parameters);
        AtomicInteger retained = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        var result = registry.applyControlled(strategy, input, config, row -> retained.incrementAndGet(), checks::incrementAndGet);
        assertEquals(result.size(), retained.get());
        assertTrue(checks.get() >= input.size());
        assertThrows(IllegalStateException.class, () -> registry.applyControlled(strategy, input, config,
                row -> { }, () -> { throw new IllegalStateException("测试取消"); }));
    }

    private static List<Map<String, Object>> rows(Object... values) {
        return Arrays.stream(values).map(value -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", value);
            return row;
        }).toList();
    }
}
