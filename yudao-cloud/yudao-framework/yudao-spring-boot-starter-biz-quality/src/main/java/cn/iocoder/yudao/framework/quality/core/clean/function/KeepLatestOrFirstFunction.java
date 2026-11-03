package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanExecutionControl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按组合字段去重（保留最新或首条）：联合唯一键去重，可配置组内排序保留策略
 *
 * <p>config.stringValue：组内排序规则 [{"columns": "update_time", "type": 0, "sort": 1}]，
 * type 0-倒序（保留最新）、1-正序（保留最早）；未配置排序时保留首条
 *
 * @author Li
 */
public class KeepLatestOrFirstFunction implements CleanFunction {

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    @Override
    public ColumnBinding getColumnBinding() {
        return ColumnBinding.MULTIPLE;
    }

    /**
     * 目标字段之外还必须存在的排序、优先级或更新字段。
     */
    @Override
    public List<String> referencedColumns(Map<String, Object> config) {
        if (!(config.get("stringValue") instanceof List<?> rules)) {
            return List.of();
        }
        return rules.stream().map(rule -> (String) ((Map<?, ?>) rule).get("columns")).distinct().toList();
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "KEEP_LATEST_OR_FIRST";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "按组合字段去重（保留最新或首条）";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"user_id\", \"year\"], \"stringValue\": [{\"columns\": \"update_time\", \"type\": 0, \"sort\": 1}]}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "stringValue"));
        Object raw = result.get("stringValue");
        if (raw == null) {
            result.put("stringValue", List.of());
            return result;
        }
        if (!(raw instanceof List<?> values) || values.size() > 32) {
            throw new IllegalArgumentException("排序字段必须是至多 32 项的数组");
        }
        List<Map<String, Object>> normalized = new ArrayList<>(values.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> entry) || !entry.keySet()
                    .equals(java.util.Set.of("columns", "type", "sort"))) {
                throw new IllegalArgumentException("排序项必须只包含 columns、type、sort");
            }
            Object columnValue = entry.get("columns");
            if (!(columnValue instanceof String column) || column.isBlank() || column.length() > 128 || !seen.add(
                    column)) {
                throw new IllegalArgumentException("排序字段名无效或重复");
            }
            Map<String, Object> valuesMap = new java.util.HashMap<>();
            valuesMap.put("type", entry.get("type"));
            valuesMap.put("sort", entry.get("sort"));
            int type = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(valuesMap,
                    "type", -1, 0, 1);
            int sort = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(valuesMap,
                    "sort", -1, -100_000, 100_000);
            normalized.add(Map.of("columns", column, "type", type, "sort", sort));
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
        List<String> allColumns = CleanValueSupport.requiredColumns(config);
        List<Map<String, Object>> sortRules = new ArrayList<>();
        Object raw = config.get("stringValue");
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item instanceof Map) {
                    sortRules.add((Map<String, Object>) item);
                }
            }
        }
        // 无排序规则：按键保留首条
        if (sortRules.isEmpty()) {
            return dedupKeepFirst(rows, allColumns);
        }
        // 分组键 = columns - 排序字段
        List<String> sortColumns = sortRules.stream()
                .map(rule -> MapUtil.getStr(rule, "columns"))
                .filter(column -> column != null && !column.isEmpty())
                .collect(Collectors.toList());
        List<String> groupColumns = allColumns.stream()
                .filter(column -> !sortColumns.contains(column))
                .collect(Collectors.toList());
        if (groupColumns.isEmpty()) {
            groupColumns = allColumns;
        }
        sortRules.sort(Comparator.comparingInt(rule -> MapUtil.getInt(rule, "sort", 0)));

        Map<List<?>, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            CleanExecutionControl.checkpoint();
            List<?> key = CleanValueSupport.compositeKey(row, groupColumns);
            Map<String, Object> current = best.get(key);
            if (current == null) {
                CleanExecutionControl.retainOutput(row);
            }
            if (current == null || compare(row, current, sortRules) < 0) {
                best.put(key, row);
            }
        }
        return new ArrayList<>(best.values());
    }

    /**
     * 根据当前输入构建并返回 {@code ArrayList}。
     */
    private List<Map<String, Object>> dedupKeepFirst(List<Map<String, Object>> rows, List<String> columns) {
        Map<List<?>, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            CleanExecutionControl.checkpoint();
            List<?> key = CleanValueSupport.compositeKey(row, columns);
            if (!best.containsKey(key)) {
                CleanExecutionControl.retainOutput(row);
                best.put(key, row);
            }
        }
        return new ArrayList<>(best.values());
    }

    /**
     * row 应排在 current 前返回负数（即优先保留）
     */
    private int compare(Map<String, Object> row, Map<String, Object> current, List<Map<String, Object>> sortRules) {
        for (Map<String, Object> rule : sortRules) {
            String column = MapUtil.getStr(rule, "columns");
            int type = MapUtil.getInt(rule, "type", 0);
            int result = compareValue(row.get(column), current.get(column));
            if (result != 0) {
                // type=0 倒序（值大者优先，保留最新）；type=1 正序（值小者优先，保留最早）
                return type == 0 ? -result : result;
            }
        }
        return 1; // 完全相等时保留先出现的
    }

    /**
     * 比较值。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValue(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Comparable && left.getClass().isInstance(right)) {
            return ((Comparable) left).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

}
