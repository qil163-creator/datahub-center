package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 在线填报-模板运行期计算：公式重算与表达式规则校验（服务端为权威结果）
 *
 * @author Li
 */
@Slf4j
public final class FillTemplateEvaluator {

    private FillTemplateEvaluator() {
    }

    /** 违反的表达式规则；rowIndex 为空表示表级/跨期规则 */
    public record Violation(String ruleCode, String name, String message, int severity,
                            String fieldCode, Integer rowIndex) {
    }

    /**
     * 按模板版本的公式定义（已按依赖排序）逐行重算公式字段，结果写回行数据。
     *
     * @param formulaSchemaJson 模板版本 formulaSchema
     * @param rows 行数据（fieldCode → 值），原地修改
     * @return 是否存在公式
     */
    public static boolean applyFormulas(String formulaSchemaJson, List<Map<String, Object>> rows) {
        List<Formula> formulas = parseFormulas(formulaSchemaJson);
        if (formulas.isEmpty()) {
            return false;
        }
        for (Map<String, Object> row : rows) {
            FillExpression.Context context = new FillExpression.Context() {
                @Override
                public Object field(String fieldCode) {
                    return row.get(fieldCode);
                }
            };
            for (Formula formula : formulas) {
                Object value = FillExpression.toFieldValue(formula.expression().evaluate(context),
                        formula.fieldType(), formula.scale());
                if (value == null) {
                    row.remove(formula.fieldCode());
                } else {
                    row.put(formula.fieldCode(), value);
                }
            }
        }
        return true;
    }

    /**
     * 执行模板版本中的行级、表级、跨期表达式规则。
     *
     * @param validationSchemaJson 模板版本 validationSchema
     * @param rows 行数据（已完成公式重算）
     * @param previousRows 上期数据行（仅存在跨期规则时调用；返回 null 表示没有上期数据，跨期规则跳过）
     * @return 违反的规则
     */
    public static List<Violation> evaluateRules(String validationSchemaJson, List<Map<String, Object>> rows,
                                                Supplier<List<Map<String, Object>>> previousRows) {
        List<RuleDef> rules = parseRules(validationSchemaJson);
        List<Violation> violations = new ArrayList<>();
        if (rules.isEmpty()) {
            return violations;
        }
        List<Map<String, Object>> previous = null;
        boolean previousLoaded = false;
        for (RuleDef rule : rules) {
            try {
                switch (rule.expression().scope()) {
                    case ROW -> {
                        for (int index = 0; index < rows.size(); index++) {
                            Map<String, Object> row = rows.get(index);
                            boolean violated = rule.expression().violated(new FillExpression.Context() {
                                @Override
                                public Object field(String fieldCode) {
                                    return row.get(fieldCode);
                                }
                            });
                            if (violated) {
                                violations.add(rule.violation(index));
                            }
                        }
                    }
                    case TABLE -> {
                        if (rule.expression().violated(tableContext(rows, null))) {
                            violations.add(rule.violation(null));
                        }
                    }
                    case PERIOD -> {
                        if (!previousLoaded) {
                            previous = previousRows == null ? null : previousRows.get();
                            previousLoaded = true;
                        }
                        // 没有上期数据（首期、上期未审批通过）时跨期规则不适用
                        if (previous != null && rule.expression().violated(tableContext(rows, previous))) {
                            violations.add(rule.violation(null));
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("[evaluateRules][规则({}) 计算失败：{}]", rule.ruleCode(), ex.getMessage());
                violations.add(new Violation(rule.ruleCode(), rule.name(), "规则【" + rule.name() + "】无法计算，请联系模板管理员",
                        FillFieldRuleValidator.SEVERITY_WARNING, rule.fieldCode(), null));
            }
        }
        return violations;
    }

    private static FillExpression.Context tableContext(List<Map<String, Object>> rows,
                                                       List<Map<String, Object>> previous) {
        return new FillExpression.Context() {
            @Override
            public List<Object> column(String fieldCode) {
                return rows.stream().map(row -> row.get(fieldCode)).toList();
            }

            @Override
            public List<Object> previousColumn(String fieldCode) {
                return previous == null ? null : previous.stream().map(row -> row.get(fieldCode)).toList();
            }
        };
    }

    // ---------------------------------------------------------------- 定义解析

    private record Formula(String fieldCode, FillExpression expression, Integer fieldType, Integer scale) {
    }

    private record RuleDef(String ruleCode, String name, String message, int severity, String fieldCode,
                           FillExpression expression) {

        Violation violation(Integer rowIndex) {
            return new Violation(ruleCode, name, message, severity, fieldCode, rowIndex);
        }
    }

    private static List<Formula> parseFormulas(String json) {
        Map<?, ?> schema = parseMap(json);
        List<Formula> formulas = new ArrayList<>();
        if (!(schema.get("formulas") instanceof List<?> list)) {
            return formulas;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || StrUtil.isBlankIfStr(map.get("expression"))) {
                continue;
            }
            formulas.add(new Formula(String.valueOf(map.get("fieldCode")),
                    FillExpression.compile(String.valueOf(map.get("expression")), FillExpression.Scope.FORMULA),
                    toInteger(map.get("fieldType")), toInteger(map.get("scale"))));
        }
        return formulas;
    }

    private static List<RuleDef> parseRules(String json) {
        Map<?, ?> schema = parseMap(json);
        List<RuleDef> rules = new ArrayList<>();
        if (!(schema.get("expressionRules") instanceof List<?> list)) {
            return rules;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            FillExpression.Scope scope = FillExpression.Scope.valueOf(String.valueOf(map.get("scope")));
            Integer severity = toInteger(map.get("severity"));
            rules.add(new RuleDef(String.valueOf(map.get("ruleCode")), String.valueOf(map.get("name")),
                    String.valueOf(map.get("message")),
                    severity != null && severity == FillFieldRuleValidator.SEVERITY_WARNING
                            ? FillFieldRuleValidator.SEVERITY_WARNING : FillFieldRuleValidator.SEVERITY_ERROR,
                    map.get("fieldCode") == null ? null : String.valueOf(map.get("fieldCode")),
                    FillExpression.compile(String.valueOf(map.get("expression")), scope)));
        }
        return rules;
    }

    private static Map<?, ?> parseMap(String json) {
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        Map<?, ?> map = JsonUtils.parseObject(json, Map.class);
        return map == null ? new LinkedHashMap<>() : map;
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

}
