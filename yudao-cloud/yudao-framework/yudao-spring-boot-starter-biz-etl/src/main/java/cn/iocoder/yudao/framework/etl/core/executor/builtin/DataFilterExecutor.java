package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据过滤节点（toolCode: data-filter）
 *
 * 配置：logicalOperator AND/OR；filterConditions: [{fieldName, operator, value, minValue, maxValue}]
 * 满足条件的行继续向下游传递，不满足的丢弃
 *
 * @author Li
 */
public class DataFilterExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "data-filter";

    /**
     * 初始化 {@code DataFilterExecutor}及其父类状态。
     */
    public DataFilterExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    private record Condition(String fieldName, String operator, String value, String minValue, String maxValue) {

    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        List<Condition> conditions = parseConditions();
        boolean andMode = !"OR".equalsIgnoreCase(config().getStr("logicalOperator", "AND"));
        long filtered = 0;
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            if (matches(row, conditions, andMode)) {
                pushRow(row);
            } else {
                filtered++;
            }
        }
        logLine(String.format("过滤完成，通过 %s 行，过滤 %s 行", writeCount.get(), filtered));
    }

    /**
     * 解析条件集合。
     */
    private List<Condition> parseConditions() {
        List<Condition> conditions = new ArrayList<>();
        JSONArray array = config().getJSONArray("filterConditions");
        if (array == null) {
            return conditions;
        }
        for (Object item : array) {
            JSONObject json = (JSONObject) item;
            if (StrUtil.isBlank(json.getStr("fieldName")) || StrUtil.isBlank(json.getStr("operator"))) {
                continue;
            }
            conditions.add(new Condition(json.getStr("fieldName"), json.getStr("operator").toUpperCase(),
                    json.getStr("value"), json.getStr("minValue"), json.getStr("maxValue")));
        }
        return conditions;
    }

    /**
     * 返回当前匹配结果。
     */
    private boolean matches(Map<String, Object> row, List<Condition> conditions, boolean andMode) {
        if (conditions.isEmpty()) {
            return true;
        }
        for (Condition condition : conditions) {
            boolean matched = evaluate(row.get(condition.fieldName), condition);
            if (andMode && !matched) {
                return false;
            }
            if (!andMode && matched) {
                return true;
            }
        }
        return andMode;
    }

    /**
     * 根据当前表达式或规则计算输入值的结果。
     */
    private boolean evaluate(Object cell, Condition condition) {
        String text = cell == null ? null : String.valueOf(cell);
        return switch (condition.operator) {
            case "IS_NULL" -> cell == null || StrUtil.isEmpty(text);
            case "IS_NOT_NULL" -> cell != null && StrUtil.isNotEmpty(text);
            case "EQUALS" -> compareEquals(cell, text, condition.value);
            case "NOT_EQUALS" -> !compareEquals(cell, text, condition.value);
            case "GREATER_THAN" -> compareNumber(text, condition.value) > 0;
            case "GREATER_EQUAL" -> compareNumber(text, condition.value) >= 0;
            case "LESS_THAN" -> compareNumber(text, condition.value) < 0;
            case "LESS_EQUAL" -> compareNumber(text, condition.value) <= 0;
            case "CONTAINS" -> text != null && condition.value != null && text.contains(condition.value);
            case "NOT_CONTAINS" -> text == null || condition.value == null || !text.contains(condition.value);
            case "STARTS_WITH" -> text != null && condition.value != null && text.startsWith(condition.value);
            case "ENDS_WITH" -> text != null && condition.value != null && text.endsWith(condition.value);
            case "REGEX" ->
                    text != null && condition.value != null && Pattern.compile(condition.value).matcher(text).find();
            case "IN" -> text != null && condition.value != null
                    && Arrays.asList(condition.value.split(",")).contains(text);
            case "NOT_IN" -> text == null || condition.value == null
                    || !Arrays.asList(condition.value.split(",")).contains(text);
            case "BETWEEN" -> compareNumber(text, condition.minValue) >= 0
                    && compareNumber(text, condition.maxValue) <= 0;
            default -> throw new IllegalStateException(String.format("不支持的过滤操作符【%s】", condition.operator));
        };
    }

    /**
     * 相等比较：两边都是数字时按数值比较，否则按字符串
     */
    private boolean compareEquals(Object cell, String text, String expected) {
        if (cell == null || expected == null) {
            return cell == null && expected == null;
        }
        if (NumberUtil.isNumber(text) && NumberUtil.isNumber(expected)) {
            return new BigDecimal(text).compareTo(new BigDecimal(expected)) == 0;
        }
        return text.equals(expected);
    }

    /**
     * 比较数值。
     */
    private int compareNumber(String text, String expected) {
        if (text == null || expected == null || !NumberUtil.isNumber(text) || !NumberUtil.isNumber(expected)) {
            // 任一侧不是数字时按字符串比较，保证行为可预期
            return StrUtil.compare(text, expected, false);
        }
        return new BigDecimal(text).compareTo(new BigDecimal(expected));
    }

}
