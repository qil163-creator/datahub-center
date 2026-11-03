package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.PatternSyntaxException;

/** 行内字段规则的编译计划；校验、执行与字段推导共享同一参数契约。 */
public final class EtlFieldRules {
    public static final String STRING_OPERATION = "string-operation";
    public static final String STRING_REPLACE = "string-replace";
    public static final String SET_FIELD_VALUE = "set-field-value";
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private static final int MAX_RULES = 256;
    private static final Set<String> CODES = Set.of(STRING_OPERATION, STRING_REPLACE, SET_FIELD_VALUE);

    private EtlFieldRules() { }

    public static boolean supports(String code) {
        return code != null && CODES.contains(code);
    }

    /** 参数错误携带规则字段路径，避免发布检查只报告笼统的 JSON 错误。 */
    public static final class ConfigException extends IllegalArgumentException {
        private final String field;
        public ConfigException(String field, String message) {
            super(message);
            this.field = field;
        }
        public String field() { return field; }
    }

    public record Projection(String sourceField, String targetField, boolean textResult) { }
    private record Rule(Projection projection, List<String> requiredFields, BiFunction<Map<String, Object>, Runnable, Object> convert) { }

    public static final class Plan {
        private final List<Rule> rules;
        private Plan(List<Rule> rules) { this.rules = List.copyOf(rules); }
        public List<Projection> projections() { return rules.stream().map(Rule::projection).toList(); }
        public List<String> requiredFields() { return rules.stream().flatMap(rule -> rule.requiredFields.stream()).distinct().toList(); }

        /** 所有源值均读取原始行，复制、互换及覆盖不会受前一条规则影响。 */
        public Map<String, Object> apply(Map<String, Object> original, Runnable checkpoint) {
            Map<String, Object> output = new LinkedHashMap<>(original);
            for (Rule rule : rules) {
                checkpoint.run();
                rule.requiredFields.forEach(name -> requireField(original, name));
                output.put(rule.projection.targetField(), rule.convert.apply(original, checkpoint));
            }
            return output;
        }
    }

    /** 在开始消费输入前一次性编译规则和正则表达式。 */
    public static Plan compile(String code, JSONObject config) {
        if (!supports(code)) throw new ConfigException("toolCode", "未知字段规则组件");
        if (!(config.get("rules") instanceof JSONArray array) || array.isEmpty() || array.size() > MAX_RULES) {
            throw new ConfigException("rules", "请配置 1 至 256 条字段规则");
        }
        List<Rule> result = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String path = "rules[" + i + "]";
            if (!(array.get(i) instanceof Map<?, ?>)) throw invalid(path, "规则须为对象");
            JSONObject rule = JSONUtil.parseObj(array.get(i));
            String source = text(rule, SET_FIELD_VALUE.equals(code) ? "sourceField" : "inputField", "", path);
            String target = text(rule, SET_FIELD_VALUE.equals(code) ? "targetField" : "outputField", source, path);
            if (source.isBlank()) throw invalid(path, "来源字段不能为空");
            if (target.isBlank()) target = source;
            if (!targets.add(target)) throw invalid(path, "目标字段不能重复");
            Projection projection = new Projection(source, target, !SET_FIELD_VALUE.equals(code));
            result.add(switch (code) {
                case STRING_OPERATION -> operation(rule, projection, path);
                case STRING_REPLACE -> replacement(rule, projection, path);
                case SET_FIELD_VALUE -> {
                    if (text(rule, "targetField", "", path).isBlank()) throw invalid(path, "目标字段不能为空");
                    yield new Rule(projection, List.of(source), (row, checkpoint) -> row.get(source));
                }
                default -> throw new ConfigException("toolCode", "未知字段规则组件");
            });
        }
        return new Plan(result);
    }

    private static Rule operation(JSONObject rule, Projection projection, String path) {
        String trim = option(rule, "trim", "NONE", Set.of("NONE", "LEFT", "RIGHT", "BOTH"), path);
        String caseMode = option(rule, "caseMode", "NONE", Set.of("NONE", "LOWER", "UPPER"), path);
        String padding = option(rule, "padDirection", "NONE", Set.of("NONE", "LEFT", "RIGHT", "BOTH"), path);
        int length = integer(rule, "padLength", 0, MAX_TEXT_LENGTH, path);
        String fill = text(rule, "padCharacter", "", path);
        if (!"NONE".equals(padding) && length > 0 && fill.isEmpty()) throw invalid(path, "填充字符不能为空");
        return new Rule(projection, List.of(projection.sourceField()), (row, checkpoint) -> {
            Object value = row.get(projection.sourceField());
            if (value == null) return null;
            String text = boundedText(value);
            text = trim(text, trim);
            text = switch (caseMode) {
                case "LOWER" -> text.toLowerCase(Locale.ROOT);
                case "UPPER" -> text.toUpperCase(Locale.ROOT);
                default -> text;
            };
            if (!"NONE".equals(padding)) text = pad(text, fill, length, padding);
            return boundedText(text);
        });
    }

    private static Rule replacement(JSONObject rule, Projection projection, String path) {
        String search = text(rule, "search", "", path);
        if (search.isEmpty() || search.length() > 4096) throw invalid(path, "搜索内容须为 1 至 4096 个字符");
        boolean regex = bool(rule, "regex", false, path);
        boolean sensitive = bool(rule, "caseSensitive", true, path);
        boolean wholeWord = bool(rule, "wholeWord", false, path);
        boolean empty = bool(rule, "emptyReplacement", false, path);
        String replacementField = text(rule, "replacementField", "", path);
        String replacement = text(rule, "replacement", "", path);
        EtlRegexReplacement.Plan plan;
        try {
            plan = EtlRegexReplacement.compile(search, regex, sensitive, wholeWord, replacement,
                    regex && !empty && replacementField.isBlank());
        } catch (PatternSyntaxException failure) {
            throw invalid(path + ".search", "正则表达式不合法");
        } catch (IllegalArgumentException failure) {
            throw invalid(path + ".replacement", failure.getMessage());
        }
        return new Rule(projection, replacementField.isBlank() || empty ? List.of(projection.sourceField())
                : List.of(projection.sourceField(), replacementField), (row, checkpoint) -> {
            Object value = row.get(projection.sourceField());
            String literal = empty ? "" : replacementField.isBlank() ? replacement
                    : boundedText(requireField(row, replacementField));
            return plan.apply(value == null ? null : boundedText(value), literal, checkpoint);
        });
    }

    private static String trim(String value, String mode) {
        int start = 0, end = value.length();
        if ("LEFT".equals(mode) || "BOTH".equals(mode)) while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        if ("RIGHT".equals(mode) || "BOTH".equals(mode)) while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
    private static boolean whitespace(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint); }
    private static String pad(String text, String fill, int length, String direction) {
        int missing = length - text.codePointCount(0, text.length());
        if (missing <= 0) return text;
        int left = "LEFT".equals(direction) ? missing : "BOTH".equals(direction) ? missing / 2 : 0;
        StringBuilder result = new StringBuilder();
        append(result, padding(fill, left)); append(result, text); append(result, padding(fill, missing - left));
        return result.toString();
    }
    private static String padding(String fill, int count) {
        if (count == 0) return "";
        int[] codePoints = fill.codePoints().toArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (result.length() + Character.charCount(codePoints[i % codePoints.length]) > MAX_TEXT_LENGTH) throw new IllegalStateException("字符串转换超过字段长度预算");
            result.appendCodePoint(codePoints[i % codePoints.length]);
        }
        return result.toString();
    }
    private static void append(StringBuilder output, String value) {
        if (value.length() > MAX_TEXT_LENGTH - output.length()) throw new IllegalStateException("字符串转换超过字段长度预算");
        output.append(value);
    }
    private static String boundedText(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.length() > MAX_TEXT_LENGTH) throw new IllegalStateException("字符串转换超过字段长度预算");
        return text;
    }
    private static Object requireField(Map<String, Object> row, String name) {
        if (!row.containsKey(name)) throw new IllegalStateException("输入行缺少字段：" + name);
        return row.get(name);
    }
    private static String text(JSONObject rule, String key, String fallback, String path) {
        Object value = rule.get(key);
        if (value == null || value instanceof JSONNull) return fallback;
        if (!(value instanceof String text)) throw invalid(path + "." + key, "字段参数须为字符串");
        return text;
    }
    private static boolean bool(JSONObject rule, String key, boolean fallback, String path) {
        Object value = rule.get(key);
        if (value == null || value instanceof JSONNull) return fallback;
        if (!(value instanceof Boolean flag)) throw invalid(path + "." + key, "开关参数须为布尔值");
        return flag;
    }
    private static String option(JSONObject rule, String key, String fallback, Set<String> values, String path) {
        String value = text(rule, key, fallback, path);
        if (!values.contains(value)) throw invalid(path + "." + key, "不支持的规则选项");
        return value;
    }
    private static int integer(JSONObject rule, String key, int fallback, int maximum, String path) {
        Object value = rule.get(key);
        if (value == null || value instanceof JSONNull) return fallback;
        try {
            if (!(value instanceof Number number)) throw new NumberFormatException();
            int result = new BigDecimal(number.toString()).intValueExact();
            if (result < 0 || result > maximum) throw new NumberFormatException();
            return result;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid(path + "." + key, "填充长度须为 0 至 " + maximum + " 的整数");
        }
    }
    private static ConfigException invalid(String field, String message) { return new ConfigException(field, message); }

}
