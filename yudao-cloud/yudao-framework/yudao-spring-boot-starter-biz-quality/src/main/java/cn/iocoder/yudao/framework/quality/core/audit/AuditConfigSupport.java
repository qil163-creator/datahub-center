package cn.iocoder.yudao.framework.quality.core.audit;

import java.math.BigDecimal;
import java.util.*;

/** 新增稽查策略共用的有界配置契约；字段必须由调用方绑定到可信 SQL 标识符。 */
public final class AuditConfigSupport {

    /**
     * 初始化 {@code AuditConfigSupport}。
     */
    private AuditConfigSupport() {
    }

    /**
     * 返回配置。
     */
    public static Map<String, Object> config(Map<String, Object> input, String... keys) {
        Map<String, Object> result = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        Set<String> allowed = new HashSet<>(Arrays.asList(keys));
        for (String key : result.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown audit config: " + key);
            }
        }
        return result;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    public static String text(Object value, String name, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max || s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " requires non-blank bounded text");
        }
        return s;
    }

    /**
     * 读取配置中的布尔值并按类型约定校验。
     */
    public static boolean bool(Map<String, Object> config, String key, boolean fallback) {
        if (!config.containsKey(key)) {
            return fallback;
        }
        Object value = config.get(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(key + " requires boolean");
        }
        return (Boolean) value;
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    public static int integer(Object value, String name, int min, int max) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " requires integer");
        }
        try {
            int result = new BigDecimal(number.toString()).intValueExact();
            if (result < min || result > max) {
                throw new IllegalArgumentException(name + " is out of range");
            }
            return result;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " requires integer", ex);
        }
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    public static String decimal(Object value, String name) {
        String s = text(value, name, 256);
        if (!s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw new IllegalArgumentException(name + " requires plain decimal text");
        }
        BigDecimal decimal = new BigDecimal(s);
        if (decimal.precision() > 128 || Math.max(0, decimal.scale()) > 64) {
            throw new IllegalArgumentException(name + " decimal exceeds precision budget");
        }
        return decimal.toPlainString();
    }

    /**
     * 读取数量及长度受限的字符串列表。
     */
    public static List<String> texts(Object value, String name, int min, int max) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw new IllegalArgumentException(name + " requires bounded array");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(text(item, name, 1024));
        }
        return List.copyOf(result);
    }

    /**
     * 返回当前对象集合。
     */
    public static List<Map<String, Object>> objects(Object value, String name, int min, int max) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw new IllegalArgumentException(name + " requires bounded object array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
                throw new IllegalArgumentException(name + " requires objects");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, v));
            result.add(copy);
        }
        return result;
    }

    /**
     * 返回当前选项。
     */
    public static String choice(Object value, String name, String fallback, String... choices) {
        String result = value == null ? fallback : text(value, name, 64);
        if (!Arrays.asList(choices).contains(result)) {
            throw new IllegalArgumentException(name + " has unsupported value");
        }
        return result;
    }

    /**
     * 返回字段。
     */
    public static String field(AuditRuleContext context, Object value, String name) {
        String column = text(value, name, 256);
        if (!context.resolveColumns().contains(column)) {
            throw new IllegalArgumentException(name + " must be a bound audit column");
        }
        return column;
    }

    /**
     * 返回当前存在性。
     */
    public static String present(AuditRuleContext context, String column, boolean blankAsNull) {
        return "(" + column + " IS NOT NULL" + (blankAsNull ? " AND " + AuditSqlDialect.charLength(
                context.getProductType(),
                "TRIM(" + AuditSqlDialect.castText(context.database(), column) + ")") + " > 0" : "") + ")";
    }

    /**
     * 返回当前字面量。
     */
    public static String literal(AuditRuleContext context, String value) {
        return AuditSqlDialect.stringLiteral(context.getProductType(), value);
    }

    /**
     * 返回当前触发器。
     */
    public static String trigger(AuditRuleContext context, Map<String, Object> config) {
        String field = field(context, config.get("conditionColumn"), "conditionColumn");
        String values = texts(config.get("conditionValues"), "conditionValues", 1, 128).stream()
                .map(v -> literal(context, v)).collect(java.util.stream.Collectors.joining(", "));
        return field + " IS NOT NULL AND " + AuditSqlDialect.caseSensitive(context.getProductType(),
                AuditSqlDialect.castText(context.database(), field)) + " IN (" + values + ")";
    }
}
