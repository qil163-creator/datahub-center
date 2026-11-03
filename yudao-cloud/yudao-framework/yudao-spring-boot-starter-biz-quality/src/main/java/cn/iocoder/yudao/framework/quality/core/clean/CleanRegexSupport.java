package cn.iocoder.yudao.framework.quality.core.clean;

import cn.iocoder.yudao.framework.quality.core.regex.BudgetedRegexText;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则清洗的固定资源契约：每值输入至多 65536 个 UTF-16 字符、同步访问至多十万步，
 * 每值输出至多 524288 字符（UTF-16 内容约 1 MiB），一次函数调用累计输出至多 16777216 字符。
 * 不允许配置提升这些上界；数值/命名分组与反斜杠引用遵循 Java replacement 语义。
 */
public final class CleanRegexSupport {

    public static final int MAX_INPUT_CHARACTERS = 65_536;

    public static final int MAX_STEPS = 100_000;

    public static final int MAX_OUTPUT_CHARACTERS = 524_288;

    public static final int MAX_BATCH_OUTPUT_CHARACTERS = 16_777_216;

    /**
     * 初始化 {@code CleanRegexSupport}。
     */
    private CleanRegexSupport() {
    }

    /**
     * 返回当前编译。
     */
    public static Pattern compile(String expression) {
        if (expression == null || expression.isEmpty() || expression.length() > 512) {
            throw new IllegalArgumentException("正则表达式长度必须在 1 至 512 字符之间");
        }
        return Pattern.compile(expression);
    }

    /**
     * 返回当前匹配结果。
     */
    public static boolean matches(Pattern pattern, String value) {
        var text = text(value);
        text.checkWork();
        try {
            return pattern.matcher(text).matches();
        } catch (StackOverflowError exhausted) {
            throw workLimit();
        }
    }

    /**
     * 替换全部。
     */
    public static String replaceAll(Pattern pattern, String value, String replacement, OutputBudget budget) {
        if (replacement == null || replacement.length() > 1024) {
            throw new IllegalArgumentException("替换文本不能超过 1024 字符");
        }
        return replaceMatches(pattern, value, budget, new java.util.function.Function<Matcher, List<Token>>() {
            private List<Token> parsed;

            /**
             * 按当前清洗或转换策略处理输入值并返回结果。
             */
            @Override
            public List<Token> apply(Matcher matcher) {
                if (parsed == null) {
                    parsed = replacementTokens(replacement, matcher.groupCount());
                }
                return parsed;
            }
        });
    }

    /** 仅供已限制匹配文本和结果长度的组件算法使用；返回文本按字面值追加，不解释分组语法。 */
    public static String replaceLiteralMatches(Pattern pattern, String value,
            java.util.function.Function<String, String> replacement, OutputBudget budget) {
        return replaceMatches(pattern, value, budget,
                matcher -> List.of(new Token(replacement.apply(matcher.group()), null, null)));
    }

    /**
     * 替换匹配结果。
     */
    private static String replaceMatches(Pattern pattern, String value, OutputBudget budget,
            java.util.function.Function<Matcher, List<Token>> replacement) {
        var text = text(value);
        text.checkWork();
        try {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                budget.retain(value.length());
                return value;
            }
            // 只有存在匹配时才解释 replacement，与 Matcher.replaceAll 的行为一致。
            StringBuilder result = new StringBuilder(Math.min(value.length(), 256));
            int end = 0;
            do {
                text.checkWork();
                append(result, value, end, matcher.start(), budget);
                for (Token token : replacement.apply(matcher)) {
                    text.checkWork();
                    if (token.literal != null) {
                        append(result, token.literal, 0, token.literal.length(), budget);
                    } else {
                        int start = token.name == null ? matcher.start(token.group) : matcher.start(token.name);
                        int stop = token.name == null ? matcher.end(token.group) : matcher.end(token.name);
                        if (start >= 0) {
                            append(result, value, start, stop, budget);
                        }
                    }
                }
                end = matcher.end();
            } while (matcher.find());
            append(result, value, end, value.length(), budget);
            return result.toString();
        } catch (StackOverflowError exhausted) {
            throw workLimit();
        }
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static BudgetedRegexText text(String value) {
        if (value.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("正则清洗输入超过 " + MAX_INPUT_CHARACTERS + " 字符");
        }
        return new BudgetedRegexText(value, MAX_STEPS, CleanRegexSupport::workLimit);
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalArgumentException}。
     */
    private static IllegalArgumentException workLimit() {
        return new IllegalArgumentException("正则清洗超过工作量预算或任务已取消");
    }

    /**
     * 将给定内容追加到当前缓冲区，并更新累计大小。
     */
    private static void append(StringBuilder output, String source, int start, int end, OutputBudget budget) {
        int length = end - start;
        if ((long) output.length() + length > MAX_OUTPUT_CHARACTERS) {
            throw new IllegalArgumentException("正则清洗单值输出超过 " + MAX_OUTPUT_CHARACTERS + " 字符");
        }
        budget.retain(length);
        output.append(source, start, end);
    }

    /** 先申请累计输出配额再分配字符串，限制整个集合而非仅最终保留的行。 */
    public static final class OutputBudget {

        private int characters;

        /**
         * 保留当前输出或资源，供后续阶段继续使用。
         */
        public void retain(int count) {
            if (Thread.currentThread().isInterrupted()) {
                throw workLimit();
            }
            if (count < 0 || count > MAX_OUTPUT_CHARACTERS
                    || (long) characters + count > MAX_BATCH_OUTPUT_CHARACTERS) {
                throw new IllegalArgumentException("正则清洗输出超过单值或本批次字符预算");
            }
            characters += count;
        }
    }

    private record Token(String literal, Integer group, String name) {

    }

    /**
     * 解析正则替换内容中的字面量及分组引用词元。
     */
    private static List<Token> replacementTokens(String replacement, int groupCount) {
        List<Token> result = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int index = 0; index < replacement.length(); index++) {
            char character = replacement.charAt(index);
            if (character == '\\') {
                if (++index >= replacement.length()) {
                    throw new IllegalArgumentException("替换文本缺少转义字符");
                }
                literal.append(replacement.charAt(index));
            } else if (character == '$') {
                if (!literal.isEmpty()) {
                    result.add(new Token(literal.toString(), null, null));
                    literal.setLength(0);
                }
                if (++index >= replacement.length()) {
                    throw new IllegalArgumentException("替换文本缺少分组引用");
                }
                if (replacement.charAt(index) == '{') {
                    int end = replacement.indexOf('}', index + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("替换文本的命名分组没有闭合");
                    }
                    String name = replacement.substring(index + 1, end);
                    if (!name.matches("[A-Za-z][A-Za-z0-9]*")) {
                        throw new IllegalArgumentException("替换文本的命名分组无效");
                    }
                    result.add(new Token(null, null, name));
                    index = end;
                } else {
                    char first = replacement.charAt(index);
                    if (first < '0' || first > '9') {
                        throw new IllegalArgumentException("替换文本的分组引用无效");
                    }
                    int group = first - '0';
                    if (group > groupCount) {
                        throw new IndexOutOfBoundsException("替换文本引用的分组不存在");
                    }
                    while (index + 1 < replacement.length()) {
                        char next = replacement.charAt(index + 1);
                        if (next < '0' || next > '9') {
                            break;
                        }
                        int candidate = group * 10 + next - '0';
                        if (candidate > groupCount) {
                            break;
                        }
                        group = candidate;
                        index++;
                    }
                    result.add(new Token(null, group, null));
                }
            } else {
                literal.append(character);
            }
        }
        if (!literal.isEmpty()) {
            result.add(new Token(literal.toString(), null, null));
        }
        return result;
    }
}
