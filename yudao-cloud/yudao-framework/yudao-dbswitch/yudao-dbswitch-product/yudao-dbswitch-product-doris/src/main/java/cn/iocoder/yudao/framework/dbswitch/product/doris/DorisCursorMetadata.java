package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于现有原生证据的 Doris 4.0 游标结构：关系代次（集群、主体、物理
 * 编号和分区代次）加有界 SHOW CREATE 目录。游标源必须使用 UNIQUE 键模型，
 * 使有序键集构成全序。水位列和键列仅接受精确数值、精确时间和
 * 文本；文本键仅在相同原生契约下重放。时间戳为本地日期时间，绝非纪元计数。
 */
public final class DorisCursorMetadata {

    public static final String VERIFIED_SERVER = "doris-4.0.";

    /**
     * 初始化 {@code DorisCursorMetadata}。
     */
    private DorisCursorMetadata() {
    }

    public record Shape(String schema, String table, DorisJdbcSupport.Evidence evidence) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            DorisNativeSchema.quote(schema);
            DorisNativeSchema.quote(table);
        }

        /**
         * 返回目录。
         */
        public DorisNativeSchema.Table catalog() {
            return evidence.table();
        }

        /**
         * 返回代次。
         */
        public String generation() {
            return evidence.generation();
        }

        /**
         * 返回物理。
         */
        public String physical() {
            return evidence.physical();
        }

        /**
         * 返回键集合。
         */
        public List<String> keys() {
            return catalog().keys();
        }

        /**
         * 返回列。
         */
        public DorisNativeSchema.Column column(String name) {
            return catalog().column(name);
        }

        /** 完整物理结构和代次证据；纳入源、目标指纹。 */
        public String relationEvidence() {
            return "DORIS_CURSOR_RELATION_V1\0" + generation() + '\0' + physical() + '\0' + catalog().toString();
        }

        /** 有序键相等值域。不包含列名，使显式重命名仍然可行。 */
        public String keyEvidence() {
            var text = new StringBuilder("DORIS_CURSOR_KEY_V1\0").append(VERIFIED_SERVER);
            for (String key : keys()) {
                text.append('\0').append(column(key).type());
            }
            return text.toString();
        }

        /**
         * 返回当前源端读取契约。
         */
        public CursorKeyContract sourceContract() {
            var domains = new ArrayList<CursorKeyContract.Domain>();
            for (String key : keys()) {
                domains.add(domain(column(key)));
            }
            return new CursorKeyContract(ProductTypeEnum.DORIS, DigestUtil.sha256Hex(keyEvidence()), domains);
        }
    }

    /** 精确键值域：整数位宽、十进制精度和小数位数，LARGEINT 作为 decimal(39,0)；时间、文本仅接受原生类型。 */
    public static CursorKeyContract.Domain domain(DorisNativeSchema.Column column) {
        return switch (column.kind()) {
            case "tinyint" -> CursorKeyContract.Domain.integer(8, false);
            case "smallint" -> CursorKeyContract.Domain.integer(16, false);
            case "int" -> CursorKeyContract.Domain.integer(32, false);
            case "bigint" -> CursorKeyContract.Domain.integer(64, false);
            case "largeint" -> CursorKeyContract.Domain.decimal(39, 0, false);
            case "decimal" -> {
                int precision = column.precision(), scale = column.scale();
                if (precision < 1 || scale < 0 || scale > precision) {
                    throw unsupportedArgument("Doris decimal key domain is unavailable");
                }
                yield CursorKeyContract.Domain.decimal(precision, scale, false);
            }
            case "date", "datetime", "char", "varchar", "string" -> CursorKeyContract.Domain.nativeOnly();
            default -> throw unsupportedArgument("Doris cursor key type is not verified");
        };
    }

    /**
     * 判断当前类型是否处于已支持的游标键值域。
     */
    public static boolean supportedKeyType(DorisNativeSchema.Column column) {
        return switch (column.kind()) {
            case "tinyint", "smallint", "int", "bigint", "largeint", "decimal", "date", "datetime", "char", "varchar",
                 "string" -> true;
            default -> false;
        };
    }

    /** 每种 Doris 目录类型都有精确读取编解码器；投影仅需已验证列。 */
    public static boolean supportedValueType(DorisNativeSchema.Column column) {
        try {
            column.jdbc();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection connection, String schema, String table, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("Doris cursor requires a bounded connection");
        }
        var evidence = DorisJdbcSupport.inspect(connection, schema, table, timeout);
        return new Shape(schema, table, evidence);
    }

    /** 投影列的精确源值；读取器先通过类型化 ResultSet 包装数据行。 */
    public static Object readValue(ResultSet rows, int index, DorisNativeSchema.Column column) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) {
            return null;
        }
        try {
            return DorisJdbcSupport.value(value, column);
        } catch (IllegalArgumentException invalid) {
            throw unsupported("Doris projected value is outside its verified codec");
        }
    }

    /** 单个有序键的类型化游标分量。 */
    public static TypedCursorValue cursorValue(DorisNativeSchema.Column column, Object value) {
        return TypedCursorValue.of(cursorType(column), column.type(), value);
    }

    /** 校验持久化分量，并返回其规范比较值。 */
    public static Object cursorComponent(DorisNativeSchema.Column column, TypedCursorValue value) {
        if (value == null || value.jdbcType() != cursorType(column) || !value.typeName().equals(column.type())) {
            throw new IllegalArgumentException("Doris cursor tuple type changed");
        }
        Object decoded = value.jdbcValue();
        if (decoded instanceof BigDecimal decimal) {
            if (column.kind().equals("largeint")) {
                if (decimal.toBigIntegerExact().bitLength() > 127) {
                    throw new IllegalArgumentException("Doris cursor key is outside its native range");
                }
                return decimal;
            }
            int scale = column.scale(), precision = column.precision();
            if (decimal.scale() > scale || decimal.precision() - decimal.scale() > precision - scale) {
                throw new IllegalArgumentException("Doris cursor key is outside its native range");
            }
            return decimal;
        }
        return decoded;
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, DorisNativeSchema.Column column,
            Object value) throws SQLException {
        if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof LocalDateTime time) {
            statement.setObject(index, time);
        } else if (value instanceof LocalDate date) {
            statement.setObject(index, date);
        } else {
            statement.setObject(index, value, cursorType(column));
        }
    }

    /**
     * 返回游标类型。
     */
    private static int cursorType(DorisNativeSchema.Column column) {
        return switch (column.kind()) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "int" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "largeint", "decimal" -> Types.NUMERIC;
            case "date" -> Types.DATE;
            case "datetime" -> Types.TIMESTAMP;
            case "char", "varchar", "string" -> Types.VARCHAR;
            default -> throw new IllegalArgumentException("Doris cursor key type is unsupported");
        };
    }

    /** 对服务器回读的数值键证明范围；文本长度已由目录限制。 */
    static void validateInteger(DorisNativeSchema.Column column, BigInteger integer) {
        int bits = switch (column.kind()) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "int" -> 32;
            case "bigint" -> 64;
            default -> 0;
        };
        if (bits == 0) {
            return;
        }
        if (integer.bitLength() > bits - 1) {
            throw new IllegalArgumentException("Doris cursor key is outside its native range");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static IllegalArgumentException unsupportedArgument(String reason) {
        return new IllegalArgumentException(reason);
    }
}
