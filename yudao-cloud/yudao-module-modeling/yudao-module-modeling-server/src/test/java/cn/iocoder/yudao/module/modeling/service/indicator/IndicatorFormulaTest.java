package cn.iocoder.yudao.module.modeling.service.indicator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorFormulaTest {

    private static BigDecimal eval(String formula, Map<String, BigDecimal> values) {
        return IndicatorFormula.parse(formula).evaluate(code -> values.get(code.toLowerCase(Locale.ROOT)));
    }

    @Test
    void precedenceParenthesesAndUnaryMinusFollowArithmetic() {
        var values = Map.of("gmv", new BigDecimal("150.50"), "order_cnt", new BigDecimal("4"));
        assertEquals("3762.5", IndicatorFormula.format(eval("{gmv} / {order_cnt} * 100", values)));
        assertEquals("158.5", IndicatorFormula.format(eval("{gmv} + {order_cnt} * 2", values)));
        assertEquals("309", IndicatorFormula.format(eval("({gmv} + {order_cnt} * 2) * 2 - 8", values)));
        assertEquals("-146.5", IndicatorFormula.format(eval("-{gmv} + -(-{order_cnt})", values)));
        assertEquals("0", IndicatorFormula.format(eval("{gmv} - {GMV}", values)));
    }

    @Test
    void divisionByZeroAndMissingValuesYieldNull() {
        var values = new java.util.HashMap<String, BigDecimal>();
        values.put("a", new BigDecimal("1"));
        values.put("zero", new BigDecimal("0.000"));
        assertNull(eval("{a} / {zero}", values));
        assertNull(eval("{a} / ({zero} * 3)", values));
        assertNull(eval("{a} + {missing}", values));
        assertNull(eval("{a} / 0", values));
        assertNull(IndicatorFormula.format(null));
    }

    @Test
    void exactDecimalArithmeticAvoidsFloatingPointDrift() {
        var values = Map.of("a", new BigDecimal("0.1"), "b", new BigDecimal("0.2"));
        assertEquals("0.3", IndicatorFormula.format(eval("{a} + {b}", values)));
        assertEquals("0.3333333333333333333333333333333333",
                IndicatorFormula.format(eval("{a} / ({a} * 3)", values)));
        var large = Map.of("a", new BigDecimal("9007199254740993.123456789"));
        assertEquals("18014398509481986.246913578", IndicatorFormula.format(eval("{a} * 2", large)));
    }

    @Test
    void referencesAreDistinctCaseInsensitiveAndNormalizable() {
        var formula = IndicatorFormula.parse(" {Gmv} / {order_cnt} + {GMV} ");
        assertEquals(List.of("Gmv", "order_cnt"), formula.references());
        assertEquals("{gmv} / {ORDER_CNT} + {gmv}", formula.normalize(code ->
                code.equalsIgnoreCase("gmv") ? "gmv" : "ORDER_CNT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "100", "{gmv} +", "({gmv}", "{gmv})", "{gmv} {cnt}", "{gmv} ** 2",
            "{gmv} % 2", "{gmv} ^ 2", "{ gmv }", "{1abc}", "{gmv", "sum({gmv})", "{gmv}; DROP TABLE x",
            "{gmv} -- comment", "1.", ".5 + {gmv}", "{gmv} + 1e3", "{gmv} + ０", "{g-m}", "'1' + {gmv}"})
    void onlyNumbersOperatorsParenthesesAndReferencesAreAccepted(String formula) {
        assertThrows(IllegalArgumentException.class, () -> IndicatorFormula.parse(formula));
    }

    @Test
    void limitsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> IndicatorFormula.parse("{a}+".repeat(600) + "{a}"));
        StringBuilder many = new StringBuilder("{c0}");
        for (int index = 1; index <= IndicatorFormula.MAX_REFERENCES; index++) {
            many.append(" + {c").append(index).append('}');
        }
        assertThrows(IllegalArgumentException.class, () -> IndicatorFormula.parse(many.toString()));
        assertThrows(IllegalArgumentException.class, () -> IndicatorFormula.parse("(".repeat(40) + "{a}"
                + ")".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> IndicatorFormula.parse("{a} + " + "9".repeat(39)));
    }
}
