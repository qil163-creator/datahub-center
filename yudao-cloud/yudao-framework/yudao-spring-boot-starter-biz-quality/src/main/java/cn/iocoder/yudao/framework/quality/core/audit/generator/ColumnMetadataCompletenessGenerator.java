package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 每个字段计一个稽查单位，同一字段的多个缺失属性合并为一个问题。 */
public class ColumnMetadataCompletenessGenerator extends AbstractMetadataValidationGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "COLUMN_METADATA_COMPLETENESS";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "字段元信息完整性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"requiredProperties\":[\"name\",\"type\",\"comment\"]}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "requiredProperties");
        List<String> properties = result.get("requiredProperties") == null ? List.of("name", "type", "comment")
                : AuditConfigSupport.texts(result.get("requiredProperties"), "requiredProperties", 1, 7);
        if (new HashSet<>(properties).size() != properties.size() || !Set.of("name", "type", "comment", "nullable",
                "length", "precision", "scale").containsAll(properties)) {
            throw new IllegalArgumentException("Invalid requiredProperties");
        }
        result.put("requiredProperties", properties);
        return result;
    }

    /**
     * 根据输入行和规则配置计算数据质量结果。
     */
    protected List<Assessment> assess(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        List<Assessment> result = new ArrayList<>();
        for (AuditRuleContext.ColumnSnapshot column : snapshots(context)) {
            Map<String, Object> values = new HashMap<>();
            values.put("name", column.name());
            values.put("type", column.type());
            values.put("comment", column.comment());
            values.put("nullable", column.nullable());
            values.put("length", column.length());
            values.put("precision", column.precision());
            values.put("scale", column.scale());
            List<String> absent = new ArrayList<>();
            for (String property : AuditConfigSupport.texts(config.get("requiredProperties"), "requiredProperties", 1,
                    7)) {
                Object value = values.get(property);
                if (value == null || value instanceof String text && text.isBlank() || value instanceof Integer number && number < 0) {
                    absent.add(property);
                }
            }
            result.add(new Assessment(column.name(),
                    absent.isEmpty() ? "元信息完整" : "缺失元信息：" + String.join(",", absent), !absent.isEmpty()));
        }
        return result;
    }
}
