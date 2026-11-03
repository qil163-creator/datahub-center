package cn.iocoder.yudao.framework.dbswitch.common.entity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 精确保留的持久化标量：不拼接分隔符、不截断纪元时间，也不使用浮点数。 */
public record TypedCursorValue(int jdbcType, String typeName, String value) {

    /**
     * 校验构造参数并初始化 {@code TypedCursorValue}。
     */
    public TypedCursorValue {
        if (typeName == null || typeName.isBlank() || typeName.length() > 128 || value == null || value.length() > 8192) {
            throw new IllegalArgumentException("Invalid typed cursor value");
        }
        decode(jdbcType, typeName, value);
    }

    /**
     * 按当前游标类型解码 JDBC 参数值。
     */
    public Object jdbcValue() {
        return decode(jdbcType, typeName, value);
    }

    /**
     * 根据给定参数创建 {@code TypedCursorValue} 实例。
     */
    public static TypedCursorValue of(int type, String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("NULL cursor values are unsupported");
        }
        String text;
        if (value instanceof BigDecimal decimal) {
            text = decimal.toPlainString();
        } else if (value instanceof java.sql.Timestamp timestamp) {
            text = timestamp.toLocalDateTime().toString();
        } else if (value instanceof java.sql.Date date) {
            text = date.toLocalDate().toString();
        } else {
            text = value.toString();
        }
        return new TypedCursorValue(type, name, text);
    }

    /**
     * 从编码数据恢复对象。
     */
    private static Object decode(int type, String name, String value) {
        try {
            return switch (type) {
                case Types.TINYINT, Types.SMALLINT -> new BigInteger(value).shortValueExact();
                case Types.INTEGER -> new BigInteger(value).intValueExact();
                case Types.BIGINT -> new BigInteger(value).longValueExact();
                case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value);
                case Types.DATE -> LocalDate.parse(value);
                case Types.TIME -> java.time.LocalTime.parse(value);
                case Types.TIMESTAMP -> LocalDateTime.parse(value);
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(value);
                case Types.VARCHAR, Types.NVARCHAR -> value;
                case Types.OTHER -> {
                    if (!"uuid".equals(name)) {
                        throw new IllegalArgumentException();
                    }
                    UUID id = UUID.fromString(value);
                    if (!id.toString().equals(value)) {
                        throw new IllegalArgumentException();
                    }
                    yield id;
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Unsupported or invalid typed cursor value");
        }
    }
}
