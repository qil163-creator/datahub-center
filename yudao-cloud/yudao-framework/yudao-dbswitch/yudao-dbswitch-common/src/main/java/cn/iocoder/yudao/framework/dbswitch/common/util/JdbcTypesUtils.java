package cn.iocoder.yudao.framework.dbswitch.common.util;

import java.lang.reflect.Field;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/**
 * JDBC的数据类型相关工具类
 *
 * @author jrl
 */
public final class JdbcTypesUtils {

    private static final Map<Integer, String> TYPE_NAMES = new HashMap<>();

    static {
        try {
            for (Field field : Types.class.getFields()) {
                TYPE_NAMES.put((Integer) field.get(null), field.getName());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve JDBC Types constants", ex);
        }
    }

    /**
     * 将JDBC的整型类型转换成文本类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return JDBC的文本类型
     */
    public static String resolveTypeName(int sqlType) {
        return TYPE_NAMES.get(sqlType);
    }

    /**
     * 判断是否为JDCB的浮点数类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isNumeric(int sqlType) {
        // 5
        return (Types.DECIMAL == sqlType || Types.DOUBLE == sqlType || Types.FLOAT == sqlType
                || Types.NUMERIC == sqlType || Types.REAL == sqlType);
    }

    /**
     * 判断是否为JDCB的整型类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isInteger(int sqlType) {
        // 5
        return (Types.BIT == sqlType || Types.BIGINT == sqlType || Types.INTEGER == sqlType
                || Types.SMALLINT == sqlType
                || Types.TINYINT == sqlType);
    }

    /**
     * 判断是否为JDCB的字符文本类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isString(int sqlType) {
        // 10
        return (Types.CHAR == sqlType || Types.NCHAR == sqlType || Types.VARCHAR == sqlType
                || Types.LONGVARCHAR == sqlType || Types.NVARCHAR == sqlType
                || Types.LONGNVARCHAR == sqlType
                || Types.CLOB == sqlType || Types.NCLOB == sqlType || Types.SQLXML == sqlType
                || Types.ROWID == sqlType);
    }

    /**
     * 判断是否为JDCB的时间类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isDateTime(int sqlType) {
        // 5
        return (Types.DATE == sqlType || Types.TIME == sqlType || Types.TIMESTAMP == sqlType
                || Types.TIME_WITH_TIMEZONE == sqlType || Types.TIMESTAMP_WITH_TIMEZONE == sqlType);
    }

    /**
     * 判断是否为JDCB的布尔类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isBoolean(int sqlType) {
        // 1
        return (Types.BOOLEAN == sqlType);
    }

    /**
     * 判断是否为JDCB的二进制类型
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isBinary(int sqlType) {
        // 4
        return (Types.BINARY == sqlType || Types.VARBINARY == sqlType || Types.BLOB == sqlType
                || Types.LONGVARBINARY == sqlType);
    }

    /**
     * 判断当前 JDBC 类型是否可按文本处理。
     */
    public static boolean isTextable(int sqlType) {
        return isNumeric(sqlType) || isString(sqlType) || isDateTime(sqlType) || isBoolean(sqlType);
    }

    /**
     * 判断该JDBC类型是否可以作为增量水位字段（整型或时间类型）
     *
     * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
     * @return true为是，否则为false
     */
    public static boolean isIncrement(int sqlType) {
        return (Types.BIT == sqlType
                || Types.BIGINT == sqlType
                || Types.INTEGER == sqlType
                || Types.SMALLINT == sqlType
                || Types.DATE == sqlType
                || Types.TIMESTAMP == sqlType
                || Types.TIME_WITH_TIMEZONE == sqlType
                || Types.TIMESTAMP_WITH_TIMEZONE == sqlType);
    }

    /**
     * 估算单个字段值占用的字节数（移植自 dbswitch 2.0.2）
     * <p>
     * 只做数量级估算用于批量写入的内存控制，避免使用反射遍历对象图（如 ehcache SizeOf）
     * 带来的每行高额 CPU 开销。常见类型直接按 Java 对象特征估算，未知类型退化为字符串长度
     *
     * @param jdbcType 字段的JDBC类型
     * @param value    字段值
     * @return 估算的字节数
     */
    public static long getObjectSize(int jdbcType, Object value) {
        if (null == value) {
            return 0;
        }
        if (value instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue array) {
            return array.estimatedBytes();
        }
        if (value instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue nativeValue) {
            return nativeValue.estimatedBytes();
        }
        if (value == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE) {
            return 1;
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).length;
        }
        if (value instanceof String) {
            return 2L * ((String) value).length();
        }
        if (value instanceof java.math.BigInteger integer) {
            return 16L + (integer.bitLength() + 7L) / 8;
        }
        if (value instanceof java.math.BigDecimal decimal) {
            // 即使未缩放值很小，负 scale 也可能表示展开后极大的整数。
            long digits = Math.max(decimal.precision(), (long) decimal.precision() - decimal.scale());
            return 32L + Math.multiplyExact(2L, digits);
        }
        if (value instanceof Number || value instanceof java.util.Date
                || value instanceof java.time.temporal.Temporal) {
            return 8;
        }
        if (value instanceof Boolean) {
            return 1;
        }
        // 估算绝不能读取或释放实际写入器仍持有的定位器。
        try {
            if (value instanceof java.sql.Blob blob) {
                return blob.length();
            }
            if (value instanceof java.sql.Clob clob) {
                return Math.multiplyExact(2L, clob.length());
            }
        } catch (java.sql.SQLException | ArithmeticException failure) {
            throw new IllegalArgumentException("Cannot estimate JDBC large object size");
        }
        String strValue = String.valueOf(value);
        return 2L * strValue.length();
    }

    /**
     * 估算一条记录占用的字节数（移植自 dbswitch 2.0.2）
     *
     * @param record    记录的字段值数组
     * @param jdbcTypes 各字段的JDBC类型数组
     * @return 估算的字节数
     */
    public static long getRecordSize(Object[] record, int[] jdbcTypes) {
        long bytes = 0;
        if (null != record && null != jdbcTypes && record.length == jdbcTypes.length) {
            for (int i = 0; i < record.length; ++i) {
                bytes += getObjectSize(jdbcTypes[i], record[i]);
            }
        }
        return bytes;
    }

    /**
     * 估算一条记录占用的字节数（无 JDBC 类型信息时按 Java 值类型估算）
     *
     * @param record 记录的字段值数组
     * @return 估算的字节数
     */
    public static long getRecordSize(Object[] record) {
        long bytes = 0;
        if (null != record) {
            for (Object value : record) {
                bytes += getObjectSize(Types.OTHER, value);
            }
        }
        return bytes;
    }

    // 其他类型如下：9个
    // JAVA_OBJECT
    // OTHER
    // NULL
    // DISTINCT
    // STRUCT
    // ARRAY
    // REF
    // DATALINK
    // REF_CURSOR

    /**
     * 构造函数私有化
     */
    private JdbcTypesUtils() {
    }

}
