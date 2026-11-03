package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/** 在 ClickHouse 隐式数值转换可能截断或回绕前校验值。 */
public final class ClickhouseValueConversions {

    /**
     * 初始化 {@code ClickhouseValueConversions}。
     */
    private ClickhouseValueConversions() {
    }

    public record Column(int jdbcType, String nativeType, int precision, int scale, boolean nullable) {

    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static Map<String, Column> columns(ResultSetMetaData metadata) throws SQLException {
        var columns = new HashMap<String, Column>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            columns.put(metadata.getColumnName(index),
                    new Column(metadata.getColumnType(index), metadata.getColumnTypeName(index),
                            metadata.getPrecision(index),
                            metadata.getScale(index), metadata.isNullable(index) != ResultSetMetaData.columnNoNulls));
        }
        return Map.copyOf(columns);
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static Object convert(Object value, Column column) {
        if (column == null) {
            throw new IllegalArgumentException("ClickHouse column metadata is missing");
        }
        if (value == null) {
            if (!column.nullable()) {
                throw new IllegalArgumentException("ClickHouse target column does not accept NULL");
            }
            return null;
        }
        if (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC) {
            if (column.precision() < 1 || column.precision() > 76 || column.scale() < 0 || column.scale() > column.precision()) {
                throw new IllegalArgumentException("ClickHouse decimal metadata is invalid");
            }
            try {
                var decimal = JdbcValueConversions.toDecimal(value).setScale(column.scale(), RoundingMode.UNNECESSARY);
                if (decimal.precision() > column.precision()) {
                    throw new ArithmeticException();
                }
                return decimal;
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("ClickHouse decimal cannot be represented exactly");
            }
        }
        String nativeType = column.nativeType();
        if (nativeType != null) {
            if (nativeType.startsWith("Nullable(") && nativeType.endsWith(")")) {
                nativeType = nativeType.substring(9, nativeType.length() - 1);
            }
            var integer = java.util.regex.Pattern.compile("(U?)Int(8|16|32|64|128|256)").matcher(nativeType);
            if (integer.matches()) {
                try {
                    BigInteger exact = JdbcValueConversions.toDecimal(value).toBigIntegerExact();
                    boolean unsigned = !integer.group(1).isEmpty();
                    int bits = Integer.parseInt(integer.group(2));
                    BigInteger boundary = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1);
                    if (exact.compareTo(unsigned ? BigInteger.ZERO : boundary.negate()) < 0 || exact.compareTo(
                            boundary) >= 0) {
                        throw new ArithmeticException();
                    }
                    return exact;
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException("ClickHouse integer cannot be represented exactly");
                }
            }
        }
        return value;
    }

    /** JDBC 0.5 的变更或表达式参数使用 SQL 字面值。裸 Decimal 会经过 Float64。 */
    public static String placeholder(Column column) {
        if (column != null && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)) {
            String type = "Decimal(" + column.precision() + "," + column.scale() + ")";
            return "CAST(? AS " + (column.nullable() ? "Nullable(" + type + ")" : type) + ")";
        }
        return "?";
    }

    /**
     * 绑定表达式。
     */
    public static void bindExpression(java.sql.PreparedStatement statement, int index, Object value,
            Column column) throws SQLException {
        if (value != null && column != null && (column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC)) {
            statement.setString(index, ((java.math.BigDecimal) value).toPlainString());
        } else {
            statement.setObject(index, value);
        }
    }
}
