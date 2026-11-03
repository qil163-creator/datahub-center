package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 冻结的预期字段规格与可信实际表结构逐字段比较。 */
public class ModelStructureValidationGenerator extends AbstractMetadataValidationGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    public String getStrategyKey() {
        return "MODEL_STRUCTURE_VALIDATION";
    }

    /**
     * 策略名称。
     */
    public String getStrategyName() {
        return "模型结构符合性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    public String getConfigExample() {
        return "{\"expectedColumns\":[{\"name\":\"id\",\"type\":\"BIGINT\",\"nullable\":false}],\"allowExtraColumns\":false}";
    }

    /**
     * 旧策略保留原配置，新策略可收紧参数契约。
     */
    public Map<String, Object> normalizeConfig(Map<String, Object> input) {
        Map<String, Object> result = AuditConfigSupport.config(input, "expectedColumns", "allowExtraColumns",
                "compareType", "compareNullable", "compareLength");
        List<Map<String, Object>> columns = AuditConfigSupport.objects(result.get("expectedColumns"), "expectedColumns",
                1, 2048);
        Set<String> names = new HashSet<>();
        for (Map<String, Object> column : columns) {
            AuditConfigSupport.config(column, "name", "type", "nullable", "length", "precision", "scale");
            String name = AuditConfigSupport.text(column.get("name"), "name", 256);
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate expected column: " + name);
            }
            column.put("name", name);
            if (column.containsKey("type")) {
                column.put("type", AuditConfigSupport.text(column.get("type"), "type", 256));
            }
            if (column.containsKey("nullable")) {
                column.put("nullable", AuditConfigSupport.bool(column, "nullable", false));
            }
            for (String property : List.of("length", "precision", "scale")) {
                if (column.containsKey(property)) {
                    column.put(property,
                            AuditConfigSupport.integer(column.get(property), property, 0, Integer.MAX_VALUE));
                }
            }
            if (column.containsKey("scale") && column.containsKey("precision") && (Integer) column.get(
                    "scale") > (Integer) column.get("precision")) {
                throw new IllegalArgumentException("Expected scale must not exceed precision");
            }
        }
        result.put("expectedColumns", columns);
        result.put("allowExtraColumns", AuditConfigSupport.bool(result, "allowExtraColumns", false));
        for (String key : List.of("compareType", "compareNullable", "compareLength")) {
            result.put(key, AuditConfigSupport.bool(result, key, true));
        }
        return result;
    }

    /**
     * 根据输入行和规则配置计算数据质量结果。
     */
    protected List<Assessment> assess(AuditRuleContext context) {
        Map<String, Object> config = normalizeConfig(context.getConfig());
        Map<String, AuditRuleContext.ColumnSnapshot> actual = new LinkedHashMap<>();
        for (AuditRuleContext.ColumnSnapshot column : snapshots(context)) {
            String name = AuditConfigSupport.text(column.name(), "snapshot.name", 256);
            if (actual.put(name, column) != null) {
                throw new IllegalArgumentException("Duplicate actual column: " + name);
            }
        }
        List<Assessment> result = new ArrayList<>();
        for (Map<String, Object> expected : AuditConfigSupport.objects(config.get("expectedColumns"), "expectedColumns",
                1, 2048)) {
            String name = (String) expected.get("name");
            AuditRuleContext.ColumnSnapshot column = actual.remove(name);
            List<String> errors = new ArrayList<>();
            if (column == null) {
                errors.add("字段缺失");
            } else {
                if ((Boolean) config.get("compareType") && expected.containsKey("type")
                        && !normalizedType((String) expected.get("type")).equals(normalizedType(column.type()))) {
                    errors.add("字段类型不一致");
                }
                if ((Boolean) config.get("compareNullable") && expected.containsKey("nullable")
                        && !Objects.equals(expected.get("nullable"), column.nullable())) {
                    errors.add("可空性不一致");
                }
                if ((Boolean) config.get("compareLength")) {
                    Map<String, Integer> properties = new HashMap<>();
                    properties.put("length", column.length());
                    properties.put("precision", column.precision());
                    properties.put("scale", column.scale());
                    for (String property : List.of("length", "precision", "scale")) {
                        if (expected.containsKey(property)
                                && !Objects.equals(expected.get(property), properties.get(property))) {
                            errors.add(property + "不一致");
                        }
                    }
                }
            }
            result.add(new Assessment(name, errors.isEmpty() ? "符合预期字段规格" : String.join("；", errors),
                    !errors.isEmpty()));
        }
        if (!(Boolean) config.get("allowExtraColumns")) {
            for (String extra : actual.keySet()) {
                result.add(new Assessment(extra, "预期模型之外的字段", true));
            }
        }
        return result;
    }

    /**
     * 整理类型文本的空白和大小写，供结构比较使用。
     */
    private static String normalizedType(String type) {
        return type == null ? "" : type.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
