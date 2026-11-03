package cn.iocoder.yudao.framework.dbswitch.product.oracle;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata.Column;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用于强类型游标载荷读取与 MERGE 绑定的已验证 Oracle 值域。时间载荷列
 * 投影为固定格式服务端文本 TO_CHAR，再解析为本地 LocalDateTime，避免驱动或
 * JVM 时区转换偏移夏令时空档值。NUMBER 载荷读取为精确 BigDecimal，RAW 以
 * 字节传输。未列出的类型包括 BLOB、CLOB、NCLOB、BFILE、ROWID、UROWID、FLOAT、BINARY_FLOAT、BINARY_DOUBLE、
 * INTERVAL、TIMESTAMP WITH TIME ZONE、XML、JSON 及空间类型，均在任何副作用之前拒绝。
 */
public final class OracleExactValues {

    public enum Kind {NUMBER, TEXT, RAW, DATE, TIMESTAMP}

    /**
     * 初始化 {@code OracleExactValues}。
     */
    private OracleExactValues() {
    }

    /**
     * 根据当前输入判定类型或操作种类。
     */
    public static Kind kind(Column column) throws SQLException {
        return switch (column.dataType()) {
            case "NUMBER" -> Kind.NUMBER;
            case "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR" -> Kind.TEXT;
            case "RAW" -> Kind.RAW;
            case "DATE" -> Kind.DATE;
            case "TIMESTAMP" -> Kind.TIMESTAMP;
            default -> throw OracleCursorMetadata.unsupported(
                    "Oracle column type is not verified for typed cursor/UPSERT");
        };
    }

    /** 可写载荷列必须是普通列；RAW 限定为经典的 2000 字节值域。 */
    public static Kind writable(Column column) throws SQLException {
        Kind kind = kind(column);
        if (column.virtual() || column.identity() || column.hidden()
                || kind == Kind.RAW && (column.charLength() == null || column.charLength() < 1 || column.charLength() > 2000)) {
            throw OracleCursorMetadata.unsupported("Oracle target column is generated or exceeds a verified domain");
        }
        return kind;
    }

    // ---- 读取 ---------------------------------------------------------------------------------------

    /** 时间列投影为固定格式服务端文本，其他列采用原生读取。 */
    public static String projection(Column column) throws SQLException {
        Kind kind = kind(column);
        String quoted = OracleCursorMetadata.quote(column.name());
        return switch (kind) {
            case DATE -> "TO_CHAR(" + quoted + ",'YYYY-MM-DD HH24:MI:SS')";
            case TIMESTAMP ->
                    "TO_CHAR(" + quoted + ",'YYYY-MM-DD HH24:MI:SS" + (column.scale() == 0 ? "" : ".FF" + column.scale()) + "')";
            default -> quoted;
        };
    }

    /**
     * 从 JDBC 结果集读取当前字段或数据行。
     */
    public static Object read(ResultSet rows, int index, Column column) throws SQLException {
        return switch (kind(column)) {
            case NUMBER -> rows.getBigDecimal(index);
            case TEXT -> rows.getString(index);
            case RAW -> rows.getBytes(index);
            case DATE -> {
                String text = rows.getString(index);
                yield text == null ? null : temporal(text, 0);
            }
            case TIMESTAMP -> {
                String text = rows.getString(index);
                yield text == null ? null : temporal(text, column.scale());
            }
        };
    }

    // ---- 游标键 -----------------------------------------------------------------------------------

    /**
     * 根据键列精确值域选择 JDBC 类型。
     */
    public static int keyJdbcType(Column column) throws SQLException {
        return OracleCursorMetadata.cursorJdbcType(column);
    }

    /**
     * 根据键列类型生成参数占位符及必要的类型转换。
     */
    public static String keyParameter(Column column) throws SQLException {
        return OracleCursorMetadata.parameter(column);
    }

    /**
     * 返回键。
     */
    public static TypedCursorValue key(Column column, Object value) throws SQLException {
        if (value == null) {
            throw new SQLException("Oracle cursor key is NULL");
        }
        return TypedCursorValue.of(keyJdbcType(column), column.keySqlType(), OracleCursorMetadata.key(value, column));
    }

    /**
     * 将游标键值绑定到查询或写入语句参数。
     */
    public static void bindKey(PreparedStatement statement, int index, TypedCursorValue value,
            Column column) throws SQLException {
        OracleCursorMetadata.bind(statement, index, value, column);
    }

    // ---- UPSERT 绑定 --------------------------------------------------------------------------------

    /** 在借用连接之前证明整批值的精确性。 */
    public static Object write(Column column, Object value) {
        try {
            Kind kind = writable(column);
            if (value == null) {
                if (!column.nullable()) {
                    throw new IllegalArgumentException("NULL cannot be written to a NOT NULL Oracle column");
                }
                return null;
            }
            return switch (kind) {
                case NUMBER -> OracleCursorMetadata.exact(value, column);
                case TEXT -> OracleCursorMetadata.exact(value, column);
                case RAW -> raw(column, value);
                case DATE, TIMESTAMP -> OracleCursorMetadata.exact(value, column);
            };
        } catch (SQLException unsupported) {
            throw new IllegalArgumentException("Oracle target column is not verified");
        }
    }

    /**
     * 绑定类型。
     */
    public static int bindType(Column column) throws SQLException {
        return switch (writable(column)) {
            case NUMBER -> Types.NUMERIC;
            case TEXT -> "NVARCHAR2".equals(column.dataType()) || "NCHAR".equals(
                    column.dataType()) ? Types.NVARCHAR : Types.VARCHAR;
            case RAW -> Types.VARBINARY;
            case DATE, TIMESTAMP -> Types.TIMESTAMP;
        };
    }

    /**
     * 返回当前原始。
     */
    private static byte[] raw(Column column, Object value) throws SQLException {
        if (!(value instanceof byte[] bytes)) {
            throw new SQLException("Oracle RAW input requires bytes");
        }
        if (column.charLength() == null || column.charLength() < 1 || column.charLength() > 2000 || bytes.length > column.charLength()) {
            throw new SQLException("Oracle RAW input exceeds the target domain");
        }
        return bytes;
    }

    /**
     * 按当前值域约定解析或转换时间值。
     */
    private static LocalDateTime temporal(String text, int scale) throws SQLException {
        try {
            if (scale < 0 || scale > 9) {
                throw new IllegalArgumentException();
            }
            var value = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss" + (scale == 0 ? "" : "." + "S".repeat(scale)))
                    .parse(text, LocalDateTime::from);
            if (value.getYear() < 1 || value.getYear() > 9999) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new SQLException("Oracle temporal text is not representable");
        }
    }
}
