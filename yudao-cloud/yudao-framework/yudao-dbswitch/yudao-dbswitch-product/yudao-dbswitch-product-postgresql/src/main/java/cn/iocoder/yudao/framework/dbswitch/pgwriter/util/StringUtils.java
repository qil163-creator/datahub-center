package cn.iocoder.yudao.framework.dbswitch.pgwriter.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 字符串工具类,提供空白判断、UTF-8 字节转换及 null 字符清除,供 COPY 写入字符串时使用。
 */
public class StringUtils {

    private static Charset utf8Charset = StandardCharsets.UTF_8;

    /**
     * 初始化 {@code StringUtils}。
     */
    private StringUtils() {
    }

    /**
     * 判断输入文本是否为空或仅包含空白字符。
     */
    public static boolean isNullOrWhiteSpace(String input) {
        return input == null || input.trim().length() == 0;
    }

    /**
     * 将当前文本编码为 UTF-8 字节数组。
     */
    public static byte[] getUtf8Bytes(String value) {
        return value.getBytes(utf8Charset);
    }

    /**
     * 移除空字符。
     */
    public static String removeNullCharacter(String data) {
        if (data == null) {
            return data;
        }

        return data.replaceAll("\u0000", "");
    }
}
