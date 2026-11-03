package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.*;

/** Flink原生有限处理时间窗口；主表单之外的明确执行合同，不给历史节点自动补默认。 */
public final class EtlNativeStreamingWindow {
    public static final String PICK_FUNCTION = "DH_WINDOW_PICK";
    private static final Set<String> KEYS = Set.of("version", "kind", "timeMode", "sizeMillis", "maxRows", "maxBytes", "emit", "latePolicy");
    public record Contract(int sizeMillis, int maxRows, int maxBytes) { }
    private EtlNativeStreamingWindow() { }

    public static Contract require(JSONObject config) {
        Object raw = config.get("streamingWindow");
        if (!(raw instanceof Map<?, ?>)) throw invalid("实时去重或长表转宽表须明确配置有限窗口");
        JSONObject window = JSONUtil.parseObj(raw);
        if (!KEYS.containsAll(window.keySet()) || !window.keySet().containsAll(KEYS)) throw invalid("实时窗口合同字段缺失或未知");
        if (number(window, "version", 1, 1) != 1 || !"TUMBLING".equals(window.getStr("kind"))
                || !"PROCESSING_TIME".equals(window.getStr("timeMode")) || !"ON_CLOSE".equals(window.getStr("emit"))
                || !"PROCESS_ON_ARRIVAL".equals(window.getStr("latePolicy"))) throw invalid("实时窗口只支持处理时间滚动窗口、关闭输出和按到达处理");
        return new Contract(number(window, "sizeMillis", 1000, 86_400_000), number(window, "maxRows", 1, 10_000), number(window, "maxBytes", 1024, 16_777_216));
    }
    public static String input(String sql, Map<String, String> fields, Contract contract) {
        if (fields.size() > 256 || fields.keySet().stream().anyMatch(name -> Set.of("window_start", "window_end", "window_time").contains(name) || name.startsWith("__dh_window_")))
            throw invalid("窗口输入超出256列或占用原生窗口保留字段");
        fields.values().forEach(EtlNativeStreamingWindow::family);
        return "WITH dh_window_clocked AS (SELECT dh_window_input.*, PROCTIME() AS `__dh_window_clock` FROM (" + sql
                + ") dh_window_input), dh_windowed AS (SELECT * FROM TABLE(TUMBLE(TABLE dh_window_clocked, DESCRIPTOR(`__dh_window_clock`), INTERVAL '"
                + java.math.BigDecimal.valueOf(contract.sizeMillis(), 3).toPlainString() + "' SECOND))) ";
    }
    public static String row(Map<String, String> fields) {
        return "ARRAY[" + String.join(", ", fields.keySet().stream().map(name -> "CAST(" + quote(name) + " AS STRING)").toList()) + "]";
    }
    public static String declaration(JSONObject config, LinkedHashMap<String, String> fields, Contract window, boolean last, boolean ordered) {
        List<String> names = new ArrayList<>(fields.keySet()); List<String> sorts = new ArrayList<>(); List<String> unique;
        if (ordered) {
            var order = config.getJSONArray("orderBy"); var keys = config.getJSONArray("uniqueFields");
            if (order == null || order.isEmpty() || keys == null || keys.isEmpty()) throw invalid("窗内首末选择须明确排序和唯一字段");
            unique = keys.toList(String.class); Set<String> sorted = new HashSet<>();
            for (Object item : order) {
                JSONObject rule = JSONUtil.parseObj(item); String name = rule.getStr("field"), direction = rule.getStr("order", "ASC");
                if (!names.contains(name) || !sorted.add(name) || !Set.of("ASC", "DESC").contains(direction)) throw invalid("窗内排序字段或方向无效");
                sorts.add(names.indexOf(name) + ":" + (direction.equals("ASC") ? "A" : "D") + ":" + family(fields.get(name)));
            }
            if (new HashSet<>(unique).size() != unique.size() || !sorted.containsAll(unique)) throw invalid("窗内排序必须包含全部不重复的唯一字段");
        } else unique = names;
        return "1|" + (last ? "L" : "F") + "|" + window.maxRows() + "|" + window.maxBytes() + "|" + String.join(",", sorts)
                + "|" + String.join(",", unique.stream().map(name -> String.valueOf(names.indexOf(name))).toList());
    }
    public static String pick(JSONObject config, LinkedHashMap<String, String> fields, Contract window, boolean last, boolean ordered) {
        return PICK_FUNCTION + "(" + row(fields) + ", '" + declaration(config, fields, window, last, ordered) + "')";
    }
    public static String deduplicate(JSONObject config, String sql, LinkedHashMap<String, String> fields) {
        Contract window = require(config);
        String strategy = config.getStr("deduplicationStrategy", "ALL_FIELDS");
        if (!Set.of("ALL_FIELDS", "KEY_FIELDS").contains(strategy)) throw invalid("去重策略无效");
        var declared = config.getJSONArray("keyFields");
        List<String> keys = strategy.equals("ALL_FIELDS") ? new ArrayList<>(fields.keySet()) : declared == null ? List.of() : declared.toList(String.class);
        if (keys.isEmpty() || new HashSet<>(keys).size() != keys.size() || !fields.keySet().containsAll(keys)
                || strategy.equals("ALL_FIELDS") && declared != null && !declared.isEmpty()) throw invalid("实时去重键字段无效");
        Set<String> insensitive;
        if (config.containsKey("ignoreCaseFields")) {
            var values = config.getJSONArray("ignoreCaseFields");
            if (values == null) throw invalid("逐字段大小写配置须为数组");
            List<String> selected = values.toList(String.class);
            if (new HashSet<>(selected).size() != selected.size() || !keys.containsAll(selected)
                    || selected.stream().anyMatch(name -> !family(fields.get(name)).equals("S"))) throw invalid("忽略大小写字段须为字符串去重键");
            insensitive = Set.copyOf(selected);
        } else insensitive = config.getBool("ignoreCase", false) ? new HashSet<>(keys.stream().filter(name -> family(fields.get(name)).equals("S")).toList()) : Set.of();
        boolean ordered = strategy.equals("KEY_FIELDS") || !insensitive.isEmpty();
        String picked = pick(config, fields, window, false, ordered);
        List<String> groups = new ArrayList<>(List.of("window_start", "window_end"));
        keys.forEach(name -> groups.add(insensitive.contains(name) ? "LOWER(" + quote(name) + ")" : quote(name)));
        List<String> projections = new ArrayList<>(); int index = 0;
        for (var field : fields.entrySet()) {
            projections.add("CAST(`__dh_picked`[" + (++index) + "] AS " + field.getValue() + ") AS " + quote(field.getKey()));
        }
        return input(sql, fields, window) + "SELECT " + String.join(", ", projections)
                + " FROM (SELECT " + picked + " AS `__dh_picked` FROM dh_windowed GROUP BY " + String.join(", ", groups) + ") dh_window_result";
    }
    public static String quote(String name) {
        if (name == null || name.isBlank() || name.codePoints().anyMatch(Character::isISOControl)) throw invalid("窗口字段名称无效");
        return "`" + name.replace("`", "``") + "`";
    }
    private static String family(String type) {
        if (type == null) throw invalid("窗口排序字段缺少类型");
        String value = type.toUpperCase(Locale.ROOT);
        if (value.matches("(?:TINYINT|SMALLINT|INT|INTEGER|BIGINT|LONG|DOUBLE|FLOAT|DECIMAL(?:\\(\\d+,\\d+\\))?)")) return "N";
        if (value.matches("(?:STRING|TEXT|VARCHAR(?:\\(\\d+\\))?|CHAR(?:\\(\\d+\\))?)")) return "S";
        if (value.equals("DATE")) return "D";
        if (value.matches("TIMESTAMP(?:\\([0-9]\\))?|DATETIME")) return "T";
        if (Set.of("BOOL", "BOOLEAN").contains(value)) return "B";
        throw invalid("窗口排序字段不是已支持的标量类型");
    }
    private static int number(JSONObject config, String key, int minimum, int maximum) {
        String value = String.valueOf(config.get(key));
        if (!value.matches("[0-9]{1,9}")) throw invalid("窗口" + key + "须为明确整数");
        long parsed = Long.parseLong(value);
        if (parsed < minimum || parsed > maximum) throw invalid("窗口" + key + "超出允许范围");
        return (int) parsed;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
