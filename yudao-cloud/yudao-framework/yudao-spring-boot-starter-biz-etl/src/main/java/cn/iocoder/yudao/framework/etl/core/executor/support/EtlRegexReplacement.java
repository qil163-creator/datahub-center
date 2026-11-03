package cn.iocoder.yudao.framework.etl.core.executor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java 17 可运行的统一正则替换计划，本地 ETL 与受管引擎 UDF 共用匹配、分组和长度边界。 */
public final class EtlRegexReplacement {
    private static final int MAX_TEXT_LENGTH = 1_000_000;
    private EtlRegexReplacement() { }

    /** Spark JAVA_METHOD 的稳定入口，布尔参数使用字符串以保持 Java 反射参数类型明确。 */
    public static String replace(String source, String search, String regex, String sensitive, String wholeWord,
                                 String replacement, String literalReplacement) {
        if (source == null) return null;
        boolean useRegex = Boolean.parseBoolean(regex);
        return compile(search, useRegex, Boolean.parseBoolean(sensitive), Boolean.parseBoolean(wholeWord),
                replacement == null ? "" : replacement, useRegex && !Boolean.parseBoolean(literalReplacement))
                .apply(source, replacement, () -> {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("受管正则函数已取消");
                });
    }

    public static Plan compile(String search, boolean regex, boolean sensitive, boolean wholeWord,
                               String replacement, boolean interpretGroups) {
        if (search == null || search.isEmpty() || search.length() > 4096) throw new IllegalArgumentException("搜索内容须为1至4096个字符");
        Pattern pattern = Pattern.compile(regex ? search : Pattern.quote(search), Pattern.UNICODE_CHARACTER_CLASS
                | Pattern.UNICODE_CASE | (sensitive ? 0 : Pattern.CASE_INSENSITIVE));
        return new Plan(pattern, wholeWord, regex && interpretGroups ? replacementParts(pattern, replacement) : null);
    }

    public static final class Plan {
        private final Pattern pattern;
        private final boolean wholeWord;
        private final List<Part> parts;
        private Plan(Pattern pattern, boolean wholeWord, List<Part> parts) { this.pattern = pattern; this.wholeWord = wholeWord; this.parts = parts; }

        /** literalReplacement 始终按字面处理；配置为正则分组替换时使用编译后的 parts。 */
        public String apply(String source, String literalReplacement, Runnable checkpoint) {
            if (source == null) return null;
            bounded(source); String literal = literalReplacement == null ? "" : literalReplacement; bounded(literal);
            Matcher matcher = pattern.matcher(new CheckedText(source, checkpoint)); StringBuilder output = new StringBuilder(); int end = 0;
            while (matcher.find()) {
                checkpoint.run();
                if (wholeWord && !wholeWord(source, matcher.start(), matcher.end())) continue;
                append(output, source.substring(end, matcher.start()));
                if (parts == null) append(output, literal);
                else for (Part part : parts) append(output, part.value(matcher));
                end = matcher.end();
            }
            append(output, source.substring(end)); return output.toString();
        }
    }

    private record Part(String literal, Integer group, String namedGroup) {
        String value(Matcher matcher) {
            if (literal != null) return literal;
            String value = namedGroup == null ? matcher.group(group) : matcher.group(namedGroup);
            return value == null ? "" : value;
        }
    }

    /** 遵循 Java Matcher 的 $0/$1/${name} 和反斜线转义。 */
    private static List<Part> replacementParts(Pattern pattern, String value) {
        List<Part> parts = new ArrayList<>(); StringBuilder literal = new StringBuilder(); int groups = pattern.matcher("").groupCount();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\') {
                if (++i == value.length()) throw new IllegalArgumentException("替换内容末尾缺少转义字符");
                literal.append(value.charAt(i));
            } else if (current == '$') {
                if (literal.length() > 0) { parts.add(new Part(literal.toString(), null, null)); literal.setLength(0); }
                if (++i == value.length()) throw new IllegalArgumentException("替换内容缺少分组编号");
                if (value.charAt(i) == '{') {
                    int end = value.indexOf('}', i + 1); String name = end < 0 ? "" : value.substring(i + 1, end);
                    if (!name.matches("[A-Za-z][A-Za-z0-9]*") || !hasNamedGroup(pattern, name)) throw new IllegalArgumentException("替换内容引用了不存在的命名分组");
                    parts.add(new Part(null, null, name)); i = end;
                } else {
                    char digit = value.charAt(i);
                    if (digit < '0' || digit > '9' || digit - '0' > groups) throw new IllegalArgumentException("替换内容引用了不存在的分组");
                    int group = digit - '0';
                    while (i + 1 < value.length() && Character.isDigit(value.charAt(i + 1))) {
                        char next = value.charAt(i + 1); if (next > '9' || group * 10 + next - '0' > groups) break;
                        group = group * 10 + next - '0'; i++;
                    }
                    parts.add(new Part(null, group, null));
                }
            } else literal.append(current);
        }
        if (literal.length() > 0) parts.add(new Part(literal.toString(), null, null)); return List.copyOf(parts);
    }

    private static boolean hasNamedGroup(Pattern pattern, String name) {
        try {
            // 由 Java 正则编译器校验反向引用，避免依赖 Java 20 的 namedGroups 或打开 JDK 私有包。
            // 空引用结束原模式可能未关闭的 \Q；换行结束 COMMENTS 模式的尾注释。
            Pattern.compile(pattern.pattern() + "\\Q\\E\n\\k<" + name + ">", pattern.flags());
            return true;
        } catch (java.util.regex.PatternSyntaxException missing) { return false; }
    }

    private static boolean wholeWord(String text, int start, int end) { return (start == 0 || !word(text.codePointBefore(start))) && (end == text.length() || !word(text.codePointAt(end))); }
    private static boolean word(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isAlphabetic(codePoint) || Character.isDigit(codePoint) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.CONNECTOR_PUNCTUATION || codePoint == 0x200c || codePoint == 0x200d;
    }
    private static void bounded(String value) { if (value.length() > MAX_TEXT_LENGTH) throw new IllegalStateException("字符串转换超过字段长度预算"); }
    private static void append(StringBuilder output, String value) { if (value.length() > MAX_TEXT_LENGTH - output.length()) throw new IllegalStateException("字符串转换超过字段长度预算"); output.append(value); }

    /** 正则回溯过程中定期检查取消和超时。 */
    private static final class CheckedText implements CharSequence {
        private final String text; private final Runnable checkpoint; private int reads;
        private CheckedText(String text, Runnable checkpoint) { this.text = text; this.checkpoint = checkpoint; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { if ((reads++ & 255) == 0) checkpoint.run(); return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { checkpoint.run(); return text.subSequence(start, end); }
        @Override public String toString() { return text; }
    }
}
