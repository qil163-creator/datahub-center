package cn.iocoder.yudao.framework.etl.core.executor.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EtlRegexReplacementTest {
    @Test void javaNumberedNamedGroupsEscapesAndLiteralFieldValuesHaveOneContract() {
        var grouped = EtlRegexReplacement.compile("(?<word>[a-z]+)-(\\d+)", true, true, false, "${word}:$2:$0", true);
        assertEquals("abc:12:abc-12", grouped.apply("abc-12", "ignored", () -> { }));
        var literal = EtlRegexReplacement.compile("a", false, true, false, "", false);
        assertEquals("$1\\tail", literal.apply("a", "$1\\tail", () -> { }));
        var escaped = EtlRegexReplacement.compile("a", true, true, false, "\\$1", true);
        assertEquals("$1", escaped.apply("a", "ignored", () -> { }));
        assertEquals("a", EtlRegexReplacement.compile("(?<word>a)\\Q$", true, true, false, "${word}", true).apply("a$", "ignored", () -> { }));
        assertEquals("a", EtlRegexReplacement.compile("(?x)(?<word>a)# trailing", true, true, false, "${word}", true).apply("a", "ignored", () -> { }));
    }

    @Test void wholeWordChecksTheOriginalJavaMatcherResultAndUnicodeBoundaries() {
        var alternatives = EtlRegexReplacement.compile("a|ab", true, true, true, "X", true);
        assertEquals("ab X a\u0301 _a_", alternatives.apply("ab a a\u0301 _a_", "ignored", () -> { }));
        var insensitive = EtlRegexReplacement.compile("é", false, false, true, "", false);
        assertEquals("X xé Éx", insensitive.apply("É xé Éx", "X", () -> { }));
    }

    @Test void nullLimitsAndCancellationAreNotSilentlyIgnored() {
        var plan = EtlRegexReplacement.compile("a", true, true, false, "", false);
        assertNull(plan.apply(null, "x", () -> { }));
        assertThrows(IllegalStateException.class, () -> plan.apply("a".repeat(1_000_001), "x", () -> { }));
        assertThrows(IllegalStateException.class, () -> plan.apply("a", "x", () -> { throw new IllegalStateException("cancelled"); }));
        assertThrows(IllegalArgumentException.class, () -> EtlRegexReplacement.compile("(a)", true, true, false, "$2", true));
        assertThrows(IllegalArgumentException.class, () -> EtlRegexReplacement.compile("(?x)(?<word>a)# trailing", true, true, false, "${missing}", true));
    }
}
