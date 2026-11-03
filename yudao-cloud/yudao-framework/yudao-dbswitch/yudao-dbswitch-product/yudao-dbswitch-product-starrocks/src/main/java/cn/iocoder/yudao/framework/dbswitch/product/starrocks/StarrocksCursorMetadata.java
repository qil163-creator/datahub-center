package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
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
 * 基于现有原生证据的 StarRocks 3.5 游标结构：关系身份（集群、主体、表编号和
 * 分区代次）加已验证的 tables_config/DESC/SHOW INDEX 目录。游标源必须使用
 * PRIMARY_KEYS 模型，使有序键集构成全序，且每个键列均非空。水位列和键
 * 列仅接受精确数值、精确时间和文本；文本键仅在相同原生
 * 契约下重放。时间戳为本地日期时间，绝非纪元计数。
 */
public final class StarrocksCursorMetadata {

    public static final String VERIFIED_SERVER = "3.5.";

    /**
     * 初始化 {@code StarrocksCursorMetadata}。
     */
    private StarrocksCursorMetadata() {
    }

    public record Shape(String schema, String table, StarrocksJdbcSupport.Table catalog) {

        /**
         * 初始化 {@code Shape}。
         */
        public Shape {
            StarrocksJdbcSupport.table(schema, table);
        }

        /**
         * 返回身份信息。
         */
        public String identity() {
            return catalog.identity();
        }

        /**
         * 读取当前表的原生物理身份。
         */
        public String physicalIdentity() {
            return catalog.physicalIdentity();
        }

        /**
         * 返回键集合。
         */
        public List<String> keys() {
            return catalog.keys();
        }

        /**
         * 返回列。
         */
        public StarrocksJdbcSupport.Column column(String name) {
            return catalog.column(name);
        }

        /** 完整物理结构和代次证据；纳入源、目标指纹。 */
        public String evidence() {
            return "STARROCKS_CURSOR_RELATION_V1\0" + identity() + '\0' + physicalIdentity() + '\0' + catalog.shape()
                    .toString();
        }

        /** 有序键相等值域。不包含列名，使显式重命名仍然可行。 */
        public String keyEvidence() {
            var text = new StringBuilder("STARROCKS_CURSOR_KEY_V1\0").append(VERIFIED_SERVER);
            for (String key : keys()) {
                text.append('\0').append(column(key).nativeType());
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
            return new CursorKeyContract(ProductTypeEnum.STARROCKS, DigestUtil.sha256Hex(keyEvidence()), domains);
        }
    }

    /** 精确键值域：整数位宽、十进制精度和小数位数，LARGEINT 作为 decimal(39,0)；时间、文本仅接受原生类型。 */
    public static CursorKeyContract.Domain domain(StarrocksJdbcSupport.Column column) {
        String type = column.nativeType();
        if (type.equals("tinyint")) {
            return CursorKeyContract.Domain.integer(8, false);
        }
        if (type.equals("smallint")) {
            return CursorKeyContract.Domain.integer(16, false);
        }
        if (type.equals("int")) {
            return CursorKeyContract.Domain.integer(32, false);
        }
        if (type.equals("bigint")) {
            return CursorKeyContract.Domain.integer(64, false);
        }
        if (type.equals("largeint")) {
            return CursorKeyContract.Domain.decimal(39, 0, false);
        }
        if (type.startsWith("decimal(")) {
            return CursorKeyContract.Domain.decimal(column.precision(), column.scale(), false);
        }
        if (type.equals("date") || type.equals("datetime")) {
            return CursorKeyContract.Domain.nativeOnly();
        }
        if (type.startsWith("varchar(") || type.startsWith("char(")) {
            return CursorKeyContract.Domain.nativeOnly();
        }
        throw unsupportedArgument("StarRocks cursor key type is not verified");
    }

    /**
     * 判断当前类型是否处于已支持的游标键值域。
     */
    public static boolean supportedKeyType(StarrocksJdbcSupport.Column column) {
        try {
            domain(column);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** 每种有界 StarRocks 目录类型都有精确读取编解码器。 */
    public static boolean supportedValueType(StarrocksJdbcSupport.Column column) {
        return true;
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection connection, String schema, String table, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("StarRocks cursor requires a bounded connection");
        }
        return new Shape(schema, table, StarrocksJdbcSupport.metadata(connection, schema, table, timeout));
    }

    /** 投影列的精确源值；读取器先通过类型化 ResultSet 包装数据行。 */
    public static Object readValue(ResultSet rows, int index, StarrocksJdbcSupport.Column column) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            if (time.getYear() < 1 || time.getYear() > 9999) {
                throw unsupported("StarRocks DATETIME range is not verified");
            }
            return time;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp stamp) {
            LocalDateTime time = stamp.toLocalDateTime();
            if (time.getYear() < 1 || time.getYear() > 9999) {
                throw unsupported("StarRocks DATETIME range is not verified");
            }
            return time;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof String || value instanceof Boolean || value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            if (decimal.precision() > column.precision() || decimal.scale() > column.scale()) {
                throw unsupported("StarRocks decimal value is outside its native range");
            }
            return decimal;
        }
        throw unsupported("StarRocks projected value is outside its verified codec");
    }

    /** 单个有序键的类型化游标分量。 */
    public static TypedCursorValue cursorValue(StarrocksJdbcSupport.Column column, Object value) {
        return TypedCursorValue.of(cursorType(column), column.nativeType(), value);
    }

    /** 校验持久化分量，并返回其规范比较值。 */
    public static Object cursorComponent(StarrocksJdbcSupport.Column column, TypedCursorValue value) {
        if (value == null || value.jdbcType() != cursorType(column) || !value.typeName().equals(column.nativeType())) {
            throw new IllegalArgumentException("StarRocks cursor tuple type changed");
        }
        Object decoded = value.jdbcValue();
        if (decoded instanceof BigDecimal decimal) {
            if (column.nativeType().equals("largeint")) {
                if (decimal.toBigIntegerExact().bitLength() > 127) {
                    throw new IllegalArgumentException("StarRocks cursor key is outside its native range");
                }
                return decimal;
            }
            if (decimal.scale() > column.scale() || decimal.precision() - decimal.scale() > column.precision() - column.scale()) {
                throw new IllegalArgumentException("StarRocks cursor key is outside its native range");
            }
            return decimal;
        }
        return decoded;
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, StarrocksJdbcSupport.Column column,
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
    private static int cursorType(StarrocksJdbcSupport.Column column) {
        String type = column.nativeType();
        if (type.equals("tinyint")) {
            return Types.TINYINT;
        }
        if (type.equals("smallint")) {
            return Types.SMALLINT;
        }
        if (type.equals("int")) {
            return Types.INTEGER;
        }
        if (type.equals("bigint")) {
            return Types.BIGINT;
        }
        if (type.equals("largeint") || type.startsWith("decimal(")) {
            return Types.NUMERIC;
        }
        if (type.equals("date")) {
            return Types.DATE;
        }
        if (type.equals("datetime")) {
            return Types.TIMESTAMP;
        }
        if (type.startsWith("varchar(") || type.startsWith("char(")) {
            return Types.VARCHAR;
        }
        throw new IllegalArgumentException("StarRocks cursor key type is unsupported");
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
