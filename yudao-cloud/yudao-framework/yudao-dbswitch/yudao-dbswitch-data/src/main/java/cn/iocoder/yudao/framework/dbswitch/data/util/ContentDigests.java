package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.hutool.crypto.SecureUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 多重集内容摘要：保留重复次数，按类型规范化后再排序拼接，避免简单字符串碰撞。
 */
public final class ContentDigests {

    /**
     * 初始化 {@code ContentDigests}。
     */
    private ContentDigests() {
    }

    /**
     * 根据数据行内容计算摘要。
     */
    public static String ofRows(List<Object[]> rows) {
        List<String> encoded = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            encoded.add(encodeRow(row));
        }
        Collections.sort(encoded);
        return SecureUtil.sha256(String.join("\n", encoded));
    }

    /**
     * 读取结果集并根据数据内容计算摘要。
     */
    public static String ofResultSet(ResultSet resultSet) throws Exception {
        ResultSetMetaData meta = resultSet.getMetaData();
        int width = meta.getColumnCount();
        List<Object[]> rows = new ArrayList<>();
        while (resultSet.next()) {
            Object[] row = new Object[width];
            for (int i = 0; i < width; i++) {
                row[i] = resultSet.getObject(i + 1);
            }
            rows.add(row);
        }
        return ofRows(rows);
    }

    /**
     * 编码数据行。
     */
    static String encodeRow(Object[] row) {
        if (row == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                builder.append('\u001f');
            }
            builder.append(encodeValue(row[i]));
        }
        return builder.toString();
    }

    /**
     * 编码值。
     */
    static String encodeValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "BIN:" + HexFormat.of().formatHex(bytes);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return new String(String.valueOf(value).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

}
