package cn.iocoder.yudao.module.governance.service.rule;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SafetyDisplayMaskTest {
    @Test void singleCharacterAndWholeReplacementPreserveTheNewInclusiveContract() {
        assertThat(mask("张三", "*", range(2, 2))).isEqualTo("张*");
        assertThat(mask("123456789012345678", "********", range(7, 14))).isEqualTo("123456********5678");
        assertThat(mask("abcdef", "X", range(2, 4))).isEqualTo("aXef");
        assertThat(mask("abcdef", "MASK", range(1, 1))).isEqualTo("MASKbcdef");
    }
    @Test void multipleOriginalPositionIntervalsAreIndependentOfReplacementLengthAndInputOrder() {
        assertThat(mask("abcdefghij", "XX", range(8, 9), range(2, 3))).isEqualTo("aXXdefgXXj");
        assertThat(mask("abc", "*", range(9, 12))).isEqualTo("abc");
        assertThat(mask("abc", "*", range(2, 10))).isEqualTo("a*");
        assertThat(SafetyMaskEngine.mask(null, "MASK", Map.of(), List.of())).isNull();
    }
    @Test void invalidPositionsFractionsOverlapAndMissingReplacementNeverFallBackToPlainText() {
        for (Object intervals : List.of(List.of(), List.of(range(0, 1)), List.of(range(2, 1)),
                List.of(Map.of("start", 1.5, "end", 3)), List.of(range(1, 3), range(3, 5)))) {
            Map<String, Object> config = Map.of("intervals", intervals, "maskChar", "*");
            assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("MASK", config, List.of())).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> SafetyMaskEngine.mask("abcdef", "MASK", config, List.of())).isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("MASK", Map.of("intervals", List.of(range(1, 1)), "maskChar", ""), List.of()))
                .isInstanceOf(ServiceException.class);
    }
    @Test void originalSingleRangeKeepsItsCharacterFillBehavior() {
        Map<String, Object> config = Map.of("maskStart", 2, "maskEnd", 4, "maskChar", "*");
        SafetyRuleConfigValidator.validate("MASK", config, List.of());
        assertThat(SafetyMaskEngine.mask("abcdef", "MASK", config, List.of())).isEqualTo("a***ef");
        assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("MASK", Map.of("maskStart", 1, "maskEnd", 1), List.of()))
                .isInstanceOf(ServiceException.class);
    }
    @SafeVarargs private static Object mask(String text, String replacement, Map<String, Integer>... ranges) {
        return SafetyMaskEngine.mask(text, "MASK", Map.of("intervals", Arrays.asList(ranges), "maskChar", replacement), List.of());
    }
    private static Map<String, Integer> range(int start, int end) { return Map.of("start", start, "end", end); }
}
