package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 数值字段关系校验；精度保留在数据库数值类型中。 */
public class NumericRelationValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "NUMERIC_RELATION_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "数值逻辑关系校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"conditions\":[{\"leftField\":\"amount\",\"rightField\":\"limit_amount\",\"operator\":\"<=\"}],\"ignoreNullValue\":false}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "conditions", "ignoreNullValue");
        List<Map<String, Object>> conditions = AuditConfigSupport.objects(result.get("conditions"), "conditions", 1,
                32);
        for (Map<String, Object> item : conditions) {
            AuditConfigSupport.config(item, "leftField", "rightField", "operator");
            item.put("leftField", AuditConfigSupport.text(item.get("leftField"), "leftField", 256));
            item.put("rightField", AuditConfigSupport.text(item.get("rightField"), "rightField", 256));
            item.put("operator",
                    AuditConfigSupport.choice(item.get("operator"), "operator", "<=", "<", "<=", ">", ">=", "=", "<>"));
        }
        result.put("conditions", conditions);
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        List<String> clauses = new ArrayList<>();
        for (Map<String, Object> condition : AuditConfigSupport.objects(config.get("conditions"), "conditions", 1,
                32)) {
            String left = AuditConfigSupport.field(context, condition.get("leftField"), "leftField");
            String right = AuditConfigSupport.field(context, condition.get("rightField"), "rightField");
            clauses.add("(" + left + " " + condition.get("operator") + " " + right + ")");
        }
        return String.join(" AND ", clauses);
    }
}
