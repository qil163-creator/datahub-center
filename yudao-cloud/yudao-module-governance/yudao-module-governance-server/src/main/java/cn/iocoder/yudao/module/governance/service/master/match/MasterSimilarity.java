package cn.iocoder.yudao.module.governance.service.master.match;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 主数据相似匹配的字段规范化与相似度算法；结果均在 0～1 之间。
 */
public final class MasterSimilarity {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 文本比较时忽略空白、标点和符号（含中文标点） */
    private static final Pattern NOISE = Pattern.compile("[\\s\\p{P}\\p{S}]+");

    private MasterSimilarity() {
    }

    /** 判断值是否为空：null 或规范化后为空串 */
    public static boolean blank(Object value) {
        return value == null || normalizeExact(value).isEmpty();
    }

    /** 精确比较用的规范化：全半角统一、去首尾空白、连续空白合一、小写 */
    public static String normalizeExact(Object value) {
        if (value == null) {
            return "";
        }
        String text = Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC).trim();
        return WHITESPACE.matcher(text).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** 文本相似比较用的规范化：在精确规范化基础上去掉空白、标点和符号 */
    public static String normalizeText(Object value) {
        return NOISE.matcher(normalizeExact(value)).replaceAll("");
    }

    /** Jaro-Winkler 相似度（前缀权重 0.1，最长 4 个字符） */
    public static double jaroWinkler(String left, String right) {
        if (left.equals(right)) {
            return left.isEmpty() ? 0 : 1;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int window = Math.max(0, Math.max(a.length, b.length) / 2 - 1);
        boolean[] matchedA = new boolean[a.length];
        boolean[] matchedB = new boolean[b.length];
        int matches = 0;
        for (int i = 0; i < a.length; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!matchedB[j] && a[i] == b[j]) {
                    matchedA[i] = true;
                    matchedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length; i++) {
            if (!matchedA[i]) {
                continue;
            }
            while (!matchedB[k]) {
                k++;
            }
            if (a[i] != b[k]) {
                transpositions++;
            }
            k++;
        }
        double m = matches;
        double jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length, b.length)) && a[prefix] == b[prefix]) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    /** 编辑距离相似度：1 - 距离 / 较长串长度（按 Unicode 码点计） */
    public static double levenshtein(String left, String right) {
        if (left.equals(right)) {
            return left.isEmpty() ? 0 : 1;
        }
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (a.length == 0 || b.length == 0) {
            return 0;
        }
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1 - (double) previous[b.length] / Math.max(a.length, b.length);
    }

    /** 数值接近度；任一侧不是数值时退化为精确比较 */
    public static double numeric(Object left, Object right) {
        BigDecimal a = decimal(left);
        BigDecimal b = decimal(right);
        if (a == null || b == null) {
            return normalizeExact(left).equals(normalizeExact(right)) ? 1 : 0;
        }
        if (a.compareTo(b) == 0) {
            return 1;
        }
        BigDecimal scale = a.abs().max(b.abs());
        double ratio = a.subtract(b).abs().divide(scale, MathContext.DECIMAL64).doubleValue();
        return Math.max(0, 1 - ratio);
    }

    /** 按算法计算两个非空值的相似度 */
    public static double score(MasterMatchConfig.Algorithm algorithm, Object left, Object right) {
        return switch (algorithm) {
            case EXACT -> normalizeExact(left).equals(normalizeExact(right)) ? 1 : 0;
            case TEXT -> jaroWinkler(normalizeText(left), normalizeText(right));
            case EDIT -> levenshtein(normalizeText(left), normalizeText(right));
            case NUMERIC -> numeric(left, right);
        };
    }

    /** 解析数值；不是数值时返回 null */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

}
