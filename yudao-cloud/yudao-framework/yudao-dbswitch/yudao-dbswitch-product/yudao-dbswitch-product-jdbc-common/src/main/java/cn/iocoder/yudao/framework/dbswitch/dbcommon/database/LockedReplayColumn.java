package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.*;
import java.sql.*;

/** 由原生产品观测器提供的精确标量契约，绝不仅根据 JDBC 类型推断。 */
public record LockedReplayColumn(String nativeType, int jdbcType, boolean nullable,
                                 CursorKeyContract.Domain domain, int maximumCharacters, boolean asciiOnly) {

    /**
     * 校验构造参数并初始化 {@code LockedReplayColumn}。
     */
    public LockedReplayColumn {
        if (nativeType == null || !nativeType.matches(
                "(?:int|bigint|smallint|(?:decimal|numeric)\\([1-9][0-9]*,[0-9]+\\)|n?varchar\\([1-9][0-9]*\\))")
                || domain == null && maximumCharacters < 1 || domain != null && domain.numeric() == null) {
            throw new IllegalArgumentException("Incomplete exact native scalar contract");
        }
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    public Object exact(Object value) throws SQLException {
        if (value == null) {
            if (!nullable) {
                throw new SQLException("NULL input violates native nullability");
            }
            return null;
        }
        if (domain != null) {
            BigDecimal decimal;
            if (value instanceof BigDecimal v) {
                decimal = v;
            } else if (value instanceof BigInteger v) {
                decimal = new BigDecimal(v);
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                decimal = BigDecimal.valueOf(((Number) value).longValue());
            } else {
                throw new SQLException("Exact numeric input required");
            }
            var numeric = domain.numeric();
            try {
                decimal = decimal.setScale(numeric.scale(), RoundingMode.UNNECESSARY);
            } catch (ArithmeticException lossy) {
                throw new SQLException("Numeric input would lose scale");
            }
            if (decimal.compareTo(numeric.minimum()) < 0 || decimal.compareTo(numeric.maximum()) > 0) {
                throw new SQLException("Numeric input is outside its native range");
            }
            return switch (jdbcType) {
                case Types.SMALLINT -> decimal.shortValueExact();
                case Types.INTEGER -> decimal.intValueExact();
                case Types.BIGINT -> decimal.longValueExact();
                default -> decimal;
            };
        }
        if (!(value instanceof String text) || text.length() > maximumCharacters || text.indexOf('\0') >= 0) {
            throw new SQLException("Text input is outside its exact native range");
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (asciiOnly && ch > 127) {
                throw new SQLException("This native character-set profile requires ASCII input");
            }
            if (Character.isHighSurrogate(ch)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new SQLException("Malformed Unicode input");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new SQLException("Malformed Unicode input");
            }
        }
        return text;
    }

    /**
     * 从 JDBC 结果集读取当前字段或数据行。
     */
    public Object read(ResultSet rows, int index, long maximumCellBytes) throws SQLException {
        if (domain != null) {
            return exact(rows.getBigDecimal(index));
        }
        // 产品准入将文本限制为 <= 8000 个代码单元；发布前检查每个单元格的额度。
        String text = rows.getString(index);
        if (text != null && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximumCellBytes) {
            throw new SQLException("Native text cell exceeds the read budget");
        }
        return exact(text);
    }

    /**
     * 要求游标列非空且具有精确可排序的数值域。
     */
    public void requireOrdered() throws SQLException {
        if (nullable || domain == null) {
            throw new SQLException("Locked cursor ordering requires an exact non-null numeric column");
        }
    }

    /**
     * 返回游标。
     */
    public TypedCursorValue cursor(Object value) throws SQLException {
        requireOrdered();
        return TypedCursorValue.of(jdbcType, nativeType, exact(value));
    }

    /**
     * 校验当前重放值符合已声明列的精确值域。
     */
    public void validate(TypedCursorValue value) throws SQLException {
        requireOrdered();
        if (value.jdbcType() != jdbcType || !nativeType.equals(value.typeName())) {
            throw new SQLException("Cursor native scalar type changed");
        }
        exact(value.jdbcValue());
    }
}
