package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 状态触发后的类型化断言，缺失值只有 IS_NULL 断言能通过。 */
public class StateConsistencyValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "STATE_CONSISTENCY_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "业务状态一致性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"conditionColumn\":\"status\",\"conditionValues\":[\"PAID\"],\"assertions\":[{\"column\":\"paid_amount\",\"operator\":\"GT\",\"valueType\":\"DECIMAL\",\"value\":\"0\"}]}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "conditionColumn", "conditionValues",
                "assertions", "ignoreNullValue");
        result.put("conditionColumn", AuditConfigSupport.text(result.get("conditionColumn"), "conditionColumn", 256));
        result.put("conditionValues",
                AuditConfigSupport.texts(result.get("conditionValues"), "conditionValues", 1, 128));
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        List<Map<String, Object>> assertions = AuditConfigSupport.objects(result.get("assertions"), "assertions", 1,
                32);
        for (Map<String, Object> item : assertions) {
            AuditConfigSupport.config(item, "column", "operator", "valueType", "value", "values");
            item.put("column", AuditConfigSupport.text(item.get("column"), "column", 256));
            String operator = AuditConfigSupport.choice(item.get("operator"), "operator", "EQ", "IS_NULL", "NOT_NULL",
                    "EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN");
            String type = AuditConfigSupport.choice(item.get("valueType"), "valueType", "TEXT", "TEXT", "DECIMAL");
            item.put("operator", operator);
            item.put("valueType", type);
            if (Set.of("IS_NULL", "NOT_NULL").contains(operator)) {
                if (item.containsKey("value") || item.containsKey("values")) {
                    throw new IllegalArgumentException("NULL assertions cannot contain values");
                }
            } else if (Set.of("IN", "NOT_IN").contains(operator)) {
                if (item.containsKey("value")) {
                    throw new IllegalArgumentException("Set assertions require values only");
                }
                item.put("values", AuditConfigSupport.texts(item.get("values"), "values", 1, 128).stream()
                        .map(v -> "DECIMAL".equals(type) ? AuditConfigSupport.decimal(v, "values") : v).toList());
            } else {
                if (item.containsKey("values")) {
                    throw new IllegalArgumentException("Scalar assertions require value only");
                }
                item.put("value", "DECIMAL".equals(type) ? AuditConfigSupport.decimal(item.get("value"),
                        "value") : AuditConfigSupport.text(item.get("value"), "value", 1024));
            }
        }
        result.put("assertions", assertions);
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        List<String> conditions = new ArrayList<>();
        for (Map<String, Object> assertion : AuditConfigSupport.objects(config.get("assertions"), "assertions", 1,
                32)) {
            String column = AuditConfigSupport.field(context, assertion.get("column"), "column");
            String operator = (String) assertion.get("operator");
            if ("IS_NULL".equals(operator)) {
                conditions.add(column + " IS NULL");
                continue;
            }
            if ("NOT_NULL".equals(operator)) {
                conditions.add(column + " IS NOT NULL");
                continue;
            }
            boolean decimal = "DECIMAL".equals(assertion.get("valueType"));
            String left = decimal ? column : AuditSqlDialect.caseSensitive(context.getProductType(),
                    AuditSqlDialect.castText(context.database(), column));
            String comparison;
            if ("IN".equals(operator) || "NOT_IN".equals(operator)) {
                String values = AuditConfigSupport.texts(assertion.get("values"), "values", 1, 128).stream()
                        .map(v -> decimal ? v : AuditConfigSupport.literal(context, v))
                        .collect(java.util.stream.Collectors.joining(", "));
                comparison = left + ("IN".equals(operator) ? " IN (" : " NOT IN (") + values + ")";
            } else {
                String sqlOperator = switch (operator) {
                    case "EQ" -> "=";
                    case "NE" -> "<>";
                    case "GT" -> ">";
                    case "GTE" -> ">=";
                    case "LT" -> "<";
                    default -> "<=";
                };
                String value = (String) assertion.get("value");
                comparison = left + " " + sqlOperator + " " + (decimal ? value : AuditConfigSupport.literal(context,
                        value));
            }
            conditions.add("(" + column + " IS NOT NULL AND " + comparison + ")");
        }
        return "CASE WHEN " + AuditConfigSupport.trigger(context, config) + " THEN (" + String.join(" AND ",
                conditions) + ") ELSE TRUE END";
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    protected String buildInvalidFrag(AuditRuleContext context) {
        return "NOT (" + buildValidFrag(context) + ")";
    }
}
