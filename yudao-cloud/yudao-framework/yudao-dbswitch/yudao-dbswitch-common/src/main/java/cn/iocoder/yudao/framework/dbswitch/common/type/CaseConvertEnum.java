package cn.iocoder.yudao.framework.dbswitch.common.type;

import java.util.Locale;

/**
 * 目标表名/字段名的转换方式（移植自 dbswitch 2.0.2）
 * <p>
 * SNAKE/CAMEL 逐字符复现项目所用 Hutool 5.8.x {@code NamingCase.toUnderlineCase}/{@code NamingCase.toCamelCase}
 * 的结果（按 char 判断大小写，数字仅限 ASCII），本模块不引入 Hutool 依赖；
 * UPPER/LOWER 固定使用 {@link Locale#ROOT}，结果不受进程默认语言影响。
 *
 * @author yudao
 */
public enum CaseConvertEnum {

    /** 不转换 */
    NONE,
    /** 全大写 */
    UPPER,
    /** 全小写 */
    LOWER,
    /** 驼峰转下划线，如 userName → user_name；连续大写按专有名词保留，如 HTTPServer → HTTP_server */
    SNAKE,
    /** 下划线转驼峰，如 user_name → userName；不含下划线的名称原样返回 */
    CAMEL,
    ;

    private static final char UNDERLINE = '_';

    /**
     * 转换名称；null 原样返回
     */
    public String convert(String name) {
        if (name == null) {
            return null;
        }
        return switch (this) {
            case NONE -> name;
            case UPPER -> name.toUpperCase(Locale.ROOT);
            case LOWER -> name.toLowerCase(Locale.ROOT);
            case SNAKE -> toUnderlineCase(name);
            case CAMEL -> toCamelCase(name);
        };
    }

    /**
     * 将标识符名称转换为下划线命名形式。
     */
    private static String toUnderlineCase(String name) {
        int length = name.length();
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                boolean last = i == length - 1;
                char next = last ? 0 : name.charAt(i + 1);
                // 普通首字母大写：其后为结尾或小写字母
                boolean capitalized = last || Character.isLowerCase(next);
                if (i == 0 || name.charAt(i - 1) == UNDERLINE) {
                    // 首字母或分隔符之后：后一个仍为大写时按专有名词保留，如 _AB
                    if (capitalized) {
                        c = Character.toLowerCase(c);
                    }
                } else if (Character.isLowerCase(name.charAt(i - 1))) {
                    // 小写之后开始新单词，如 aBcc → a_bcc；后一个为大写时保留，如 aBC → a_BC
                    result.append(UNDERLINE);
                    if (capitalized || isAsciiDigit(next)) {
                        c = Character.toLowerCase(c);
                    }
                } else if (!last && Character.isLowerCase(next)) {
                    // 连续大写的最后一个开始新单词，如 ABcc → A_bcc
                    result.append(UNDERLINE);
                    c = Character.toLowerCase(c);
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * 将标识符名称转换为驼峰命名形式。
     */
    private static String toCamelCase(String name) {
        if (name.indexOf(UNDERLINE) < 0) {
            return name;
        }
        int length = name.length();
        StringBuilder result = new StringBuilder(length);
        boolean upperNext = false;
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c == UNDERLINE) {
                upperNext = true;
            } else if (upperNext) {
                result.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    /**
     * 判断字符是否为 ASCII 十进制数字。
     */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
