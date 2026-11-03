package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EtlFieldRulesTest {
    @Test
    void unicodeWhitespaceCaseAndPaddingPreserveNullAndOriginalRow() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "\u00a0\u3000iStraße\u2003");
        input.put("emoji", "😀"); input.put("absent", null);
        var plan = plan("string-operation", List.of(
                Map.of("inputField", "name", "outputField", "upper", "trim", "BOTH", "caseMode", "UPPER"),
                Map.of("inputField", "emoji", "outputField", "center", "padDirection", "BOTH", "padCharacter", "🟢", "padLength", 4),
                Map.of("inputField", "absent", "outputField", "nullCopy", "caseMode", "UPPER")));
        Locale previous = Locale.getDefault();
        Map<String, Object> result;
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); result = apply(plan, input); }
        finally { Locale.setDefault(previous); }
        assertEquals("ISTRASSE", result.get("upper"));
        assertEquals("🟢😀🟢🟢", result.get("center"));
        assertTrue(result.containsKey("nullCopy")); assertNull(result.get("nullCopy"));
        assertEquals("\u00a0\u3000iStraße\u2003", input.get("name")); assertEquals(3, input.size());
    }

    @Test
    void directionalTrimAndPaddingDoNotTruncateOrSplitSurrogates() {
        var result = apply(plan("string-operation", List.of(
                Map.of("inputField", "left", "trim", "LEFT", "padDirection", "RIGHT", "padCharacter", "xy", "padLength", 6),
                Map.of("inputField", "right", "trim", "RIGHT", "padDirection", "LEFT", "padCharacter", "0", "padLength", 4),
                Map.of("inputField", "long", "padDirection", "BOTH", "padCharacter", "0", "padLength", 1))),
                Map.of("left", "  😀 ", "right", "  i  ", "long", "😀text"));
        assertEquals("😀 xyxy", result.get("left")); assertEquals("0  i", result.get("right"));
        assertEquals("😀text", result.get("long"));
    }

    @Test
    void fieldCopiesSwapFromOneSnapshotWithoutConvertingDecimalIntegerBinaryOrNull() {
        BigDecimal decimal = new BigDecimal("9007199254740993.123456789012345678");
        BigInteger integer = new BigInteger("99999999999999999999999999999");
        byte[] bytes = { 0, 1, -1 };
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", decimal); input.put("b", integer); input.put("bytes", bytes); input.put("nil", null);
        var result = apply(plan("set-field-value", List.of(
                Map.of("sourceField", "a", "targetField", "b"), Map.of("sourceField", "b", "targetField", "a"),
                Map.of("sourceField", "bytes", "targetField", "binaryCopy"), Map.of("sourceField", "nil", "targetField", "nullCopy"))), input);
        assertSame(integer, result.get("a")); assertSame(decimal, result.get("b")); assertSame(bytes, result.get("binaryCopy"));
        assertNull(result.get("nullCopy")); assertTrue(result.containsKey("nullCopy"));
        assertSame(decimal, input.get("a")); assertSame(integer, input.get("b")); assertEquals(4, input.size());
    }

    @Test
    void literalReplacementQuotesDollarBackslashesAndUsesUnicodeCaseAndWholeWord() {
        var result = apply(plan("string-replace", List.of(Map.of("inputField", "text", "search", "cät",
                "replacement", "$1\\home", "caseSensitive", false, "wholeWord", true))),
                Map.of("text", "CÄT cätapult cÄt cät_ xcät cät"));
        assertEquals("$1\\home cätapult $1\\home cät_ xcät $1\\home", result.get("text"));
        var literal = apply(plan("string-replace", List.of(Map.of("inputField", "text", "search", ".", "replacement", "$0"))), Map.of("text", "a.b"));
        assertEquals("a$0b", literal.get("text"));
    }

    @Test
    void regexReplacementSupportsNamedNumberedAndEscapedGroupsWithoutChangingLiteralFieldValues() {
        var result = apply(plan("string-replace", List.of(
                Map.of("inputField", "text", "outputField", "grouped", "search", "(?<word>\\p{L}+)-(\\d+)", "regex", true, "replacement", "${word}:$2/$0"),
                Map.of("inputField", "text", "outputField", "literal", "search", "\\d+", "regex", true, "replacementField", "replacement"))),
                Map.of("text", "猫-12", "replacement", "$1\\path"));
        assertEquals("猫:12/猫-12", result.get("grouped")); assertEquals("猫-$1\\path", result.get("literal"));
        assertEquals("a2$1", apply(plan("string-replace", List.of(Map.of("inputField", "text", "search", "(a)", "regex", true, "replacement", "$12\\$1"))), Map.of("text", "a")).get("text"));
    }

    @Test
    void nullSourceRemainsNullAndEmptyReplacementOverridesReplacementField() {
        Map<String, Object> input = new LinkedHashMap<>(); input.put("text", null);
        assertNull(apply(plan("string-replace", List.of(Map.of("inputField", "text", "search", "x", "replacement", "y"))), input).get("text"));
        assertEquals("ab", apply(plan("string-replace", List.of(Map.of("inputField", "text", "search", "x", "emptyReplacement", true,
                "replacementField", "notNeeded"))), Map.of("text", "axxb")).get("text"));
        var missing = plan("string-replace", List.of(Map.of("inputField", "text", "search", "x", "replacementField", "missing")));
        assertThrows(IllegalStateException.class, () -> apply(missing, input));
    }

    @Test
    void invalidRegexReplacementAndRuleOptionsFailDuringCompilation() {
        for (String replacement : List.of("$2", "${missing}", "\\")) {
            assertThrows(EtlFieldRules.ConfigException.class, () -> plan("string-replace", List.of(Map.of("inputField", "text", "search", "(a)", "regex", true, "replacement", replacement))));
        }
        var invalid = assertThrows(EtlFieldRules.ConfigException.class, () -> plan("string-replace", List.of(Map.of("inputField", "text", "search", "[", "regex", true))));
        assertEquals("rules[0].search", invalid.field());
        assertThrows(EtlFieldRules.ConfigException.class, () -> plan("string-operation", List.of(Map.of("inputField", "text", "trim", "UNKNOWN"))));
        assertThrows(EtlFieldRules.ConfigException.class, () -> plan("string-operation", List.of(Map.of("inputField", "text", "padDirection", "LEFT", "padLength", 2))));
        assertThrows(EtlFieldRules.ConfigException.class, () -> plan("string-operation", List.of(Map.of("inputField", "text", "padLength", 1.5))));
        assertThrows(EtlFieldRules.ConfigException.class, () -> plan("set-field-value", List.of(Map.of("sourceField", "a", "targetField", "x"), Map.of("sourceField", "b", "targetField", "x"))));
        assertThrows(EtlFieldRules.ConfigException.class, () -> plan("set-field-value", List.of()));
    }

    @Test
    void regexBacktrackingRespondsToEngineStopAndReplacementGrowthIsBounded() {
        var plan = plan("string-replace", List.of(Map.of("inputField", "text", "search", "(a+)+b", "regex", true)));
        AtomicInteger checks = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IllegalStateException.class,
                () -> plan.apply(Map.of("text", "a".repeat(20_000)), () -> {
                    if (checks.incrementAndGet() >= 4) throw new IllegalStateException("停止");
                })));
        assertTrue(checks.get() >= 4);
        var growth = plan("string-replace", List.of(Map.of("inputField", "text", "search", "(a)", "regex", true, "replacement", "$1".repeat(2000))));
        assertThrows(IllegalStateException.class, () -> apply(growth, Map.of("text", "a".repeat(1000))));
    }

    private static EtlFieldRules.Plan plan(String code, List<? extends Map<String, ?>> rules) {
        return EtlFieldRules.compile(code, JSONUtil.parseObj(JSONUtil.toJsonStr(Map.of("rules", rules))));
    }
    private static Map<String, Object> apply(EtlFieldRules.Plan plan, Map<String, Object> row) {
        return plan.apply(row, () -> { });
    }
}
