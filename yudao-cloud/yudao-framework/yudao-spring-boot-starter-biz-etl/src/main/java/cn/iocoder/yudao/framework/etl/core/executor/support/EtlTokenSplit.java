package cn.iocoder.yudao.framework.etl.core.executor.support;

import java.util.regex.Pattern;

/** 本地节点和受管函数共用的 Java 字段拆分语义，保留尾空值、零宽分隔符和越界 null。 */
public final class EtlTokenSplit {
    private EtlTokenSplit() { }

    public static Plan compile(String separator, boolean regex, String wrapper) {
        if (separator == null || separator.isEmpty() || separator.length() > 4096) throw new IllegalArgumentException("拆分分隔符须为1至4096个字符");
        if (wrapper != null && wrapper.length() > 4096) throw new IllegalArgumentException("包裹字符超过长度限制");
        return new Plan(Pattern.compile(regex ? separator : Pattern.quote(separator)), wrapper == null ? "" : wrapper);
    }

    /** Spark JAVA_METHOD 稳定入口，参数均为字符串。 */
    public static String token(String source, String separator, String regex, String wrapper, String index, String trim) {
        int ordinal;
        try { ordinal = Integer.parseInt(index); } catch (NumberFormatException failure) { throw new IllegalArgumentException("拆分字段序号无效"); }
        if (ordinal < 0 || ordinal >= 200) throw new IllegalArgumentException("拆分字段序号超出范围");
        Plan plan = compile(separator, Boolean.parseBoolean(regex), wrapper); String[] tokens = plan.split(source);
        return ordinal >= tokens.length ? null : plan.clean(tokens[ordinal], trim);
    }

    public static final class Plan {
        private final Pattern separator; private final String wrapper;
        private Plan(Pattern separator, String wrapper) { this.separator = separator; this.wrapper = wrapper; }
        public String[] split(String source) {
            String value = source == null ? "" : source;
            if (value.length() > 1_000_000) throw new IllegalStateException("拆分来源超过字段长度预算");
            return separator.split(new CheckedText(value), -1);
        }
        public String clean(String token, String trim) {
            if (token == null) return null;
            String value = token;
            if (!wrapper.isEmpty()) {
                if (value.startsWith(wrapper)) value = value.substring(wrapper.length());
                if (value.endsWith(wrapper)) value = value.substring(0, value.length() - wrapper.length());
            }
            if ("BOTH".equalsIgnoreCase(trim)) return value.trim();
            if (!"LEFT".equalsIgnoreCase(trim) && !"RIGHT".equalsIgnoreCase(trim) && trim != null && !"NONE".equalsIgnoreCase(trim)) throw new IllegalArgumentException("去空格方式无效");
            int start = 0, end = value.length();
            if ("LEFT".equalsIgnoreCase(trim)) while (start < end && blank(value.charAt(start))) start++;
            if ("RIGHT".equalsIgnoreCase(trim)) while (end > start && blank(value.charAt(end - 1))) end--;
            return value.substring(start, end);
        }
    }
    private static boolean blank(char value) { return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\ufeff' || value == '\u202a' || value == '\0' || value == '\u3164' || value == '\u2800' || value == '\u200c' || value == '\u180e'; }
    private static final class CheckedText implements CharSequence {
        private final String text; private int reads;
        private CheckedText(String text) { this.text = text; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { if ((reads++ & 255) == 0 && Thread.currentThread().isInterrupted()) throw new IllegalStateException("字段拆分已取消"); return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("字段拆分已取消"); return text.subSequence(start, end); }
        @Override public String toString() { return text; }
    }
}
