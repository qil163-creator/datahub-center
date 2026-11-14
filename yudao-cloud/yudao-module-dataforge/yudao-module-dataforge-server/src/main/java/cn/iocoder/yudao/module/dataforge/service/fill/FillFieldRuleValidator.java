package cn.iocoder.yudao.module.dataforge.service.fill;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 填报字段的取值范围、格式与小数位规则。
 * 保存字段时校验“规则定义本身”，提交与导入时校验“单元格取值”；不依赖 Spring，便于独立测试。
 */
public final class FillFieldRuleValidator {

    /** 字段类型（dbswitch 逻辑类型）。 */
    static final int TYPE_DECIMAL = 1;
    static final int TYPE_STRING = 2;
    static final int TYPE_DATE = 3;
    static final int TYPE_INTEGER = 5;
    static final int TYPE_LONG = 6;
    static final int TYPE_DATETIME = 9;
    static final int TYPE_TIME = 10;

    public static final int SEVERITY_ERROR = 1;
    public static final int SEVERITY_WARNING = 2;

    public static final int MAX_REGEX_LENGTH = 200;
    /** 超过该长度的取值不做正则匹配，避免恶意长文本拖慢提交。 */
    public static final int MAX_REGEX_INPUT_LENGTH = 2000;
    private static final long REGEX_BUDGET_NANOS = 50_000_000L;

    private FillFieldRuleValidator() {
    }

    /** 单元格规则违反项；detail 不含行号和字段名，由调用方拼接。 */
    /**
     * 选择类字段的取值是否都在可选项内；多选字段按英文逗号分隔逐项检查。
     *
     * @param options 可选项（为空表示不限制）
     * @param multiple 是否多选
     * @param text 已去除首尾空白的非空取值
     * @return 是否合法
     */
    public static boolean optionsAllowed(java.util.Collection<?> options, boolean multiple, String text) {
        if (options == null || options.isEmpty()) {
            return true;
        }
        java.util.Set<String> allowed = new java.util.HashSet<>();
        options.forEach(option -> allowed.add(String.valueOf(option)));
        if (!multiple) {
            return allowed.contains(text);
        }
        for (String item : text.split(",")) {
            String value = item.trim();
            if (!value.isEmpty() && !allowed.contains(value)) {
                return false;
            }
        }
        return true;
    }

    public record Violation(String ruleCode, int severity, String detail) {
    }

    // ------------------------------------------------------------ 规则定义校验

    /**
     * 校验字段上配置的规则本身是否自洽。
     *
     * @return 错误说明；null 表示通过
     */
    public static String checkDefinition(Integer fieldType, String minValue, String maxValue,
            String regexPattern, Integer ruleSeverity) {
        int type = fieldType == null ? -1 : fieldType;
        if (ruleSeverity != null && ruleSeverity != SEVERITY_ERROR && ruleSeverity != SEVERITY_WARNING) {
            return "规则级别只能是错误或警告";
        }
        boolean hasMin = !isBlank(minValue);
        boolean hasMax = !isBlank(maxValue);
        if (hasMin || hasMax) {
            if (!isNumeric(type) && !isTemporal(type)) {
                return "只有数值和日期时间字段可以设置最小值、最大值";
            }
            Comparable<?> min = hasMin ? parseBound(type, minValue.trim()) : null;
            Comparable<?> max = hasMax ? parseBound(type, maxValue.trim()) : null;
            if (hasMin && min == null) {
                return "最小值格式不正确：" + boundHint(type);
            }
            if (hasMax && max == null) {
                return "最大值格式不正确：" + boundHint(type);
            }
            if (min != null && max != null && compare(min, max) > 0) {
                return "最小值不能大于最大值";
            }
        }
        if (!isBlank(regexPattern)) {
            if (type != TYPE_STRING) {
                return "只有字符串字段可以设置格式正则";
            }
            if (regexPattern.length() > MAX_REGEX_LENGTH) {
                return "格式正则不能超过 " + MAX_REGEX_LENGTH + " 个字符";
            }
            try {
                Pattern.compile(regexPattern);
            } catch (PatternSyntaxException error) {
                return "格式正则语法错误：" + error.getDescription();
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 取值校验

    /**
     * 校验一个非空单元格取值。
     *
     * @param field 发布版本固化的字段 schema（min/max/regex/regexMessage/ruleSeverity/scale/length/fieldType）
     * @param text 去除首尾空白后的非空文本
     */
    public static List<Violation> checkValue(Map<String, Object> field, String text) {
        List<Violation> violations = new ArrayList<>();
        int type = field.get("fieldType") instanceof Number number ? number.intValue() : -1;
        int severity = field.get("ruleSeverity") instanceof Number number
                && number.intValue() == SEVERITY_WARNING ? SEVERITY_WARNING : SEVERITY_ERROR;

        if (type == TYPE_DECIMAL) {
            BigDecimal value = parseDecimal(text);
            if (value != null) {
                checkDecimalPlaces(field, value, violations);
            }
        }
        String min = stringOf(field.get("min"));
        String max = stringOf(field.get("max"));
        if (!isBlank(min) || !isBlank(max)) {
            Comparable<?> value = parseBound(type, text);
            if (value == null) {
                // 类型本身的格式问题由类型规则报告；这里只在能解析时比较范围
                if (isTemporal(type)) {
                    violations.add(new Violation("RANGE", severity, "格式不正确，无法比较范围（" + boundHint(type) + "）"));
                }
            } else {
                Comparable<?> lower = isBlank(min) ? null : parseBound(type, min.trim());
                Comparable<?> upper = isBlank(max) ? null : parseBound(type, max.trim());
                if (lower != null && compare(value, lower) < 0) {
                    violations.add(new Violation("MIN_VALUE", severity, "不能小于 " + min.trim()));
                }
                if (upper != null && compare(value, upper) > 0) {
                    violations.add(new Violation("MAX_VALUE", severity, "不能大于 " + max.trim()));
                }
            }
        }
        String regex = stringOf(field.get("regex"));
        if (type == TYPE_STRING && !isBlank(regex)) {
            String custom = stringOf(field.get("regexMessage"));
            String message = isBlank(custom) ? "格式不符合要求" : custom.trim();
            if (text.length() > MAX_REGEX_INPUT_LENGTH) {
                violations.add(new Violation("REGEX", severity, "内容过长，无法校验格式"));
            } else {
                Boolean matched = matches(regex, text);
                if (matched == null) {
                    violations.add(new Violation("REGEX", severity, "格式校验超时，请简化内容或联系管理员调整规则"));
                } else if (!matched) {
                    violations.add(new Violation("REGEX", severity, message));
                }
            }
        }
        return violations;
    }

    /**
     * 整体匹配；超出时间预算返回 null，规则无法编译视为通过（保存字段时已拦截）。
     */
    static Boolean matches(String regex, String text) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException error) {
            return true;
        }
        try {
            return pattern.matcher(new DeadlineCharSequence(text, System.nanoTime() + REGEX_BUDGET_NANOS)).matches();
        } catch (RegexTimeoutException timeout) {
            return null;
        }
    }

    private static void checkDecimalPlaces(Map<String, Object> field, BigDecimal value, List<Violation> violations) {
        Integer scale = field.get("scale") instanceof Number number ? number.intValue() : null;
        Integer precision = field.get("length") instanceof Number number && number.intValue() > 0
                ? number.intValue() : null;
        BigDecimal normalized = value.stripTrailingZeros();
        int fraction = Math.max(normalized.scale(), 0);
        if (scale != null && scale >= 0 && fraction > scale) {
            violations.add(new Violation("SCALE", SEVERITY_ERROR, "小数位数不能超过 " + scale));
            return;
        }
        if (precision != null) {
            int allowedScale = scale == null || scale < 0 ? 0 : scale;
            int integerDigits = Math.max(normalized.precision() - normalized.scale(), 1);
            if (integerDigits > precision - allowedScale) {
                violations.add(new Violation("PRECISION", SEVERITY_ERROR,
                        "整数部分不能超过 " + Math.max(precision - allowedScale, 0) + " 位"));
            }
        }
    }

    // ------------------------------------------------------------ 解析与比较

    static boolean isNumeric(int type) {
        return type == TYPE_DECIMAL || type == TYPE_INTEGER || type == TYPE_LONG;
    }

    static boolean isTemporal(int type) {
        return type == TYPE_DATE || type == TYPE_DATETIME || type == TYPE_TIME;
    }

    static String boundHint(int type) {
        return switch (type) {
            case TYPE_DATE -> "日期，如 2026-01-01";
            case TYPE_DATETIME -> "日期时间，如 2026-01-01 12:00:00";
            case TYPE_TIME -> "时间，如 12:00:00";
            case TYPE_INTEGER, TYPE_LONG -> "整数";
            default -> "数值";
        };
    }

    /** 按字段类型解析边界或取值；无法解析返回 null。 */
    static Comparable<?> parseBound(int type, String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        try {
            switch (type) {
                case TYPE_INTEGER, TYPE_LONG -> {
                    return text.matches("[+-]?\\d{1,19}") ? new BigDecimal(text) : null;
                }
                case TYPE_DECIMAL -> {
                    return parseDecimal(text);
                }
                // 日期与时间的写法以统一转换器为准，保证范围比较、类型校验与合并写入一致
                case TYPE_DATE, TYPE_DATETIME, TYPE_TIME -> {
                    return (Comparable<?>) FillValueConverter.convert(type, text);
                }
                default -> {
                    return null;
                }
            }
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String text) {
        if (!text.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d{1,3})?")) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable left, Comparable right) {
        return left.compareTo(right);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------ 正则时间预算

    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() {
            super("regex budget exceeded", null, false, false);
        }
    }

    /** 每次读取字符时检查截止时间，防止灾难性回溯占满线程。 */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadline;

        DeadlineCharSequence(CharSequence delegate, long deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new RegexTimeoutException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

}
