package cn.iocoder.yudao.framework.dbswitch.product.sybase;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.math.*;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.util.*;

/**
 * 原生 MERGE 写入使用的目标列事实及精确 Sybase ASE 16 值域；
 * 借用连接前验证值，否则 ASE 会舍入 datetime 刻度、补齐 char(n)、
 * 截断 univarchar，或在批次已发送后才失败，结果为 UNKNOWN；float、real、money、text、image、
 * binary、bit 及有损的 1/300 秒 time 类型没有已验证的精确编解码器，因此予以拒绝。
 */
public final class SybaseWriteColumns {

    public record Column(String name, String base, int length, int precision, int scale, boolean nullable,
                         boolean identity) {

        /**
         * 根据原生列信息确定数据库类型声明。
         */
        public String nativeType() {
            return switch (base) {
                case "decimal", "numeric" -> base + '(' + precision + ',' + scale + ')';
                case "char", "varchar", "unichar", "univarchar" -> base + '(' + length + ')';
                default -> base;
            };
        }

        /**
         * 根据列属性确定参数绑定使用的类型。
         */
        public int bindingType(int metadataType) {
            return base.equals("bigtime") ? Types.TIMESTAMP : metadataType;
        }

        /**
         * 比较实际回读定义与当前计划定义是否完全一致。
         */
        public Object exact(Object value, boolean strict) throws SQLException {
            if (value == null) {
                if (!nullable) {
                    throw new SQLException("Sybase required value is null");
                }
                return null;
            }
            switch (base) {
                case "tinyint":
                    return integer(value, 0, 255).intValueExact();
                case "smallint":
                    return integer(value, Short.MIN_VALUE, Short.MAX_VALUE).intValueExact();
                case "int", "integer":
                    return integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE).intValueExact();
                case "bigint":
                    return integer(value, Long.MIN_VALUE, Long.MAX_VALUE).longValueExact();
                case "decimal", "numeric": {
                    var number = decimal(value, scale);
                    if (number.abs().compareTo(BigDecimal.TEN.pow(precision - scale)) >= 0) {
                        throw new SQLException("Sybase decimal input is out of range");
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
                        throw new SQLException("Exact Sybase date input required");
                    }
                    return date;
                }
                case "datetime": {
                    LocalDateTime time = civil(value);
                    if (time == null || time.getYear() < 1753 || time.getYear() > 9999 || !SybaseCursorMetadata.datetimeTick(
                            time.getNano())) {
                        throw new SQLException("Sybase datetime would round this value");
                    }
                    return time;
                }
                case "bigdatetime": {
                    LocalDateTime time = civil(value);
                    if (time == null || time.getYear() < 1 || time.getYear() > 9999 || time.getNano() % 1_000 != 0) {
                        throw new SQLException("Sybase bigdatetime would round this value");
                    }
                    return time;
                }
                case "bigtime": {
                    LocalTime time = clock(value);
                    if (time == null || time.getNano() % 1_000 != 0) {
                        throw new SQLException("Sybase bigtime would round this value");
                    }
                    return time;
                }
                case "char", "varchar", "unichar", "univarchar": {
                    if (!(value instanceof String text)) {
                        throw new SQLException("Materialized Sybase text input required");
                    }
                    int chars = base.startsWith("uni") ? text.length() : text.getBytes(
                            java.nio.charset.StandardCharsets.ISO_8859_1).length;
                    if (chars > length) {
                        throw new SQLException("Sybase text input exceeds its domain");
                    }
                    return text;
                }
                default:
                    throw new SQLException("Sybase value has no exact replay codec");
            }
        }

        /** 时间值使用 java.sql.Timestamp，bigtime 使用日期 0001-01-01 的载体，便于驱动绑定。 */
        public Object bindable(Object value) {
            if (value instanceof LocalDateTime time) {
                return java.sql.Timestamp.valueOf(time);
            }
            if (value instanceof LocalDate date) {
                return java.sql.Date.valueOf(date);
            }
            if (value instanceof LocalTime time) {
                return java.sql.Timestamp.valueOf(LocalDate.of(1, 1, 1).atTime(time));
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
                throw new SQLException("Sybase integer input is fractional");
            }
        } else {
            throw new SQLException("Exact Sybase integer input required");
        }
        if (number.compareTo(BigInteger.valueOf(minimum)) < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw new SQLException("Sybase integer input is out of range");
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
            throw new SQLException("Exact Sybase decimal input required");
        }
        try {
            return number.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new SQLException("Sybase decimal scale would be lost");
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
        String type = SybaseCursorMetadata.nativeType(column);
        var evidence = column.getMetadataEvidence();
        String base = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
        int length = -1;
        if (base.matches("(?:uni)?(?:var)?char") && type.endsWith(")")) {
            length = Integer.parseInt(type.substring(base.length() + 1, type.length() - 1));
        }
        Integer precision = evidence.numericPrecision().value(), scale = evidence.numericScale().value();
        if (precision == null) {
            precision = 0;
        }
        if (scale == null) {
            scale = 0;
        }
        return new Column(column.getFieldName(), base, length, precision, scale, column.isNullable(),
                evidence.identity()
                        .state() == cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.VALUE);
    }

    /**
     * 读取当前关系中的数据行并保留受支持类型的精确值。
     */
    public static List<Object[]> exactRows(List<Object[]> rows, List<Column> order) {
        List<Object[]> copy = new ArrayList<>(rows.size());
        for (int row = 0; row < rows.size(); row++) {
            Object[] values = rows.get(row);
            if (values == null || values.length != order.size()) {
                throw new BatchRejectedException("Sybase batch column count mismatch");
            }
            Object[] exact = new Object[values.length];
            for (int i = 0; i < values.length; i++) {
                try {
                    exact[i] = order.get(i).bindable(order.get(i).exact(values[i], true));
                } catch (SQLException | RuntimeException failure) {
                    throw new BatchRejectedException(
                            "Sybase input cannot be represented exactly at row " + (row + 1) + ", column " + (i + 1));
                }
            }
            copy.add(exact);
        }
        return List.copyOf(copy);
    }

    /**
     * 返回当前参数集合对应的绑定类型。
     */
    public static int[] bindingTypes(int[] metadataTypes, List<Column> order) {
        if (metadataTypes.length != order.size()) {
            throw new IllegalArgumentException("Sybase parameter types are incomplete");
        }
        int[] types = metadataTypes.clone();
        for (int i = 0; i < types.length; i++) {
            types[i] = order.get(i).bindingType(types[i]);
        }
        return types;
    }
}
