package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.time.Instant;
import java.util.*;

/** 按类型化复合键分组，优先级相同保持首条，分组输出顺序保持首次出现顺序。 */
public final class PreferredDedupCleanFunction extends ConfiguredCleanFunction {

    public enum Kind {LATEST, PRIORITY, NON_NULL}

    private final Kind kind;

    /**
     * 初始化 {@code PreferredDedupCleanFunction}及其父类状态。
     */
    public PreferredDedupCleanFunction(Kind kind) {
        super(switch (kind) {
                    case LATEST -> "PK_DEDUP_LATEST";
                    case PRIORITY -> "DEDUP_BY_PRIORITY";
                    case NON_NULL -> "DEDUP_PREFER_NON_NULL";
                },
                switch (kind) {
                    case LATEST -> "按主键去重（按时间字段保留最新）";
                    case PRIORITY -> "按优先级字段保留记录";
                    case NON_NULL -> "按非空字段优先保留记录";
                },
                switch (kind) {
                    case LATEST ->
                            "{\"columns\":[\"id\"],\"timeField\":\"update_time\",\"inputFormats\":[\"yyyy-MM-dd HH:mm:ss\"]}";
                    case PRIORITY ->
                            "{\"columns\":[\"id\"],\"priorityField\":\"source\",\"priorityValues\":[\"系统录入\",\"Excel导入\"],\"timeField\":\"update_time\"}";
                    case NON_NULL ->
                            "{\"columns\":[\"id\"],\"preferredFields\":[\"phone\",\"name\"],\"timeField\":\"update_time\"}";
                });
        this.kind = kind;
    }

    /**
     * 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。
     */
    @Override
    public ExecutionScope executionScope() {
        return ExecutionScope.ROW_SET;
    }

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    @Override
    public ColumnBinding getColumnBinding() {
        return ColumnBinding.MULTIPLE;
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = switch (kind) {
            case LATEST -> checked(config, "timeField", "inputFormats");
            case PRIORITY -> checked(config, "priorityField", "priorityValues", "timeField", "inputFormats");
            case NON_NULL -> checked(config, "preferredFields", "timeField", "inputFormats");
        };
        if (kind == Kind.LATEST || result.containsKey("timeField")) {
            result.put("timeField", field(result, "timeField"));
        }
        if (kind == Kind.PRIORITY) {
            result.put("priorityField", field(result, "priorityField"));
            result.put("priorityValues", strings(result, "priorityValues", List.of(), true, 128, 128));
        } else if (kind == Kind.NON_NULL) {
            result.put("preferredFields", strings(result, "preferredFields", List.of(), true, 32, 128));
        }
        result.put("inputFormats", formats(result));
        return result;
    }

    /**
     * 目标字段之外还必须存在的排序、优先级或更新字段。
     */
    @Override
    public List<String> referencedColumns(Map<String, Object> config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.get("timeField") instanceof String field) {
            result.add(field);
        }
        if (config.get("priorityField") instanceof String field) {
            result.add(field);
        }
        if (config.get("preferredFields") instanceof List<?> fields) {
            fields.forEach(value -> result.add((String) value));
        }
        return List.copyOf(result);
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> input) {
        var config = normalizeConfig(input);
        List<String> columns = CleanValueSupport.requiredColumns(config), required = referencedColumns(config);
        Map<List<?>, Map<String, Object>> selected = new LinkedHashMap<>();
        for (var row : rows) {
            CleanExecutionControl.checkpoint();
            if (!row.keySet().containsAll(columns) || !row.keySet().containsAll(required)) {
                throw new IllegalArgumentException("去重绑定或优先字段不在输入行中");
            }
            List<?> key = CleanValueSupport.compositeKey(row, columns);
            var previous = selected.get(key);
            if (previous == null) {
                CleanExecutionControl.retainOutput(row);
                selected.put(key, row);
            } else if (compare(row, previous, config) > 0) {
                selected.put(key, row);
            }
        }
        return new ArrayList<>(selected.values());
    }

    /**
     * 返回当前比较。
     */
    private int compare(Map<String, Object> candidate, Map<String, Object> previous, Map<String, Object> config) {
        int difference = switch (kind) {
            case LATEST -> 0;
            case PRIORITY -> Integer.compare(priority(previous, config), priority(candidate, config));
            case NON_NULL -> Integer.compare(completeness(candidate, config), completeness(previous, config));
        };
        if (difference != 0 || !config.containsKey("timeField")) {
            return difference;
        }
        Instant left = time(candidate.get(config.get("timeField")), config), right = time(
                previous.get(config.get("timeField")), config);
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    /**
     * 返回当前优先级。
     */
    private static int priority(Map<String, Object> row, Map<String, Object> config) {
        Object value = row.get(config.get("priorityField"));
        List<?> priorities = (List<?>) config.get("priorityValues");
        int position = value == null ? -1 : priorities.indexOf(boundedText(value));
        return position < 0 ? priorities.size() : position;
    }

    /**
     * 返回当前完整性。
     */
    private static int completeness(Map<String, Object> row, Map<String, Object> config) {
        int score = 0;
        for (Object field : (List<?>) config.get("preferredFields")) {
            if (!empty(row.get(field))) {
                score++;
            }
        }
        return score;
    }

    /**
     * 读取配置中的时间并按类型约定校验。
     */
    @SuppressWarnings("unchecked")
    private static Instant time(Object value, Map<String, Object> config) {
        var date = CleanDateSupport.parse(value, (List<String>) config.get("inputFormats"));
        return date == null ? null : date.toInstant();
    }

    /**
     * 返回字段。
     */
    private static String field(Map<String, Object> config, String key) {
        String result = text(config, key, null, true, 128, false);
        if (!result.equals(result.trim())) {
            throw new IllegalArgumentException("引用字段不能包含首尾空格");
        }
        return result;
    }
}
