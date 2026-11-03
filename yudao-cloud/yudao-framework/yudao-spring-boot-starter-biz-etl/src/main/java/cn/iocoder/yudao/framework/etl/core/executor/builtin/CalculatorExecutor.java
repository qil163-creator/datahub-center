package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算器节点（toolCode: calculator）：按规则对字段做数值/日期/字符串计算，结果写入新字段
 *
 * 配置：rules [{targetField 结果字段名称, calcType 计算类型, fieldA, fieldB, fieldC,
 * valueType STRING|INTEGER|DOUBLE 值类型（默认 STRING）}]，规则按顺序执行，可引用前序结果字段。
 *
 * 计算类型：ADD(A+B) SUB(A-B) MUL(A*B) DIV(A/B) ADD3(A+B+C) MIN MAX ABS ROUND CEIL FLOOR
 * MOD(A MOD B) POWER(A^B) SQRT ADD_DAYS(A+B天) DIFF_DAYS(A-B天数) YEAR MONTH DAY
 * STR_LEN UPPER LOWER INIT_CAP TRIM
 *
 * @author Li
 */
public class CalculatorExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "calculator";

    /**
     * 初始化 {@code CalculatorExecutor}及其父类状态。
     */
    public CalculatorExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        List<JSONObject> rules = resolveRules();
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            for (JSONObject rule : rules) {
                result.put(rule.getStr("targetField"), calculate(rule, result));
            }
            pushRow(result);
        }
        logLine(String.format("计算完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 解析规则集合。
     */
    private List<JSONObject> resolveRules() {
        List<JSONObject> rules = new ArrayList<>();
        JSONArray array = config().getJSONArray("rules");
        if (array != null) {
            for (Object item : array) {
                JSONObject rule = (JSONObject) item;
                if (StrUtil.isNotBlank(rule.getStr("targetField"))
                        && StrUtil.isNotBlank(rule.getStr("calcType"))) {
                    rules.add(rule);
                }
            }
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException(
                    String.format("节点【%s】缺少配置：计算规则", node.getName()));
        }
        return rules;
    }

    /**
     * 返回当前计算。
     */
    private Object calculate(JSONObject rule, Map<String, Object> row) {
        String calcType = rule.getStr("calcType").toUpperCase();
        Object valueA = row.get(rule.getStr("fieldA"));
        Object valueB = row.get(rule.getStr("fieldB"));
        Object valueC = row.get(rule.getStr("fieldC"));
        Object result = switch (calcType) {
            case "ADD" -> numeric(valueA, valueB, BigDecimal::add);
            case "SUB" -> numeric(valueA, valueB, BigDecimal::subtract);
            case "MUL" -> numeric(valueA, valueB, BigDecimal::multiply);
            case "DIV" -> numeric(valueA, valueB,
                    (a, b) -> b.signum() == 0 ? null : a.divide(b, MathContext.DECIMAL64));
            case "ADD3" -> {
                BigDecimal sum = toDecimal(valueA);
                BigDecimal b = toDecimal(valueB);
                BigDecimal c = toDecimal(valueC);
                yield sum == null || b == null || c == null ? null : sum.add(b).add(c);
            }
            case "MIN" -> numeric(valueA, valueB, BigDecimal::min);
            case "MAX" -> numeric(valueA, valueB, BigDecimal::max);
            case "ABS" -> unary(valueA, BigDecimal::abs);
            case "ROUND" -> unary(valueA, a -> a.setScale(0, RoundingMode.HALF_UP));
            case "CEIL" -> unary(valueA, a -> a.setScale(0, RoundingMode.CEILING));
            case "FLOOR" -> unary(valueA, a -> a.setScale(0, RoundingMode.FLOOR));
            case "MOD" -> numeric(valueA, valueB,
                    (a, b) -> b.signum() == 0 ? null : a.remainder(b));
            case "POWER" -> numeric(valueA, valueB,
                    (a, b) -> BigDecimal.valueOf(Math.pow(a.doubleValue(), b.doubleValue())));
            case "SQRT" -> unary(valueA, a -> BigDecimal.valueOf(Math.sqrt(a.doubleValue())));
            case "ADD_DAYS" -> {
                DateTime date = toDate(valueA);
                BigDecimal days = toDecimal(valueB);
                yield date == null || days == null ? null
                        : DateUtil.formatDateTime(DateUtil.offsetDay(date, days.intValue()));
            }
            case "DIFF_DAYS" -> {
                DateTime dateA = toDate(valueA);
                DateTime dateB = toDate(valueB);
                yield dateA == null || dateB == null ? null
                        : DateUtil.betweenDay(dateB, dateA, false);
            }
            case "YEAR" -> dateField(valueA, DateUtil::year);
            case "MONTH" -> {
                DateTime date = toDate(valueA);
                yield date == null ? null : DateUtil.month(date) + 1;
            }
            case "DAY" -> dateField(valueA, DateUtil::dayOfMonth);
            case "STR_LEN" -> valueA == null ? null : String.valueOf(valueA).length();
            case "UPPER" -> valueA == null ? null : String.valueOf(valueA).toUpperCase();
            case "LOWER" -> valueA == null ? null : String.valueOf(valueA).toLowerCase();
            case "INIT_CAP" -> valueA == null ? null : StrUtil.upperFirst(String.valueOf(valueA));
            case "TRIM" -> valueA == null ? null : String.valueOf(valueA).trim();
            default -> throw new IllegalStateException(
                    String.format("暂不支持的计算类型【%s】", calcType));
        };
        return convert(result, rule.getStr("valueType"));
    }

    private interface DecimalOperator {

        /**
         * 对当前精确小数值应用指定的一元运算。
         */
        BigDecimal apply(BigDecimal left, BigDecimal right);
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    private Object numeric(Object valueA, Object valueB, DecimalOperator operator) {
        BigDecimal a = toDecimal(valueA);
        BigDecimal b = toDecimal(valueB);
        return a == null || b == null ? null : operator.apply(a, b);
    }

    /**
     * 返回当前一元运算。
     */
    private Object unary(Object value, java.util.function.UnaryOperator<BigDecimal> operator) {
        BigDecimal decimal = toDecimal(value);
        return decimal == null ? null : operator.apply(decimal);
    }

    /**
     * 将输入转换为日期后提取指定日期分量。
     */
    private Object dateField(Object value, java.util.function.ToIntFunction<DateTime> getter) {
        DateTime date = toDate(value);
        return date == null ? null : getter.applyAsInt(date);
    }

    /**
     * 将输入值转换为精确小数。
     */
    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return NumberUtil.isNumber(text) ? new BigDecimal(text) : null;
    }

    /**
     * 转换为日期。
     */
    private DateTime toDate(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return DateUtil.parse(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 结果按值类型转换：STRING（默认，数值去除多余的 0）/ INTEGER / DOUBLE
     */
    private Object convert(Object result, String valueType) {
        if (result == null) {
            return null;
        }
        return switch (StrUtil.nullToEmpty(valueType).toUpperCase()) {
            case "INTEGER" -> result instanceof BigDecimal decimal
                    ? decimal.setScale(0, RoundingMode.HALF_UP).longValue()
                    : NumberUtil.parseLong(String.valueOf(result));
            case "DOUBLE" -> result instanceof BigDecimal decimal
                    ? decimal.doubleValue()
                    : NumberUtil.parseDouble(String.valueOf(result));
            default -> result instanceof BigDecimal decimal
                    ? decimal.stripTrailingZeros().toPlainString()
                    : String.valueOf(result);
        };
    }

}
