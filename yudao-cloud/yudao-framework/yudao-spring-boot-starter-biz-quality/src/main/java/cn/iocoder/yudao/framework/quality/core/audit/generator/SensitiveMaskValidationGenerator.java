package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 校验固定脱敏结构，不推测或恢复原始敏感值。 */
public class SensitiveMaskValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "SENSITIVE_MASK_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "敏感信息脱敏校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"maskType\":\"PHONE\",\"maskChar\":\"*\",\"ignoreNullValue\":false}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "maskType", "maskChar", "regex",
                "ignoreNullValue");
        String type = AuditConfigSupport.choice(result.get("maskType"), "maskType", "PHONE", "PHONE", "ID_CARD",
                "BANK_CARD", "CUSTOM");
        String mask = result.get("maskChar") == null ? "*" : AuditConfigSupport.text(result.get("maskChar"), "maskChar",
                1);
        if (mask.length() != 1 || Character.isWhitespace(mask.charAt(0)) || Character.isISOControl(mask.charAt(0))) {
            throw new IllegalArgumentException("maskChar requires one visible BMP character");
        }
        if ("CUSTOM".equals(type)) {
            String regex = AuditConfigSupport.text(result.get("regex"), "regex", 512);
            try {
                java.util.regex.Pattern.compile(regex);
            } catch (java.util.regex.PatternSyntaxException ex) {
                throw new IllegalArgumentException("Invalid mask regex", ex);
            }
            result.put("regex", regex);
        } else if (result.containsKey("regex")) {
            throw new IllegalArgumentException("regex requires CUSTOM maskType");
        }
        result.put("maskType", type);
        result.put("maskChar", mask);
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        if (context.resolveColumns().size() != 1) {
            throw new IllegalArgumentException("Mask validation requires one column");
        }
        String mask = (String) config.get("maskChar");
        if ("\\.^$|?*+()[]{}".contains(mask)) {
            mask = "\\" + mask;
        }
        String regex = switch ((String) config.get("maskType")) {
            case "PHONE" -> "^[0-9]{3}" + mask + "{4}[0-9]{4}$";
            case "ID_CARD" -> "^[0-9]{6}" + mask + "{8}[0-9Xx]{4}$";
            case "BANK_CARD" -> "^" + mask + "{8,15}[0-9]{4}$";
            default -> (String) config.get("regex");
        };
        return AuditSqlDialect.regexpMatch(context.getProductType(),
                AuditSqlDialect.castText(context.database(), context.resolveColumns().get(0)), regex);
    }
}
