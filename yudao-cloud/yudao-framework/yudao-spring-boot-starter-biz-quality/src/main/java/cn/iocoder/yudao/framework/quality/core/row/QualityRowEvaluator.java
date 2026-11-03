package cn.iocoder.yudao.framework.quality.core.row;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import cn.iocoder.yudao.framework.quality.core.regex.BudgetedRegexText;

/**
 * 编译有限的纯 ROW 规则计划。不涉及 I/O、连接、租户、日志或可变共享状态。
 * 配置名及默认空值处理遵循现有 SQL 策略。文本比较使用
 * Unicode/Locale.ROOT（不使用任意数据库排序规则）；数值比较使用 BigDecimal。
 * REGEX 使用 Java Pattern.find()，类似 SQL 正则搜索，并明确限制表达式、输入和工作量。
 * 不保证不同数据库间的正则方言等价。拒绝 ROW_SET 和 SQL 规则。
 */
public class QualityRowEvaluator {

    public static final int MAX_RULES = 256;

    public static final int MAX_TEXT_LENGTH = 65_536;

    public static final int MAX_REGEX_LENGTH = 512;

    public static final int MAX_REGEX_INPUT_LENGTH = 4_096;

    public static final int MAX_REGEX_STEPS = 100_000;

    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR");

    private static final Set<String> STRATEGIES = Set.of("NOT_NULL_VALIDATION", "LENGTH_VALIDATION",
            "ENUM_VALIDATION", "NUMERIC_RANGE_VALIDATION", "REGEX_VALIDATION");

    /**
     * 返回策略集合。
     */
    public Set<String> supportedStrategies() {
        return STRATEGIES;
    }

    /**
     * 返回当前编译。
     */
    public CompiledPlan compile(List<QualityRowRule> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("A quality row plan requires 1.." + MAX_RULES + " rules");
        }
        Set<String> codes = new HashSet<>();
        List<CompiledRule> compiled = new ArrayList<>();
        for (QualityRowRule rule : rules) {
            Objects.requireNonNull(rule, "Quality row rule is required");
            String code = required(rule.code(), "rule code");
            if (!codes.add(code)) {
                throw new IllegalArgumentException("Duplicate quality rule code: " + code);
            }
            String field = required(rule.field(), "field");
            String strategy = normalizeStrategy(rule.strategy());
            String severity = rule.severity() == null ? "ERROR" : rule.severity().toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(severity)) {
                throw new IllegalArgumentException("Unsupported quality severity: " + severity);
            }
            Map<String, Object> config = rule.config() == null ? Map.of() : rule.config();
            boolean ignoreNull = bool(config, "ignoreNullValue", false);
            Function<Object, Failure> test = switch (strategy) {
                case "NOT_NULL_VALIDATION" -> nonNull(config);
                case "LENGTH_VALIDATION" -> length(config);
                case "ENUM_VALIDATION" -> enumeration(config);
                case "NUMERIC_RANGE_VALIDATION" -> numericRange(config);
                case "REGEX_VALIDATION" -> regex(code, config);
                default -> throw new IllegalArgumentException("Unsupported row quality strategy: " + strategy);
            };
            compiled.add(new CompiledRule(code, strategy, field, severity, ignoreNull, test));
        }
        return new CompiledPlan(List.copyOf(compiled));
    }

    /** 不可变计划；并发求值不共享计数器，也不修改调用方的行。 */
    public static final class CompiledPlan {

        private final List<CompiledRule> rules;

        /**
         * 初始化 {@code CompiledPlan}。
         */
        private CompiledPlan(List<CompiledRule> rules) {
            this.rules = rules;
        }

        /**
         * 返回当前冻结计划包含的规则数量。
         */
        public int ruleCount() {
            return rules.size();
        }

        /**
         * 根据当前表达式或规则计算输入值的结果。
         */
        public List<QualityViolation> evaluate(Map<String, Object> row) {
            Objects.requireNonNull(row, "Quality input row is required");
            List<QualityViolation> violations = new ArrayList<>();
            for (CompiledRule rule : rules) {
                Failure failure;
                if (!row.containsKey(rule.field)) {
                    failure = new Failure("FIELD_MISSING", "规则字段不存在");
                } else {
                    Object value = row.get(rule.field);
                    if (value == null) {
                        // 即使启用了通用 ignoreNullValue，NON_NULL 也必须拒绝 null。
                        failure = rule.ignoreNull && !rule.strategy.equals("NOT_NULL_VALIDATION") ? null
                                : new Failure("NULL_VALUE", "字段值为空");
                    } else {
                        if (!scalar(value)) {
                            throw new QualityRowEvaluationException(rule.code, "INVALID_VALUE_TYPE");
                        }
                        if (value instanceof CharSequence text && text.length() > MAX_TEXT_LENGTH) {
                            throw new QualityRowEvaluationException(rule.code, "VALUE_LENGTH_LIMIT");
                        }
                        failure = rule.test.apply(value);
                    }
                }
                if (failure != null) {
                    violations.add(new QualityViolation(rule.code, rule.strategy, rule.field,
                            rule.severity, failure.code, failure.message));
                }
            }
            return List.copyOf(violations);
        }
    }

    private record CompiledRule(String code, String strategy, String field, String severity,
                                boolean ignoreNull, Function<Object, Failure> test) {

    }

    private record Failure(String code, String message) {

    }

    /**
     * 根据是否允许空白的配置构建非空值校验规则。
     */
    private static Function<Object, Failure> nonNull(Map<String, Object> config) {
        keys(config, "allowBlank");
        boolean allowBlank = bool(config, "allowBlank", false);
        return value -> !allowBlank && text(value).isEmpty()
                ? new Failure("EMPTY_VALUE", "字段值为空字符串") : null;
    }

    /**
     * 返回长度。
     */
    private static Function<Object, Failure> length(Map<String, Object> config) {
        keys(config, "minLength", "maxLength");
        Integer min = integer(config, "minLength");
        Integer max = integer(config, "maxLength");
        if (min == null && max == null || min != null && min < 0 || max != null && max < 0
                || min != null && max != null && min > max) {
            throw new IllegalArgumentException("Invalid quality length bounds");
        }
        return value -> {
            String text = text(value);
            int count = text.codePointCount(0, text.length());
            return min != null && count < min || max != null && count > max
                    ? new Failure("LENGTH_OUT_OF_RANGE", "字段长度不在规则范围内") : null;
        };
    }

    /**
     * 返回当前枚举。
     */
    private static Function<Object, Failure> enumeration(Map<String, Object> config) {
        keys(config, "validValues", "ignoreCase");
        if (!(config.get("validValues") instanceof List<?> values) || values.isEmpty() || values.size() > 10_000) {
            throw new IllegalArgumentException("Quality enum requires 1..10000 validValues");
        }
        boolean ignoreCase = bool(config, "ignoreCase", false);
        Set<String> allowed = new LinkedHashSet<>();
        for (Object value : values) {
            if (!scalar(value)) {
                throw new IllegalArgumentException("Quality enum values must be non-null scalars");
            }
            String text = text(value);
            if (text.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Quality enum value is too long");
            }
            allowed.add(ignoreCase ? text.toUpperCase(Locale.ROOT) : text);
        }
        Set<String> frozen = Set.copyOf(allowed);
        return value -> frozen.contains(ignoreCase ? text(value).toUpperCase(Locale.ROOT) : text(value))
                ? null : new Failure("ENUM_MISMATCH", "字段值不在允许值域内");
    }

    /**
     * 根据当前输入构建并返回 {@code Failure}。
     */
    private static Function<Object, Failure> numericRange(Map<String, Object> config) {
        keys(config, "minValue", "maxValue", "includeBoundary", "includeMin", "includeMax");
        BigDecimal min = decimal(config.get("minValue"));
        BigDecimal max = decimal(config.get("maxValue"));
        boolean include = bool(config, "includeBoundary", true);
        boolean includeMin = bool(config, "includeMin", include);
        boolean includeMax = bool(config, "includeMax", include);
        if (min == null && max == null || min != null && max != null
                && (min.compareTo(max) > 0 || min.compareTo(max) == 0 && (!includeMin || !includeMax))) {
            throw new IllegalArgumentException("Invalid quality numeric bounds");
        }
        return value -> {
            BigDecimal actual;
            try {
                actual = decimal(value);
            } catch (IllegalArgumentException invalid) {
                return new Failure("INVALID_NUMBER", "字段值不是有效数值");
            }
            if (actual == null) {
                return new Failure("INVALID_NUMBER", "字段值不是有效数值");
            }
            return min != null && (actual.compareTo(min) < 0 || !includeMin && actual.compareTo(min) == 0)
                    || max != null && (actual.compareTo(max) > 0 || !includeMax && actual.compareTo(max) == 0)
                    ? new Failure("NUMBER_OUT_OF_RANGE", "数值不在规则范围内") : null;
        };
    }

    /**
     * 返回正则表达式。
     */
    private static Function<Object, Failure> regex(String code, Map<String, Object> config) {
        keys(config, "regex");
        if (!(config.get("regex") instanceof String expression) || expression.isBlank()
                || expression.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException("Quality regex requires 1.." + MAX_REGEX_LENGTH + " characters");
        }
        final Pattern pattern;
        try {
            pattern = Pattern.compile(expression);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalArgumentException("Invalid quality regex syntax");
        }
        return value -> {
            String text = text(value);
            if (text.length() > MAX_REGEX_INPUT_LENGTH) {
                throw new QualityRowEvaluationException(code, "REGEX_INPUT_LIMIT");
            }
            try {
                return pattern.matcher(new BudgetedRegexText(text, MAX_REGEX_STEPS,
                        () -> new QualityRowEvaluationException(code, "REGEX_WORK_LIMIT"))).find()
                        ? null : new Failure("REGEX_MISMATCH", "字段值不符合正则格式");
            } catch (StackOverflowError exhausted) {
                throw new QualityRowEvaluationException(code, "REGEX_WORK_LIMIT");
            }
        };
    }

    /**
     * 规范化策略。
     */
    private static String normalizeStrategy(String strategy) {
        String value = required(strategy, "strategy").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "NON_NULL" -> "NOT_NULL_VALIDATION";
            case "LENGTH" -> "LENGTH_VALIDATION";
            case "ENUM" -> "ENUM_VALIDATION";
            case "NUMERIC_RANGE" -> "NUMERIC_RANGE_VALIDATION";
            case "REGEX" -> "REGEX_VALIDATION";
            default -> value;
        };
    }

    /**
     * 返回键集合。
     */
    private static void keys(Map<String, Object> config, String... allowed) {
        Set<String> keys = new HashSet<>(List.of(allowed));
        keys.add("ignoreNullValue");
        if (!keys.containsAll(config.keySet())) {
            throw new IllegalArgumentException("Unsupported quality rule config key");
        }
    }

    /**
     * 读取配置中的布尔值并按类型约定校验。
     */
    private static boolean bool(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        throw new IllegalArgumentException("Invalid quality boolean: " + key);
    }

    /**
     * 读取配置中的整数并按类型约定校验。
     */
    private static Integer integer(Map<String, Object> config, String key) {
        BigDecimal value = decimal(config.get(key));
        try {
            return value == null ? null : value.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid quality integer: " + key);
        }
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (!scalar(value) || value instanceof Boolean) {
            throw new IllegalArgumentException("Invalid quality number");
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || text.length() > 256) {
            throw new IllegalArgumentException("Invalid quality number");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid quality number");
        }
    }

    /**
     * 返回当前必填。
     */
    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Invalid quality " + name);
        }
        return value;
    }

    /**
     * 读取或转换当前标量值。
     */
    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(Object value) {
        if (!scalar(value)) {
            throw new IllegalArgumentException("Quality row scalar required; normalize nested objects first");
        }
        return String.valueOf(value);
    }
}
