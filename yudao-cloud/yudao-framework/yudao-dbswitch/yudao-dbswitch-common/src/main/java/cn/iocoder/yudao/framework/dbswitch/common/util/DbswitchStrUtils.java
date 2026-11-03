package cn.iocoder.yudao.framework.dbswitch.common.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 字符串工具类
 *
 * @author jrl
 * @date 2021/6/8 20:55
 */
public final class DbswitchStrUtils {

    /**
     * 把 glob 通配符模式（* 匹配任意串、? 匹配单字符）转换为等价的正则表达式，
     * 其余字符按字面量处理
     *
     * @param glob glob 模式串
     * @return 等价正则表达式
     */
    public static String globToRegex(String glob) {
        if (glob == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    /**
     * 根据逗号切分字符串为数组
     *
     * @param str 待切分的字符串
     * @return List
     */
    public static List<String> stringToList(String str) {
        if (null != str && str.length() > 0) {
            String[] strs = str.split(",");
            if (strs.length > 0) {
                return new ArrayList<>(Arrays.asList(strs));
            }
        }

        return new ArrayList<>();
    }


    /**
     * 将二进制数据转换为16进制的可视化字符串
     *
     * @param bytes 二进制数据
     * @return 16进制的可视化字符串
     */
    public static String toHexString(byte[] bytes) {
        if (null == bytes || bytes.length <= 0) {
            return null;
        }
        final StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 0xFF;
            String s = Integer.toHexString(v);
            if (s.length() < 2) {
                hexString.append(0);
            }
            hexString.append(s);
        }
        return hexString.toString();
    }

    /**
     * 初始化 {@code DbswitchStrUtils}。
     */
    private DbswitchStrUtils() {
    }
}
