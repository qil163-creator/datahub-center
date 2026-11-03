package cn.iocoder.yudao.framework.dbswitch.common.entity;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcTypesUtils;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 已脱离驱动的一维 JDBC 数组，下界为 1，不保留驱动或连接引用。 */
public final class JdbcArrayValue {

    private final String baseTypeName;

    private final int baseJdbcType;

    private final List<Object> elements;

    private final long estimatedBytes;

    /**
     * 校验构造参数并初始化 {@code JdbcArrayValue}。
     */
    public JdbcArrayValue(String baseTypeName, int baseJdbcType, List<?> elements) {
        if (baseTypeName == null || elements == null) {
            throw new IllegalArgumentException("Invalid JDBC array");
        }
        int expected = switch (baseTypeName) {
            case "int2" -> Types.SMALLINT;
            case "int4" -> Types.INTEGER;
            case "int8" -> Types.BIGINT;
            case "numeric" -> Types.NUMERIC;
            case "text", "varchar", "bpchar" -> Types.VARCHAR;
            case "bool" -> Types.BIT;
            default -> throw new IllegalArgumentException("Unsupported JDBC array element type");
        };
        if (baseJdbcType != expected && !(expected == Types.VARCHAR && baseJdbcType == Types.CHAR)
                && !(expected == Types.BIT && baseJdbcType == Types.BOOLEAN)
                && !(expected == Types.NUMERIC && baseJdbcType == Types.DECIMAL)) {
            throw new IllegalArgumentException("JDBC array type mismatch");
        }
        List<Object> owned = new ArrayList<>(elements.size());
        long bytes = 32L + 2L * baseTypeName.length();
        for (Object value : elements) {
            boolean valid = value == null || switch (expected) {
                case Types.SMALLINT -> value instanceof Short || value instanceof Integer;
                case Types.INTEGER -> value instanceof Integer;
                case Types.BIGINT -> value instanceof Long;
                case Types.NUMERIC -> value instanceof BigDecimal;
                case Types.VARCHAR -> value instanceof String;
                case Types.BIT -> value instanceof Boolean;
                default -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("Unsupported JDBC array element value");
            }
            bytes = Math.addExact(bytes, Math.addExact(8L, JdbcTypesUtils.getObjectSize(Types.OTHER, value)));
            owned.add(value);
        }
        this.baseTypeName = baseTypeName;
        this.baseJdbcType = baseJdbcType;
        this.elements = Collections.unmodifiableList(owned);
        this.estimatedBytes = bytes;
    }

    /**
     * 返回数组元素类型名。
     */
    public String baseTypeName() {
        return baseTypeName;
    }

    /**
     * 返回数组元素 JDBC 类型。
     */
    public int baseJdbcType() {
        return baseJdbcType;
    }

    /**
     * 返回数组元素。
     */
    public List<Object> elements() {
        return elements;
    }

    /**
     * 返回数组元素数组。
     */
    public Object[] elementsArray() {
        return elements.toArray();
    }

    /**
     * 返回估算字节数。
     */
    public long estimatedBytes() {
        return estimatedBytes;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "JdbcArrayValue[type=" + baseTypeName + ", size=" + elements.size() + "]";
    }
}
