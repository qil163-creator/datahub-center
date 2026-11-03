package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举值映射标准化：按配置字典映射替换字段值（未命中保留原值）
 *
 * <p>config.stringValue: [{"value": "1", "name": "启用"}, {"value": "0", "name": "停用"}]
 *
 * @author Li
 */
public class MenuCustomFunction implements CleanFunction {

    /**
     * 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。
     */
    @Override
    public ExecutionScope executionScope() {
        return ExecutionScope.ROW;
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "MENU_CUSTOM";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "枚举值映射标准化";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"status\"], \"stringValue\": [{\"value\": \"1\", \"name\": \"启用\"}, {\"value\": \"0\", \"name\": \"停用\"}]}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "stringValue"));
        Object raw = result.get("stringValue");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 1000) {
            throw new IllegalArgumentException("枚举映射项必须为 1 至 1000 条");
        }
        List<Map<String, String>> normalized = new java.util.ArrayList<>(values.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> entry) || !entry.keySet().equals(java.util.Set.of("value", "name"))
                    || !(entry.get("value") instanceof String value) || value.length() > 128
                    || !(entry.get("name") instanceof String name) || name.length() > 256
                    || !seen.add(value)) {
                throw new IllegalArgumentException("枚举映射项类型、字段或唯一性无效");
            }
            normalized.add(Map.of("value", value, "name", name));
        }
        result.put("stringValue", List.copyOf(normalized));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        Object raw = config.get("stringValue");
        if (!(raw instanceof List) || ((List<Object>) raw).isEmpty()) {
            throw new IllegalArgumentException("枚举映射需要配置 stringValue（字典映射列表）");
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Object item : (List<Object>) raw) {
            Map<String, Object> dict = (Map<String, Object>) item;
            mapping.put(MapUtil.getStr(dict, "value"), MapUtil.getStr(dict, "name"));
        }
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            String key = String.valueOf(value);
            return mapping.containsKey(key) ? mapping.get(key) : value;
        });
    }

}
