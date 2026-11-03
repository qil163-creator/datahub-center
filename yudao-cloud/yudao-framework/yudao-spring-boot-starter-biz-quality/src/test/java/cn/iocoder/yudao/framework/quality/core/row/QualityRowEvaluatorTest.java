package cn.iocoder.yudao.framework.quality.core.row;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.quality.config.YudaoQualityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class QualityRowEvaluatorTest {
    private final QualityRowEvaluator evaluator = new QualityRowEvaluator();

    @Test
    void nullEmptyWhitespaceAndMissingFieldsHaveDifferentSemantics() {
        var plan = evaluator.compile(List.of(rule("required", "NON_NULL", Map.of("ignoreNullValue", true)),
                rule("optionalLength", "LENGTH", Map.of("minLength", 1, "ignoreNullValue", true))));
        Map<String, Object> row = new HashMap<>(); row.put("value", null);
        assertEquals(List.of("NULL_VALUE"), errors(plan.evaluate(row)));
        assertEquals(List.of("EMPTY_VALUE", "LENGTH_OUT_OF_RANGE"), errors(plan.evaluate(Map.of("value", ""))));
        assertTrue(plan.evaluate(Map.of("value", " ")).isEmpty());
        assertEquals(List.of("FIELD_MISSING", "FIELD_MISSING"), errors(plan.evaluate(Map.of())));
        assertTrue(evaluator.compile(List.of(rule("required", "NON_NULL", Map.of("allowBlank", true))))
                .evaluate(Map.of("value", "")).isEmpty());
    }

    @Test
    void stringLengthUsesUnicodeCodePointsWithoutTrimming() {
        var plan = evaluator.compile(List.of(rule("length", "LENGTH_VALIDATION", Map.of("minLength", 1, "maxLength", 1))));
        assertTrue(plan.evaluate(Map.of("value", "😀")).isEmpty());
        assertFalse(plan.evaluate(Map.of("value", "😀 ")).isEmpty());
    }

    @Test
    void decimalsUseExactInclusiveAndExclusiveBounds() {
        var plan = evaluator.compile(List.of(rule("range", "NUMERIC_RANGE", Map.of(
                "minValue", "9007199254740993.01", "maxValue", "9007199254740993.03", "includeMin", false))));
        assertTrue(plan.evaluate(Map.of("value", new BigDecimal("9007199254740993.02"))).isEmpty());
        assertEquals(List.of("NUMBER_OUT_OF_RANGE"), errors(plan.evaluate(Map.of("value", "9007199254740993.01"))));
        assertTrue(plan.evaluate(Map.of("value", "9007199254740993.03")).isEmpty());
        assertEquals(List.of("INVALID_NUMBER"), errors(plan.evaluate(Map.of("value", "not-a-number"))));
        assertEquals(List.of("INVALID_NUMBER"), errors(plan.evaluate(Map.of("value", Double.NaN))));
    }

    @Test
    void enumPlanFreezesValuesAndComparisonPolicy() {
        List<Object> values = new ArrayList<>(List.of("Alpha"));
        Map<String, Object> config = new HashMap<>(Map.of("validValues", values, "ignoreCase", true));
        var plan = evaluator.compile(List.of(rule("enum", "ENUM", config)));
        values.clear(); values.add("replacement"); config.put("ignoreCase", false);
        assertTrue(plan.evaluate(Map.of("value", "alpha")).isEmpty());
        assertEquals(List.of("ENUM_MISMATCH"), errors(plan.evaluate(Map.of("value", "replacement"))));
        assertEquals(List.of("ENUM_MISMATCH"), errors(plan.evaluate(Map.of("value", " alpha "))));
    }

    @Test
    void regexUsesSearchSemanticsAndBudgetFailuresAreNotDataViolations() {
        var search = evaluator.compile(List.of(rule("regex", "REGEX", Map.of("regex", "[0-9]{3}"))));
        assertTrue(search.evaluate(Map.of("value", "prefix123suffix")).isEmpty());
        assertEquals(List.of("REGEX_MISMATCH"), errors(search.evaluate(Map.of("value", "prefix"))));
        var tooLong = assertThrows(QualityRowEvaluationException.class,
                () -> search.evaluate(Map.of("value", "a".repeat(4097))));
        assertEquals("REGEX_INPUT_LIMIT", tooLong.getErrorCode());
        var hostile = evaluator.compile(List.of(rule("regex", "REGEX", Map.of("regex", "^(a+)+$"))));
        var exhausted = assertThrows(QualityRowEvaluationException.class,
                () -> hostile.evaluate(Map.of("value", "a".repeat(1024) + "!")));
        assertEquals("REGEX_WORK_LIMIT", exhausted.getErrorCode());
        assertEquals("regex", exhausted.getRuleCode());
    }

    @Test
    void invalidPlansFailBeforeAnyRowIsProcessed() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.compile(List.of()));
        for (QualityRowRule rule : List.of(
                rule("x", "UNIQUE_VALIDATION", Map.of()),
                rule("x", "LENGTH", Map.of()),
                rule("x", "LENGTH", Map.of("minLength", 1.5)),
                rule("x", "LENGTH", Map.of("minLength", 3, "maxLength", 2)),
                rule("x", "ENUM", Map.of("validValues", List.of())),
                rule("x", "NUMERIC_RANGE", Map.of("minValue", 2, "maxValue", 1)),
                rule("x", "NUMERIC_RANGE", Map.of("minValue", 1, "maxValue", 1, "includeMin", false)),
                rule("x", "REGEX", Map.of("regex", "[")),
                rule("x", "REGEX", Map.of("regex", "x".repeat(513))),
                rule("x", "NON_NULL", Map.of("allowBlank", "yes")),
                rule("x", "NON_NULL", Map.of("unexpectedOption", true)))) {
            assertThrows(IllegalArgumentException.class, () -> evaluator.compile(List.of(rule)), rule.toString());
        }
        var duplicate = rule("same", "NON_NULL", Map.of());
        assertThrows(IllegalArgumentException.class, () -> evaluator.compile(List.of(duplicate, duplicate)));
        assertThrows(IllegalArgumentException.class, () -> evaluator.compile(List.of(
                new QualityRowRule("s", "NON_NULL", "value", Map.of(), "FATAL"))));
    }

    @Test
    void violationsContainEveryRuleAndSeverityButNeverSensitiveValues() {
        var plan = evaluator.compile(List.of(new QualityRowRule("length", "LENGTH", "value", Map.of("maxLength", 1), "WARN"),
                new QualityRowRule("enum", "ENUM", "value", Map.of("validValues", List.of("ok")), "ERROR")));
        String sensitiveValue = "sample-private-value";
        var result = plan.evaluate(Map.of("value", sensitiveValue));
        assertEquals(List.of("WARN", "ERROR"), result.stream().map(QualityViolation::severity).toList());
        assertFalse(JsonUtils.toJsonString(result).contains(sensitiveValue));
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.getFirst()));
        var nested = assertThrows(QualityRowEvaluationException.class, () -> plan.evaluate(Map.of("value", Map.of("nested", 1))));
        assertEquals("INVALID_VALUE_TYPE", nested.getErrorCode());
    }

    @Test
    void compiledPlanIsReusableAcrossThreadsWithoutMutatingRows() throws Exception {
        var plan = evaluator.compile(List.of(rule("regex", "REGEX", Map.of("regex", "^[0-9]+$"))));
        Map<String, Object> row = Map.of("value", "123");
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<List<QualityViolation>>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) futures.add(executor.submit(() -> plan.evaluate(row)));
            for (var future : futures) assertTrue(future.get().isEmpty());
        }
        assertEquals(Map.of("value", "123"), row);
    }

    @Test
    void autoConfigurationAllowsApplicationOverrideAndRuleJsonRoundTrips() {
        try (var context = new AnnotationConfigApplicationContext(YudaoQualityAutoConfiguration.class)) {
            assertNotNull(context.getBean(QualityRowEvaluator.class));
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            QualityRowEvaluator custom = new QualityRowEvaluator();
            context.registerBean(QualityRowEvaluator.class, () -> custom);
            context.register(YudaoQualityAutoConfiguration.class); context.refresh();
            assertSame(custom, context.getBean(QualityRowEvaluator.class));
        }
        QualityRowRule rule = rule("range", "NUMERIC_RANGE", Map.of("minValue", "9007199254740993.01"));
        String json = JsonUtils.toJsonString(rule);
        assertTrue(json.contains("\"9007199254740993.01\""));
        QualityRowRule restored = JsonUtils.parseObject(json, QualityRowRule.class);
        assertTrue(evaluator.compile(List.of(restored)).evaluate(Map.of("value", "9007199254740993.02")).isEmpty());
    }

    private static QualityRowRule rule(String code, String strategy, Map<String, Object> config) {
        return new QualityRowRule(code, strategy, "value", config, "ERROR");
    }
    private static List<String> errors(List<QualityViolation> violations) {
        return violations.stream().map(QualityViolation::errorCode).toList();
    }
}
