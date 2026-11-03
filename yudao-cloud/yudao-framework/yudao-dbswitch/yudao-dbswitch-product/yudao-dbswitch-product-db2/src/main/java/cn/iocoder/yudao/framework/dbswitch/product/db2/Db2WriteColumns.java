package cn.iocoder.yudao.framework.dbswitch.product.db2;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.math.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/**
 * 目标列事实及原生 MERGE 写入的精确 DB2 LUW 12.1.5.0 值域。在借用连接之前
 * 校验值，否则 DB2 会截断 VARCHAR 字节长度、静默填充
 * CHAR(n)，或将 TIME/TIMESTAMP 值舍入到列的小数精度。REAL/DOUBLE、
 * FLOAT/DECFLOAT、CLOB/BLOB、GRAPHIC/VARGRAPHIC、XML、LONG VARCHAR 及 CHAR/VARCHAR FOR BIT DATA
 * 没有经过验证的精确编解码器，因此拒绝。
 */
public final class Db2WriteColumns {

    public record Column(String name, String base, int length, int precision, int scale, int fraction,
                         boolean nullable, boolean identity) {

        /**
         * 根据原生列信息确定数据库类型声明。
         */
        public String nativeType() {
            return switch (base) {
                case "decimal" -> base + '(' + precision + ',' + scale + ')';
                case "character", "varchar" -> base + '(' + length + ')';
                case "timestamp" -> base + '(' + fraction + ')';
                default -> base;
            };
        }

        /**
         * 比较实际回读定义与当前计划定义是否完全一致。
         */
        public Object exact(Object value, boolean strict) throws SQLException {
            if (value == null) {
                if (!nullable) {
                    throw new SQLException("DB2 required value is null");
                }
                return null;
            }
            switch (base) {
                case "smallint":
                    return integer(value, Short.MIN_VALUE, Short.MAX_VALUE).intValueExact();
                case "integer":
                    return integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE).intValueExact();
                case "bigint":
                    return integer(value, Long.MIN_VALUE, Long.MAX_VALUE).longValueExact();
                case "decimal": {
                    var number = decimal(value, scale);
                    if (number.abs().compareTo(BigDecimal.TEN.pow(precision - scale)) >= 0) {
                        throw new SQLException("DB2 decimal input is out of range");
                    }
                    return number;
                }
                case "date": {
                    LocalDate date = value instanceof LocalDate v ? v : value instanceof java.sql.Date v ? v.toLocalDate() : null;
                    if (date == null && civil(value) instanceof LocalDateTime time && time.toLocalTime()
                            .equals(LocalTime.MIDNIGHT)) {
                        date = time.toLocalDate();
                    }
                    if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
                        throw new SQLException("Exact DB2 date input required");
                    }
                    return date;
                }
                case "timestamp": {
                    LocalDateTime time = civil(value);
                    if (time == null || time.getYear() < 1 || time.getYear() > 9999 || time.getNano() % Db2CursorMetadata.fractionUnit(
                            fraction) != 0) {
                        throw new SQLException("DB2 timestamp would round this value");
                    }
                    return time;
                }
                case "time": {
                    LocalTime time = clock(value);
                    if (time == null || time.getNano() != 0) {
                        throw new SQLException("DB2 time stores whole seconds only");
                    }
                    return time;
                }
                case "character", "varchar": {
                    if (!(value instanceof String text)) {
                        throw new SQLException("Materialized DB2 text input required");
                    }
                    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > length) {
                        throw new SQLException("DB2 text input exceeds its domain");
                    }
                    return text;
                }
                default:
                    throw new SQLException("DB2 value has no exact replay codec");
            }
        }

        /** 时间值使用 java.sql 包装类型，便于驱动绑定。 */
        public Object bindable(Object value) {
            if (value instanceof LocalDateTime time) {
                return java.sql.Timestamp.valueOf(time);
            }
            if (value instanceof LocalDate date) {
                return java.sql.Date.valueOf(date);
            }
            if (value instanceof LocalTime time) {
                return java.sql.Time.valueOf(time);
            }
            return value;
        }
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    private static BigInteger integer(Object value, long minimum, long maximum) throws SQLException {
        BigInteger number;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger v) {
            number = v;
        } else if (value instanceof BigDecimal v) {
            try {
                number = v.toBigIntegerExact();
            } catch (ArithmeticException fractional) {
                throw new SQLException("DB2 integer input is fractional");
            }
        } else {
            throw new SQLException("Exact DB2 integer input required");
        }
        if (number.compareTo(BigInteger.valueOf(minimum)) < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw new SQLException("DB2 integer input is out of range");
        }
        return number;
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static BigDecimal decimal(Object value, int scale) throws SQLException {
        BigDecimal number;
        if (value instanceof BigDecimal v) {
            number = v;
        } else if (value instanceof BigInteger v) {
            number = new BigDecimal(v);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw new SQLException("Exact DB2 decimal input required");
        }
        try {
            return number.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new SQLException("DB2 decimal scale would be lost");
        }
    }

    /**
     * 按当前值域约定解析或转换本地日期时间。
     */
    private static LocalDateTime civil(Object value) {
        return value instanceof LocalDateTime v ? v : value instanceof java.sql.Timestamp v ? v.toLocalDateTime() : null;
    }

    /**
     * 返回当前时钟。
     */
    private static LocalTime clock(Object value) {
        if (value instanceof LocalTime v) {
            return v;
        }
        if (value instanceof java.sql.Time v) {
            return v.toLocalTime();
        }
        LocalDateTime time = civil(value);
        return time == null ? null : time.toLocalTime();
    }

    /**
     * 根据给定参数创建 {@code Column} 实例。
     */
    public static Column of(ColumnDescription column) throws SQLException {
        String type = Db2CursorMetadata.nativeType(column);
        var evidence = column.getMetadataEvidence();
        String base = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
        if (type.endsWith(" for bit data") || base.matches(
                "real|double|float|decfloat|clob|blob|dbclob|graphic|vargraphic|long varchar|xml")) {
            throw new SQLException("DB2 column has no exact write codec");
        }
        int length = -1;
        if (base.matches("character|varchar") && type.endsWith(")")) {
            length = Integer.parseInt(type.substring(base.length() + 1, type.length() - 1));
        }
        Integer precision = evidence.numericPrecision().value(), scale = evidence.numericScale().value();
        if (precision == null) {
            precision = 0;
        }
        if (scale == null) {
            scale = 0;
        }
        int fraction = -1;
        if (base.equals("timestamp")) {
            fraction = Integer.parseInt(type.substring(base.length() + 1, type.length() - 1));
        }
        return new Column(column.getFieldName(), base, length, precision, scale, fraction, column.isNullable(),
                column.isAutoIncrement());
    }

    /**
     * 读取当前关系中的数据行并保留受支持类型的精确值。
     */
    public static List<Object[]> exactRows(List<Object[]> rows, List<Column> order) {
        List<Object[]> copy = new ArrayList<>(rows.size());
        for (int row = 0; row < rows.size(); row++) {
            Object[] values = rows.get(row);
            if (values == null || values.length != order.size()) {
                throw new BatchRejectedException("DB2 batch column count mismatch");
            }
            Object[] exact = new Object[values.length];
            for (int i = 0; i < values.length; i++) {
                try {
                    exact[i] = order.get(i).bindable(order.get(i).exact(values[i], true));
                } catch (SQLException | RuntimeException failure) {
                    throw new BatchRejectedException(
                            "DB2 input cannot be represented exactly at row " + (row + 1) + ", column " + (i + 1));
                }
            }
            copy.add(exact);
        }
        return List.copyOf(copy);
    }

    /** DB2 原样绑定元数据类型；时间值已由 bindable() 包装。 */
    public static int[] bindingTypes(int[] metadataTypes, List<Column> order) {
        if (metadataTypes.length != order.size()) {
            throw new IllegalArgumentException("DB2 parameter types are incomplete");
        }
        return metadataTypes.clone();
    }
}
