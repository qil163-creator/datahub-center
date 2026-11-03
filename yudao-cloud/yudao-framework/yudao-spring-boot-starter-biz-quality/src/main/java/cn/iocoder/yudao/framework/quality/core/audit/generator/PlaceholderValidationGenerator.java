package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.AuditConfigSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 非法占位值识别：字段值不得为系统约定的占位/无效值
 *
 * <p>config: {"values": ["-", "N/A", "null", "暂无"], "trim": true}
 *
 * @author Li
 */
public class PlaceholderValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "PLACEHOLDER_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "非法占位值识别";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"values\": [\"-\", \"N/A\", \"null\"], \"ignoreNullValue\": false}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "values", "trim", "ignoreCase",
                "ignoreNullValue");
        if (!(result.get("values") instanceof List<?> values) || values.isEmpty() || values.size() > 128) {
            throw new IllegalArgumentException("Placeholder values require 1..128 text values");
        }
        List<String> normalized = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.length() > 1024 || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Placeholder values require bounded text");
            }
            normalized.add(text);
        }
        result.put("values", normalized);
        result.put("trim", AuditConfigSupport.bool(result, "trim", false));
        result.put("ignoreCase", AuditConfigSupport.bool(result, "ignoreCase", true));
        result.put("ignoreNullValue", AuditConfigSupport.bool(result, "ignoreNullValue", false));
        return result;
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        List<?> raw = (List<?>) config.get("values");
        List<String> frags = new ArrayList<>();
        String left = AuditSqlDialect.castText(context.database(), context.getColumn());
        if ((Boolean) config.get("trim")) {
            left = "TRIM(" + left + ")";
        }
        for (Object value : raw) {
            String v = (String) value;
            if ((Boolean) config.get("trim")) {
                v = v.trim();
            }
            String right = AuditSqlDialect.stringLiteral(context.getProductType(), v);
            frags.add((Boolean) config.get("ignoreCase") ? "UPPER(" + left + ") <> UPPER(" + right + ")"
                    : AuditSqlDialect.caseSensitive(context.getProductType(), left) + " <> " + right);
        }
        return String.join(" AND ", frags);
    }

}
