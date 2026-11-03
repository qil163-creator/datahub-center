package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Map;

/**
 * 推送行字段与批次体积限制。大字段走流式读取，超限不整段物化。
 *
 * @author Li
 */
public final class DataPushPayloadLimiter {

    public static final String OMITTED_PREFIX = "[omitted, ";

    /**
     * 限制数据推送载荷限流器工具的实例化，统一通过静态能力使用。
     */
    private DataPushPayloadLimiter() {
    }

    /**
     * 读取列。
     *
     * @param rs JDBC 查询结果集
     * @param column 列
     * @param maxLobBytes 最大值大对象字节
     * @param maxStringChars 最大值字符串字符
     * @return 数据推送载荷限流器
     */
    public static Object readColumn(ResultSet rs, String column, int maxLobBytes, int maxStringChars)
            throws Exception {
        int index = rs.findColumn(column);
        int sqlType = rs.getMetaData().getColumnType(index);
        int display = rs.getMetaData().getColumnDisplaySize(index);
        if (isBinary(sqlType)) {
            try (InputStream in = rs.getBinaryStream(index)) {
                return limitBinaryStream(in, maxLobBytes);
            } catch (Exception ex) {
                Object value = rs.getObject(index);
                return limitValue(value, maxLobBytes, maxStringChars);
            }
        }
        if (isLongText(sqlType) || (isChar(sqlType) && (display <= 0 || display > maxStringChars))) {
            try (Reader in = rs.getCharacterStream(index)) {
                return limitCharStream(in, maxStringChars);
            } catch (Exception ex) {
                Object value = rs.getObject(index);
                return limitValue(value, maxLobBytes, maxStringChars);
            }
        }
        Object value = rs.getObject(index);
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return limitValue(value, maxLobBytes, maxStringChars);
    }

    /**
     * 获取数据推送载荷限流器的上限值。
     *
     * @param value 待处理的值
     * @param maxLobBytes 最大值大对象字节
     * @param maxStringChars 最大值字符串字符
     * @return 数据推送载荷限流器
     */
    public static Object limitValue(Object value, int maxLobBytes, int maxStringChars) throws Exception {
        if (value == null) {
            return null;
        }
        int lobLimit = Math.max(0, maxLobBytes);
        int textLimit = Math.max(1, maxStringChars);
        if (value instanceof byte[] bytes) {
            if (bytes.length > lobLimit) {
                return OMITTED_PREFIX + bytes.length + " bytes]";
            }
            return HexUtil.encodeHexStr(bytes);
        }
        if (value instanceof Blob blob) {
            long length = blob.length();
            if (length > lobLimit) {
                return OMITTED_PREFIX + length + " bytes]";
            }
            return HexUtil.encodeHexStr(blob.getBytes(1, (int) length));
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            if (length > textLimit) {
                return clob.getSubString(1, textLimit) + "...(truncated)";
            }
            return clob.getSubString(1, (int) length);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = value.toString();
        if (text.length() > textLimit) {
            return text.substring(0, textLimit) + "...(truncated)";
        }
        return text;
    }

    /**
     * 获取数据推送载荷限流器的上限二进制流。
     *
     * @param in 输入
     * @param maxLobBytes 最大值大对象字节
     * @return 数据推送载荷限流器
     */
    public static Object limitBinaryStream(InputStream in, int maxLobBytes) throws Exception {
        if (in == null) {
            return null;
        }
        int limit = Math.max(0, maxLobBytes);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(Math.max(limit, 0), 4096));
        byte[] chunk = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (total + n > limit) {
                long length = total + n;
                while ((n = in.read(chunk)) >= 0) {
                    length += n;
                }
                return OMITTED_PREFIX + length + " bytes]";
            }
            buf.write(chunk, 0, n);
            total += n;
        }
        return total == 0 ? "" : HexUtil.encodeHexStr(buf.toByteArray());
    }

    /**
     * 获取数据推送载荷限流器的上限字符流。
     *
     * @param in 输入
     * @param maxStringChars 最大值字符串字符
     * @return 数据推送载荷限流器
     */
    public static Object limitCharStream(Reader in, int maxStringChars) throws Exception {
        if (in == null) {
            return null;
        }
        int limit = Math.max(1, maxStringChars);
        StringBuilder buf = new StringBuilder(Math.min(limit, 256));
        char[] chunk = new char[2048];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (total + n > limit) {
                int keep = limit - total;
                if (keep > 0) {
                    buf.append(chunk, 0, keep);
                }
                while (in.read(chunk) >= 0) {
                    // 排空剩余，避免驱动缓冲滞留
                }
                return buf + "...(truncated)";
            }
            buf.append(chunk, 0, n);
            total += n;
        }
        return buf.toString();
    }

    /**
     * 估算行字节。
     *
     * @param row 当前行数据
     * @return 估算行字节数值
     */
    public static int estimateRowBytes(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return 2;
        }
        int size = 2;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            size += StrUtil.length(entry.getKey()) + 8;
            Object value = entry.getValue();
            if (value == null) {
                size += 4;
            } else {
                size += String.valueOf(value).length() * 2;
            }
        }
        return size;
    }

    /**
     * 判断是否满足二进制条件。
     *
     * @param sqlType SQL类型
     * @return 判断结果
     */
    static boolean isBinary(int sqlType) {
        return sqlType == Types.BLOB
                || sqlType == Types.BINARY
                || sqlType == Types.VARBINARY
                || sqlType == Types.LONGVARBINARY;
    }

    /**
     * 判断是否满足长整数文本条件。
     *
     * @param sqlType SQL类型
     * @return 判断结果
     */
    static boolean isLongText(int sqlType) {
        return sqlType == Types.CLOB
                || sqlType == Types.NCLOB
                || sqlType == Types.LONGVARCHAR
                || sqlType == Types.LONGNVARCHAR;
    }

    /**
     * 判断是否满足字符条件。
     *
     * @param sqlType SQL类型
     * @return 判断结果
     */
    static boolean isChar(int sqlType) {
        return sqlType == Types.CHAR
                || sqlType == Types.VARCHAR
                || sqlType == Types.NCHAR
                || sqlType == Types.NVARCHAR
                || isLongText(sqlType);
    }

}
