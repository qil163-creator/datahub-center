package cn.iocoder.yudao.framework.quality.core.clean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 已注册清洗函数共用的严格有界配置基础能力。 */
public final class CleanFunctionConfigSupport {

    /**
     * 初始化 {@code CleanFunctionConfigSupport}。
     */
    private CleanFunctionConfigSupport() {
    }

    /**
     * 检查输入、允许键集合是否满足当前操作的约定。
     */
    public static Map<String, Object> checked(Map<String, Object> input, Set<String> allowedKeys) {
        Map<String, Object> source = input == null ? Map.of() : input;
        if (source.size() > 16 || source.keySet().stream().anyMatch(key -> key == null || !allowedKeys.contains(key))) {
            throw new IllegalArgumentException("清洗函数配置包含未知字段");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!"columns".equals(key)) {
                result.put(key, value);
            }
        });
        Object rawColumns = source.get("columns");
        if (rawColumns != null) {
            if (!(rawColumns instanceof List<?> columns) || columns.isEmpty() || columns.size() > 32) {
                throw new IllegalArgumentException("清洗字段必须是 1 至 32 个字段名的数组");
            }
            List<String> normalized = new ArrayList<>(columns.size());
            for (Object value : columns) {
                if (!(value instanceof String field) || field.isBlank() || field.length() > 128) {
                    throw new IllegalArgumentException("清洗字段名类型或长度无效");
                }
                normalized.add(field);
            }
            if (normalized.stream().distinct().count() != normalized.size()) {
                throw new IllegalArgumentException("清洗字段不能重复");
            }
            result.put("columns", List.copyOf(normalized));
        } else if (source.containsKey("columns")) {
            throw new IllegalArgumentException("清洗字段不能为空值");
        }
        return result;
    }

    /**
     * 读取配置中的字符串并按类型约定校验。
     */
    public static String string(Map<String, Object> source, String key, String defaultValue,
            boolean required, int maxLength, boolean allowEmpty) {
        Object raw = source.get(key);
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("缺少清洗参数 " + key);
            }
            return defaultValue;
        }
        if (!(raw instanceof String value) || value.length() > maxLength || (!allowEmpty && value.isBlank())) {
            throw new IllegalArgumentException("清洗参数 " + key + " 类型或长度无效");
        }
        return value;
    }

    /**
     * 读取配置中的整数并按类型约定校验。
     */
    public static int integer(Map<String, Object> source, String key, int defaultValue, int min, int max) {
        Object raw = source.get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value;
            if (raw instanceof Number number) {
                value = new BigDecimal(number.toString()).intValueExact();
            } else if (raw instanceof String text && text.matches("[+-]?\\d{1,10}")) {
                value = Integer.parseInt(text);
            } else {
                throw new NumberFormatException();
            }
            if (value < min || value > max) {
                throw new NumberFormatException();
            }
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("清洗参数 " + key + " 必须在 " + min + " 至 " + max + " 之间", ex);
        }
    }

    /**
     * 读取配置中的精确小数并按类型约定校验。
     */
    public static String decimal(Map<String, Object> source, String key, String defaultValue, boolean required) {
        Object raw = source.get(key);
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("缺少清洗参数 " + key);
            }
            return defaultValue;
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("清洗参数 " + key + " 必须使用十进制字符串以保持精度");
        }
        try {
            if (text.length() > 128) {
                throw new NumberFormatException();
            }
            BigDecimal value = new BigDecimal(text);
            if (value.precision() > 64 || Math.abs((long) value.scale()) > 32) {
                throw new NumberFormatException();
            }
            return value.toPlainString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("清洗参数 " + key + " 不是有效十进制数", ex);
        }
    }

    /**
     * 读取配置中的受限字符串列表，并校验必填约束。
     */
    public static List<String> stringList(Map<String, Object> source, String key, List<String> defaultValue,
            boolean required, int maxItems, int maxLength) {
        Object raw = source.get(key);
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("缺少清洗参数 " + key);
            }
            return defaultValue;
        }
        if (!(raw instanceof List<?> values) || values.size() > maxItems) {
            throw new IllegalArgumentException("清洗参数 " + key + " 必须是有界字符串数组");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > maxLength) {
                throw new IllegalArgumentException("清洗参数 " + key + " 数组项类型或长度无效");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    /**
     * 检查清洗配置只包含当前策略允许的键。
     */
    public static void only(Map<String, Object> input, String... keys) {
        Map<String, Object> source = input == null ? Map.of() : input;
        Set<String> allowed = Set.of(keys);
        if (source.keySet().stream().anyMatch(key -> !allowed.contains(key))) {
            throw new IllegalArgumentException("清洗函数配置包含未知字段");
        }
    }
}
