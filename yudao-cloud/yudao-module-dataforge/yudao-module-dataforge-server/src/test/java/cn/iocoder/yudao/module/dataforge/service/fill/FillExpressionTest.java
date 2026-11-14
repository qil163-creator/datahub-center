package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 填报表达式语言：语法、范围、空值与精度语义，以及公式重算和表达式规则
 */
class FillExpressionTest {

    private static Object eval(String text, Map<String, Object> row) {
        return FillExpression.compile(text, FillExpression.Scope.ROW).evaluate(new FillExpression.Context() {
            @Override
            public Object field(String fieldCode) {
                return row.get(fieldCode);
            }
        });
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void arithmeticIsExactDecimalNotFloatingPoint() {
        assertThat(eval("a + b", row("a", "0.1", "b", "0.2"))).isEqualTo(new BigDecimal("0.3"));
        assertThat(eval("price * qty - discount", row("price", "19.99", "qty", 3, "discount", "0.97")))
                .isEqualTo(new BigDecimal("59.00"));
        assertThat(eval("ROUND(10 / 3, 2)", row())).isEqualTo(new BigDecimal("3.33"));
        assertThat(eval("-a % 3", row("a", 7))).isEqualTo(new BigDecimal("-1"));
    }

    @Test
    void nullPropagatesAndDivisionByZeroIsNull() {
        assertThat(eval("a * b", row("a", "2"))).isNull();
        assertThat(eval("a * b", row("a", "2", "b", ""))).isNull();
        assertThat(eval("a / b", row("a", 1, "b", 0))).isNull();
        assertThat(eval("COALESCE(b, 0) + a", row("a", 2))).isEqualTo(new BigDecimal("2"));
        assertThat(eval("MAX(a, b, c)", row("a", 1, "c", 5))).isEqualTo(new BigDecimal("5"));
    }

    @Test
    void comparisonLogicAndFunctions() {
        assertThat(eval("end_date >= start_date", row("start_date", "2026-01-02", "end_date", "2026-01-10")))
                .isEqualTo(true);
        assertThat(eval("total = a + b AND NOT (a < 0)", row("total", "10.00", "a", 4, "b", 6))).isEqualTo(true);
        assertThat(eval("a > 1 || b > 1", row("a", 0))).isNull();
        assertThat(eval("a > 1 OR b > 1", row("a", 2))).isEqualTo(true);
        assertThat(eval("a > 1 AND b > 1", row("a", 0))).isEqualTo(false);
        assertThat(eval("IF(type = '收入', amount, -amount)", row("type", "支出", "amount", 5)))
                .isEqualTo(new BigDecimal("-5"));
        assertThat(eval("LEN(name) <= 4", row("name", "张三"))).isEqualTo(true);
        assertThat(eval("name == 'it''s'", row("name", "it's"))).isEqualTo(true);
    }

    @Test
    void scopesRestrictFunctionsAndFieldUsage() {
        assertThatThrownBy(() -> FillExpression.compile("SUM(a)", FillExpression.Scope.FORMULA))
                .isInstanceOf(FillExpression.DefinitionException.class).hasMessageContaining("表级");
        assertThatThrownBy(() -> FillExpression.compile("a > 0", FillExpression.Scope.TABLE))
                .hasMessageContaining("汇总函数");
        assertThatThrownBy(() -> FillExpression.compile("PREV_SUM(a) > 0", FillExpression.Scope.TABLE))
                .hasMessageContaining("跨期");
        assertThatThrownBy(() -> FillExpression.compile("SUM(a + b) > 0", FillExpression.Scope.TABLE))
                .hasMessageContaining("参数必须是一个字段");
        assertThatThrownBy(() -> FillExpression.compile("exec('x')", FillExpression.Scope.ROW))
                .hasMessageContaining("不支持的函数");
        assertThatThrownBy(() -> FillExpression.compile("a +", FillExpression.Scope.ROW))
                .hasMessageContaining("不完整");
        assertThatThrownBy(() -> FillExpression.compile("(a + b", FillExpression.Scope.ROW))
                .hasMessageContaining("缺少 ')'");
        assertThat(FillExpression.compile("SUM(a) <= PREV_SUM(a) * 1.3", FillExpression.Scope.PERIOD).fieldRefs())
                .containsExactly("a");
    }

    @Test
    void formulaOrderFollowsDependenciesAndRejectsCycles() {
        Map<String, FillExpression> formulas = new LinkedHashMap<>();
        formulas.put("total", FillExpression.compile("subtotal + tax", FillExpression.Scope.FORMULA));
        formulas.put("subtotal", FillExpression.compile("price * qty", FillExpression.Scope.FORMULA));
        assertThat(FillExpression.evaluationOrder(formulas)).containsExactly("subtotal", "total");

        formulas.put("price", FillExpression.compile("total / qty", FillExpression.Scope.FORMULA));
        assertThatThrownBy(() -> FillExpression.evaluationOrder(formulas)).hasMessageContaining("循环引用");
    }

    @Test
    void formulaResultsAreRoundedToFieldScaleAsDecimalStrings() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("price", "2.345", "qty", 3), row("qty", 1)));
        String schema = JsonUtils.toJsonString(Map.of("engineVersion", "1.0", "formulas", List.of(
                Map.of("fieldCode", "amount", "expression", "price * qty", "fieldType", 1, "scale", 2),
                Map.of("fieldCode", "amount_int", "expression", "amount", "fieldType", 5))));

        assertThat(FillTemplateEvaluator.applyFormulas(schema, rows)).isTrue();

        assertThat(rows.get(0)).containsEntry("amount", "7.04").containsEntry("amount_int", "7");
        assertThat(rows.get(1)).doesNotContainKey("amount");
    }

    @Test
    void rowTableAndPeriodRulesReportViolationsWithSeverity() {
        List<Map<String, Object>> rows = List.of(row("total", 10, "a", 4, "b", 6), row("total", 9, "a", 4, "b", 6));
        String schema = JsonUtils.toJsonString(Map.of("expressionRules", List.of(
                Map.of("ruleCode", "EXPR_1", "name", "合计", "scope", "ROW", "expression", "total = a + b",
                        "message", "合计应等于分项之和", "severity", 1, "fieldCode", "total"),
                Map.of("ruleCode", "EXPR_2", "name", "上限", "scope", "TABLE", "expression", "SUM(total) <= 15",
                        "message", "合计超过上限", "severity", 2),
                Map.of("ruleCode", "EXPR_3", "name", "波动", "scope", "PERIOD",
                        "expression", "ABS(SUM(total) - PREV_SUM(total)) <= PREV_SUM(total) * 0.3",
                        "message", "与上期相比波动超过 30%", "severity", 2))));

        var violations = FillTemplateEvaluator.evaluateRules(schema, rows, () -> List.of(row("total", 10)));
        assertThat(violations).extracting(FillTemplateEvaluator.Violation::ruleCode)
                .containsExactly("EXPR_1", "EXPR_2", "EXPR_3");
        assertThat(violations.getFirst().rowIndex()).isEqualTo(1);
        assertThat(violations.getFirst().fieldCode()).isEqualTo("total");
        assertThat(violations.get(1).severity()).isEqualTo(FillFieldRuleValidator.SEVERITY_WARNING);

        // 没有上期数据：跨期规则不适用
        assertThat(FillTemplateEvaluator.evaluateRules(schema, rows, () -> null))
                .extracting(FillTemplateEvaluator.Violation::ruleCode).containsExactly("EXPR_1", "EXPR_2");
    }


    @Test
    void multiSelectValuesAreCheckedItemByItem() {
        List<String> options = List.of("甲", "乙", "丙");
        assertThat(FillFieldRuleValidator.optionsAllowed(options, true, "甲, 丙")).isTrue();
        assertThat(FillFieldRuleValidator.optionsAllowed(options, true, "甲,丁")).isFalse();
        assertThat(FillFieldRuleValidator.optionsAllowed(options, false, "甲,乙")).isFalse();
        assertThat(FillFieldRuleValidator.optionsAllowed(options, false, "乙")).isTrue();
        assertThat(FillFieldRuleValidator.optionsAllowed(List.of(), false, "任意")).isTrue();
    }

}
