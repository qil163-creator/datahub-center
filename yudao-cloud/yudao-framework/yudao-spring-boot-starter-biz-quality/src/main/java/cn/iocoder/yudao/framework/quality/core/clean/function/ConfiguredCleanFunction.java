package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 同族清洗策略共用的元数据与严格配置能力，不承载业务目录或权限。 */
abstract class ConfiguredCleanFunction implements CleanFunction {

    static final List<String> DEFAULT_FORMATS = List.of("yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd", "yyyyMMdd",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "timestamp");

    private final String key, name, example;

    /**
     * 初始化 {@code ConfiguredCleanFunction}。
     */
    ConfiguredCleanFunction(String key, String name, String example) {
        this.key = key;
        this.name = name;
        this.example = example;
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public final String getStrategyKey() {
        return key;
    }

    /**
     * 策略名称。
     */
    @Override
    public final String getStrategyName() {
        return name;
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public final String getConfigExample() {
        return example;
    }

    /**
     * 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。
     */
    @Override
    public ExecutionScope executionScope() {
        return ExecutionScope.ROW;
    }

    /**
     * 检查配置、键集合是否满足当前操作的约定。
     */
    static Map<String, Object> checked(Map<String, Object> config, String... keys) {
        Set<String> allowed = new HashSet<>(List.of(keys));
        allowed.add("columns");
        return CleanFunctionConfigSupport.checked(config, allowed);
    }

    /**
     * 读取配置中的文本并按类型约定校验。
     */
    static String text(Map<String, Object> config, String key, String fallback, boolean required, int max,
            boolean empty) {
        return CleanFunctionConfigSupport.string(config, key, fallback, required, max, empty);
    }

    /**
     * 读取并校验当前配置值属于允许的选项集合。
     */
    static String option(Map<String, Object> config, String key, String fallback, String... choices) {
        String value = text(config, key, fallback, false, 32, false).toUpperCase(Locale.ROOT);
        if (!Set.of(choices).contains(value)) {
            throw new IllegalArgumentException("清洗参数 " + key + " 选项无效");
        }
        return value;
    }

    /**
     * 读取配置中的布尔值并按类型约定校验。
     */
    static boolean bool(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("清洗参数 " + key + " 必须是布尔值");
        }
        return flag;
    }

    /**
     * 读取并整理当前输入中的字符串集合。
     */
    static List<String> strings(Map<String, Object> config, String key, List<String> fallback, boolean required,
            int count, int length) {
        List<String> values = CleanFunctionConfigSupport.stringList(config, key, fallback, required, count, length);
        if (required && values.isEmpty() || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("清洗参数 " + key + " 不能为空或重复");
        }
        return values;
    }

    /**
     * 读取并验证当前清洗策略允许的输入日期格式。
     */
    static List<String> formats(Map<String, Object> config) {
        List<String> result = strings(config, "inputFormats", DEFAULT_FORMATS, false, 16, 64);
        for (String format : result) {
            if (!"timestamp".equalsIgnoreCase(format)) {
                CleanDateSupport.formatter(format);
            }
        }
        return result;
    }

    /**
     * 读取并校验日期输出格式。
     */
    static String outputFormat(Map<String, Object> config) {
        String result = text(config, "outputFormat", "yyyy-MM-dd HH:mm:ss", false, 64, false);
        DateTimeFormatter.ofPattern(result, Locale.ROOT);
        return result;
    }

    /**
     * 读取并校验长度受限的文本值。
     */
    static String boundedText(Object value) {
        String result = String.valueOf(value);
        if (result.length() > CleanRegexSupport.MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("清洗输入超过字符上限");
        }
        return result;
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    static BigDecimal number(Object value) {
        if (!(value instanceof CharSequence || value instanceof Number)) {
            return null;
        }
        String text = value.toString().trim();
        if (text.length() > 128 || !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            return null;
        }
        try {
            BigDecimal result = new BigDecimal(text);
            return result.precision() <= 64 && Math.abs((long) result.scale()) <= 32 ? result : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * 读取配置中的精确小数并按类型约定校验。
     */
    static String decimal(Map<String, Object> config, String key, String fallback, boolean required) {
        return CleanFunctionConfigSupport.decimal(config, key, fallback, required);
    }

    /**
     * 根据给定参数创建 {@code boolean} 空实例。
     */
    static boolean empty(Object value) {
        return CleanValueSupport.isBlankValue(
                value) || value instanceof Double d && d.isNaN() || value instanceof Float f && f.isNaN();
    }

    /**
     * 构造表示输入或状态不符合约定的异常。
     */
    static Object invalid(Object value, String policy) {
        return switch (policy) {
            case "KEEP" -> value;
            case "NULL" -> null;
            default -> throw new IllegalArgumentException("字段值无法按配置转换");
        };
    }

    /**
     * 整理布尔清洗策略的真值、假值及对应转换规则。
     */
    static void booleanConfig(Map<String, Object> result) {
        List<String> yes = strings(result, "trueValues", List.of("true", "1", "是", "yes"), false, 128, 128);
        List<String> no = strings(result, "falseValues", List.of("false", "0", "否", "no"), false, 128, 128);
        if (yes.isEmpty() || no.isEmpty()) {
            throw new IllegalArgumentException("布尔真值和假值集合不能为空");
        }
        boolean ignoreCase = bool(result, "ignoreCase", true);
        Set<String> seen = ignoreCase ? new TreeSet<>(String.CASE_INSENSITIVE_ORDER) : new HashSet<>();
        for (String value : yes) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException("布尔值配置重复");
            }
        }
        for (String value : no) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException("布尔真值和假值不能重叠");
            }
        }
        result.put("trueValues", yes);
        result.put("falseValues", no);
        result.put("ignoreCase", ignoreCase);
    }

    /**
     * 检查并返回配置中的布尔值。
     */
    static Boolean booleanValue(Object value, Map<String, Object> config) {
        if (value == null) {
            return null;
        }
        String text = boundedText(value).trim();
        boolean ignoreCase = Boolean.TRUE.equals(config.get("ignoreCase"));
        for (Object yes : (List<?>) config.get("trueValues")) {
            if (ignoreCase ? text.equalsIgnoreCase((String) yes) : text.equals(yes)) {
                return true;
            }
        }
        for (Object no : (List<?>) config.get("falseValues")) {
            if (ignoreCase ? text.equalsIgnoreCase((String) no) : text.equals(no)) {
                return false;
            }
        }
        return null;
    }
}
