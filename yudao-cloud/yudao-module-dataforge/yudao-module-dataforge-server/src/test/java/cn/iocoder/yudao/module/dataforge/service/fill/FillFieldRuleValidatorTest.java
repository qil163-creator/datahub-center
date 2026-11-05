package cn.iocoder.yudao.module.dataforge.service.fill;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FillFieldRuleValidatorTest {

    private static Map<String, Object> field(Object... pairs) {
        Map<String, Object> field = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            field.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return field;
    }

    private static List<String> codes(Map<String, Object> field, String text) {
        return FillFieldRuleValidator.checkValue(field, text).stream()
                .map(FillFieldRuleValidator.Violation::ruleCode).toList();
    }

    @Test
    void definitionRejectsInconsistentRules() {
        assertNull(FillFieldRuleValidator.checkDefinition(1, "0", "100.5", null, null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(2, "0", null, null, null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(5, "abc", null, null, null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(5, "10", "9", null, null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(3, "2026-02-30", null, null, null));
        assertNull(FillFieldRuleValidator.checkDefinition(3, "2026-01-01", "2026-12-31", null, null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(3, "2026-12-31", "2026-01-01", null, null));
        assertNull(FillFieldRuleValidator.checkDefinition(10, "08:00:00", "18:00:00", null, null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(1, null, null, "[0-9]+", null));
        assertNull(FillFieldRuleValidator.checkDefinition(2, null, null, "[0-9]{18}", 2));
        assertNotNull(FillFieldRuleValidator.checkDefinition(2, null, null, "([", null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(2, null, null, "a".repeat(201), null));
        assertNotNull(FillFieldRuleValidator.checkDefinition(2, null, null, null, 3));
    }

    @Test
    void numericRangeUsesExactDecimalComparison() {
        Map<String, Object> amount = field("fieldType", 1, "min", "0", "max", "100.10", "scale", 2);
        assertTrue(codes(amount, "50").isEmpty());
        assertTrue(codes(amount, "100.10").isEmpty());
        assertEquals(List.of("MAX_VALUE"), codes(amount, "100.11"));
        assertEquals(List.of("MIN_VALUE"), codes(amount, "-0.01"));
        // 超出 double 精度的边界仍然精确比较
        Map<String, Object> exact = field("fieldType", 1, "max", "0.30000000000000004");
        assertEquals(List.of("MAX_VALUE"), codes(exact, "0.30000000000000005"));
        assertTrue(codes(exact, "0.30000000000000004").isEmpty());
    }

    @Test
    void integerBoundsAcceptSignsAndLargeLongs() {
        Map<String, Object> count = field("fieldType", 6, "min", "-5", "max", "9223372036854775807");
        assertTrue(codes(count, "9223372036854775807").isEmpty());
        assertEquals(List.of("MIN_VALUE"), codes(count, "-6"));
    }

    @Test
    void temporalRangeAcceptsSlashAndDateOnlyDatetime() {
        Map<String, Object> date = field("fieldType", 3, "min", "2026-01-01", "max", "2026-12-31");
        assertTrue(codes(date, "2026/06/01").isEmpty());
        assertEquals(List.of("MIN_VALUE"), codes(date, "2025-12-31"));
        assertEquals(List.of("RANGE"), codes(date, "not a date"));
        Map<String, Object> time = field("fieldType", 10, "min", "08:00:00", "max", "18:00:00");
        assertTrue(codes(time, "12:30:00").isEmpty());
        assertEquals(List.of("MAX_VALUE"), codes(time, "18:00:01"));
        Map<String, Object> datetime = field("fieldType", 9, "max", "2026-01-01 00:00:00");
        assertTrue(codes(datetime, "2026-01-01").isEmpty());
        assertEquals(List.of("MAX_VALUE"), codes(datetime, "2026-01-01 00:00:01"));
    }

    @Test
    void severityComesFromTheFieldAndDefaultsToError() {
        Map<String, Object> warning = field("fieldType", 5, "max", "10", "ruleSeverity", 2);
        assertEquals(FillFieldRuleValidator.SEVERITY_WARNING,
                FillFieldRuleValidator.checkValue(warning, "11").get(0).severity());
        Map<String, Object> error = field("fieldType", 5, "max", "10");
        assertEquals(FillFieldRuleValidator.SEVERITY_ERROR,
                FillFieldRuleValidator.checkValue(error, "11").get(0).severity());
    }

    @Test
    void regexMatchesWholeValueWithCustomMessage() {
        Map<String, Object> id = field("fieldType", 2, "regex", "[0-9]{4}", "regexMessage", "请输入 4 位数字");
        assertTrue(codes(id, "1234").isEmpty());
        assertEquals(List.of("REGEX"), codes(id, "12345"));
        assertEquals("请输入 4 位数字", FillFieldRuleValidator.checkValue(id, "abc").get(0).detail());
        assertEquals("格式不符合要求",
                FillFieldRuleValidator.checkValue(field("fieldType", 2, "regex", "x"), "y").get(0).detail());
        // 非字符串字段忽略正则，避免误报
        assertTrue(codes(field("fieldType", 5, "regex", "x"), "5").isEmpty());
    }

    @Test
    void catastrophicRegexIsStoppedByTheTimeBudget() {
        Map<String, Object> evil = field("fieldType", 2, "regex", "(a+)+$");
        String input = "a".repeat(1900) + "b";
        long start = System.nanoTime();
        List<FillFieldRuleValidator.Violation> result = FillFieldRuleValidator.checkValue(evil, input);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(1, result.size());
        assertTrue(result.get(0).detail().contains("超时"));
        assertTrue(elapsedMillis < 2_000, "耗时 " + elapsedMillis + "ms");
        String tooLong = "a".repeat(FillFieldRuleValidator.MAX_REGEX_INPUT_LENGTH + 1);
        assertTrue(FillFieldRuleValidator.checkValue(field("fieldType", 2, "regex", "a+"), tooLong).get(0)
                .detail().contains("过长"));
    }

    @Test
    void decimalScaleAndPrecisionFollowTheColumnDefinition() {
        Map<String, Object> money = field("fieldType", 1, "length", 6, "scale", 2);
        assertTrue(codes(money, "1234.50").isEmpty());
        assertTrue(codes(money, "1234.500").isEmpty());
        assertEquals(List.of("SCALE"), codes(money, "1.234"));
        assertEquals(List.of("PRECISION"), codes(money, "12345.6"));
        assertTrue(codes(field("fieldType", 1), "1.23456789").isEmpty());
    }
}
