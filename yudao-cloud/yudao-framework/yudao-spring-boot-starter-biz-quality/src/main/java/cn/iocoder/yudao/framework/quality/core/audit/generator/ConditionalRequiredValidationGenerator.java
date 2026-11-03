package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 条件命中后要求指定字段完整，未命中（包括条件为空）直接通过。 */
public class ConditionalRequiredValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "CONDITIONAL_REQUIRED_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "条件必填校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"conditionColumn\":\"status\",\"conditionValues\":[\"ACTIVE\"],\"requiredColumns\":[\"name\"],\"blankAsNull\":true}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "conditionColumn", "conditionValues",
                "requiredColumns", "blankAsNull", "ignoreNullValue");
        result.put("conditionColumn", AuditConfigSupport.text(result.get("conditionColumn"), "conditionColumn", 256));
        result.put("conditionValues",
                AuditConfigSupport.texts(result.get("conditionValues"), "conditionValues", 1, 128));
        result.put("requiredColumns",
                AuditConfigSupport.texts(result.get("requiredColumns"), "requiredColumns", 1, 32));
        result.put("blankAsNull", AuditConfigSupport.bool(result, "blankAsNull", true));
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        List<String> requirements = new ArrayList<>();
        for (String field : AuditConfigSupport.texts(config.get("requiredColumns"), "requiredColumns", 1, 32)) {
            requirements.add(
                    AuditConfigSupport.present(context, AuditConfigSupport.field(context, field, "requiredColumns"),
                            (Boolean) config.get("blankAsNull")));
        }
        return "CASE WHEN " + AuditConfigSupport.trigger(context, config) + " THEN (" + String.join(" AND ",
                requirements) + ") ELSE TRUE END";
    }

    /**
     * 构建"违规"条件片段：默认对合规片段取反； 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）。
     */
    protected String buildInvalidFrag(AuditRuleContext context) {
        return "NOT (" + buildValidFrag(context) + ")";
    }
}
