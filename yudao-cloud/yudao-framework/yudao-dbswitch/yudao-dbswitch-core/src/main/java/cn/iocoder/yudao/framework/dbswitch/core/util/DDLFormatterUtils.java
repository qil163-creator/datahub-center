package cn.iocoder.yudao.framework.dbswitch.core.util;

import java.util.regex.Pattern;

/**
 * 保守地呈现生成的 CREATE TABLE 语句。
 * 带引号标记和注释原样复制；只在外层括号及逗号处增加空白。
 * 此 API 没有方言或会话参数，所以有歧义的转义和其他语句保持不变。
 *
 * @author jrl
 */
public class DDLFormatterUtils {

    private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*create\\s+table\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 返回格式。
     */
    public static String format(String sql) {
        if (sql == null || sql.isEmpty() || !CREATE_TABLE.matcher(sql).lookingAt()) {
            return sql;
        }
        final StringBuilder result = new StringBuilder(sql.length());
        int depth = 0;
        boolean terminated = false;
        for (int i = 0; i < sql.length(); ) {
            char value = sql.charAt(i);
            if (Character.isWhitespace(value)) {
                result.append(value);
                i++;
                continue;
            }
            if (value == '-' && follows(sql, i, '-')) {
                // MySQL 要求 -- 后有空白；没有方言时，此处存在歧义。
                if (i + 2 < sql.length() && !Character.isWhitespace(sql.charAt(i + 2))) {
                    return sql;
                }
                int end = i + 2;
                while (end < sql.length() && sql.charAt(end) != '\r' && sql.charAt(end) != '\n') {
                    end++;
                }
                result.append(sql, i, end);
                i = end;
                continue;
            }
            if (value == '/' && follows(sql, i, '*')) {
                int end = sql.indexOf("*/", i + 2);
                int nested = sql.indexOf("/*", i + 2);
                // 嵌套块注释在不同产品中含义不同。
                if (end < 0 || nested >= 0 && nested < end) {
                    return sql;
                }
                result.append(sql, i, end + 2);
                i = end + 2;
                continue;
            }
            if (terminated || value == '$' || value == '#' || value == '\\') {
                return sql;
            }
            if (value == '\'' || value == '"' || value == '`' || value == '[') {
                if (value == '\'' && alternativeQuotePrefix(sql, i)) {
                    return sql;
                }
                // 方括号也可能表示数组或下标。只允许明确的标识符位置。
                if (value == '[' && !bracketIdentifierPosition(sql, i)) {
                    return sql;
                }
                int end = quotedEnd(sql, i, value == '[' ? ']' : value);
                if (end < 0) {
                    return sql;
                }
                result.append(sql, i, end);
                i = end;
                continue;
            }
            if (value == ')') {
                if (--depth < 0) {
                    return sql;
                }
                if (depth == 0 && !endsWithLineBreak(result)) {
                    result.append("\n    ");
                }
            }
            result.append(value);
            if (value == '(') {
                depth++;
                if (depth == 1 && !lineBreakFollows(sql, i + 1)) {
                    result.append("\n        ");
                }
            } else if (value == ',' && depth == 1) {
                if (!lineBreakFollows(sql, i + 1)) {
                    result.append("\n        ");
                }
            } else if (value == ';') {
                if (depth != 0) {
                    return sql;
                }
                terminated = true;
            }
            i++;
        }
        return depth == 0 ? result.toString() : sql;
    }

    /** 起始分隔符已属于此标记；其他种类的引号均作为普通内容。 */
    private static int quotedEnd(String sql, int start, char delimiter) {
        for (int i = start + 1; i < sql.length(); i++) {
            char value = sql.charAt(i);
            // 反斜杠转义取决于数据库和会话模式。绝不猜测。
            if (value == '\\') {
                return -1;
            }
            if (value == delimiter) {
                if (follows(sql, i, delimiter)) {
                    i++;
                } else {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /**
     * 检查当前位置后续内容是否匹配指定模式。
     */
    private static boolean follows(String sql, int index, char next) {
        return index + 1 < sql.length() && sql.charAt(index + 1) == next;
    }

    /**
     * 识别字符串替代引用语法的前缀。
     */
    private static boolean alternativeQuotePrefix(String sql, int quote) {
        int start = quote;
        while (start > 0 && Character.isLetter(sql.charAt(start - 1))) {
            start--;
        }
        String prefix = sql.substring(start, quote);
        return prefix.equalsIgnoreCase("q") || prefix.equalsIgnoreCase("nq");
    }

    /**
     * 定位方括号引用标识符的边界。
     */
    private static boolean bracketIdentifierPosition(String sql, int bracket) {
        int previous = bracket - 1;
        while (previous >= 0 && Character.isWhitespace(sql.charAt(previous))) {
            previous--;
        }
        if (previous < 0) {
            return false;
        }
        char value = sql.charAt(previous);
        if (value == '(' || value == ',' || value == '.') {
            return true;
        }
        int start = previous;
        while (start >= 0 && Character.isLetter(sql.charAt(start))) {
            start--;
        }
        return sql.substring(start + 1, previous + 1).equalsIgnoreCase("table");
    }

    /**
     * 检查当前位置之后是否紧接换行。
     */
    private static boolean lineBreakFollows(String sql, int start) {
        for (int i = start; i < sql.length() && Character.isWhitespace(sql.charAt(i)); i++) {
            if (sql.charAt(i) == '\r' || sql.charAt(i) == '\n') {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查当前文本是否以换行结尾。
     */
    private static boolean endsWithLineBreak(StringBuilder result) {
        for (int i = result.length() - 1; i >= 0 && Character.isWhitespace(result.charAt(i)); i--) {
            if (result.charAt(i) == '\r' || result.charAt(i) == '\n') {
                return true;
            }
        }
        return false;
    }
}
