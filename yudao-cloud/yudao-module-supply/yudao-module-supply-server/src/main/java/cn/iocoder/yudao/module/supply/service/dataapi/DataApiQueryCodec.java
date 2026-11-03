package cn.iocoder.yudao.module.supply.service.dataapi;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_REQUEST_INVALID;

/**
 * v2 查询串的确定性编解码。重复键、非法百分号编码一律拒绝。
 */
public final class DataApiQueryCodec {

    private static final Pattern HEX = Pattern.compile("%[0-9A-Fa-f]{2}");

    private static final int MAX_QUERY_LENGTH = 8_192;

    private static final int MAX_PARAMS = 64;

    /**
     * 限制数据 API查询工具的实例化，统一通过静态能力使用。
     */
    private DataApiQueryCodec() {
    }

    /**
     * 解析数据 API查询。
     *
     * @param rawQuery 原始查询字符串
     * @return 已关联摘要映射字符串字符串
     */
    public static LinkedHashMap<String, String> parse(String rawQuery) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        if (rawQuery.length() > MAX_QUERY_LENGTH) {
            throw exception(DATA_API_REQUEST_INVALID, "查询串过长");
        }
        String query = rawQuery.startsWith("?") ? rawQuery.substring(1) : rawQuery;
        if (query.isBlank()) {
            return params;
        }
        String[] pairs = query.split("&", -1);
        if (pairs.length > MAX_PARAMS) {
            throw exception(DATA_API_REQUEST_INVALID, "查询参数个数超过限制");
        }
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                throw exception(DATA_API_REQUEST_INVALID, "查询串存在空参数");
            }
            int eq = pair.indexOf('=');
            String rawName = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            String name = decode(rawName);
            if (name.isBlank()) {
                throw exception(DATA_API_REQUEST_INVALID, "查询参数名为空");
            }
            if (params.containsKey(name)) {
                throw exception(DATA_API_REQUEST_INVALID, "查询参数重复：" + name);
            }
            params.put(name, decode(rawValue));
        }
        return params;
    }

    /**
     * 将输入转为稳定的规范表示。
     *
     * @param params 业务参数映射
     * @return 将输入转为稳定的规范表示文本
     */
    public static String canonicalize(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        params.forEach((name, value) -> {
            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(encode(name)).append('=').append(encode(value == null ? "" : value));
        });
        return result.toString();
    }

    /**
     * 解码数据 API查询。
     *
     * @param raw 原始
     * @return 解码数据 API查询文本
     */
    static String decode(String raw) {
        if (raw == null) {
            return "";
        }
        validatePercentEncoding(raw);
        try {
            return URLDecoder.decode(raw.replace("+", "%20"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_API_REQUEST_INVALID, "查询串编码不合法");
        }
    }

    /**
     * 编码数据 API查询。
     *
     * @param value 待处理的值
     * @return 编码数据 API查询文本
     */
    static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int unsigned = b & 0xff;
            if (unreserved(unsigned)) {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append("0123456789ABCDEF".charAt(unsigned >>> 4));
                result.append("0123456789ABCDEF".charAt(unsigned & 0x0f));
            }
        }
        return result.toString();
    }

    /**
     * 判断无需转义条件是否成立。
     *
     * @param ch 字符
     * @return 处理是否成功
     */
    private static boolean unreserved(int ch) {
        return (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '.' || ch == '_' || ch == '~';
    }

    /**
     * 校验百分号编码。
     *
     * @param raw 原始
     */
    private static void validatePercentEncoding(String raw) {
        int index = raw.indexOf('%');
        while (index >= 0) {
            if (index + 2 >= raw.length() || !HEX.matcher(raw.substring(index, index + 3)).matches()) {
                throw exception(DATA_API_REQUEST_INVALID, "查询串含非法百分号编码");
            }
            index = raw.indexOf('%', index + 3);
        }
    }

    /**
     * 规范化内容类型。
     *
     * @param contentType 内容类型
     * @return 规范化内容类型文本
     */
    static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        String value = contentType.strip();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).strip();
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
