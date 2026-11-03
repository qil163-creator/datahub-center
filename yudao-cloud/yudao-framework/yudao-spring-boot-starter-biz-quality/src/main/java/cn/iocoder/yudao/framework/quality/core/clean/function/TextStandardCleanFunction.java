package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.util.*;
import java.util.regex.Pattern;

/** 补零、逐词首字母大写与最长词优先的敏感文本处理。 */
public final class TextStandardCleanFunction extends ConfiguredCleanFunction {

    public enum Kind {ZERO_PAD, CAPITALIZE, MASK, REPLACE}

    private final Kind kind;

    /**
     * 初始化 {@code TextStandardCleanFunction}及其父类状态。
     */
    public TextStandardCleanFunction(Kind kind) {
        super(switch (kind) {
                    case ZERO_PAD -> "ZERO_PAD";
                    case CAPITALIZE -> "CAPITALIZE_WORDS";
                    case MASK -> "KEYWORD_MASK";
                    case REPLACE -> "SENSITIVE_WORD_REPLACE";
                },
                switch (kind) {
                    case ZERO_PAD -> "字段补零";
                    case CAPITALIZE -> "字段值首字母大写";
                    case MASK -> "关键词脱敏";
                    case REPLACE -> "敏感词替换";
                },
                switch (kind) {
                    case ZERO_PAD -> "{\"columns\":[\"value\"],\"length\":8,\"direction\":\"LEFT\"}";
                    case CAPITALIZE -> "{\"columns\":[\"value\"]}";
                    case MASK ->
                            "{\"columns\":[\"value\"],\"keywords\":[\"敏感词\"],\"mask\":\"*\",\"ignoreCase\":false}";
                    case REPLACE ->
                            "{\"columns\":[\"value\"],\"keywords\":[\"敏感词\"],\"replacement\":\"违规内容\",\"ignoreCase\":false}";
                });
        this.kind = kind;
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = switch (kind) {
            case ZERO_PAD -> checked(config, "length", "direction");
            case CAPITALIZE -> checked(config);
            case MASK -> checked(config, "keywords", "mask", "ignoreCase");
            case REPLACE -> checked(config, "keywords", "replacement", "ignoreCase");
        };
        if (kind == Kind.ZERO_PAD) {
            if (!result.containsKey("length")) {
                throw new IllegalArgumentException("补零必须配置 length");
            }
            result.put("length", CleanFunctionConfigSupport.integer(result, "length", -1, 1, 10_000));
            result.put("direction", option(result, "direction", "LEFT", "LEFT", "RIGHT"));
        } else if (kind == Kind.MASK || kind == Kind.REPLACE) {
            boolean ignoreCase = bool(result, "ignoreCase", false);
            List<String> keywords = strings(result, "keywords", List.of(), true, 128, 128);
            Set<String> canonical = ignoreCase ? new TreeSet<>(String.CASE_INSENSITIVE_ORDER) : new HashSet<>();
            for (String keyword : keywords) {
                if (!canonical.add(keyword)) {
                    throw new IllegalArgumentException("敏感词不能重复");
                }
            }
            result.put("keywords", keywords);
            result.put("ignoreCase", ignoreCase);
            if (kind == Kind.MASK) {
                String mask = text(result, "mask", "*", false, 2, false);
                if (mask.codePointCount(0, mask.length()) != 1) {
                    throw new IllegalArgumentException("脱敏字符必须恰好是一个 Unicode 字符");
                }
                result.put("mask", mask);
            } else {
                result.put("replacement", text(result, "replacement", "违规内容", false, 1024, true));
            }
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> input) {
        var config = normalizeConfig(input);
        Pattern keywords = kind == Kind.MASK || kind == Kind.REPLACE ? keywords(config) : null;
        var budget = new CleanRegexSupport.OutputBudget();
        return CleanValueSupport.mapColumn(rows, CleanValueSupport.firstColumn(config), value -> {
            if (value == null) {
                return null;
            }
            String text = boundedText(value);
            return switch (kind) {
                case ZERO_PAD -> {
                    int missing = (Integer) config.get("length") - text.codePointCount(0, text.length());
                    if (missing <= 0) {
                        yield value;
                    }
                    String padding = "0".repeat(missing);
                    yield "LEFT".equals(config.get("direction")) ? padding + text : text + padding;
                }
                case CAPITALIZE -> capitalize(text);
                case MASK -> CleanRegexSupport.replaceLiteralMatches(keywords, text,
                        match -> ((String) config.get("mask")).repeat(match.codePointCount(0, match.length())), budget);
                case REPLACE -> CleanRegexSupport.replaceLiteralMatches(keywords, text,
                        match -> (String) config.get("replacement"), budget);
            };
        });
    }

    /**
     * 返回当前关键字集合。
     */
    private static Pattern keywords(Map<String, Object> config) {
        List<?> values = (List<?>) config.get("keywords");
        String expression = values.stream().map(String.class::cast)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return Pattern.compile(expression,
                Boolean.TRUE.equals(config.get("ignoreCase")) ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    }

    /**
     * 组合附加符属于当前词，不能把分解形式的重音字母拆成两个词。
     */
    private static String capitalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        boolean start = true;
        for (int index = 0; index < lower.length(); ) {
            CleanExecutionControl.checkpoint();
            int codePoint = lower.codePointAt(index);
            index += Character.charCount(codePoint);
            result.appendCodePoint(start ? Character.toTitleCase(codePoint) : codePoint);
            int type = Character.getType(codePoint);
            // 组合附加符属于当前词，不能把分解形式的重音字母拆成两个词。
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                start = !Character.isLetterOrDigit(codePoint);
            }
        }
        return result.toString();
    }
}
