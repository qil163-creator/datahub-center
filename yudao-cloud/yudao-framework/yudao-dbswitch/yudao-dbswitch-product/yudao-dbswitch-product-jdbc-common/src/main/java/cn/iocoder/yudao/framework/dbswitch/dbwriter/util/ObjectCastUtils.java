package cn.iocoder.yudao.framework.dbswitch.dbwriter.util;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;

/** 现有写入器 API，底层由共享的严格 JDBC 转换实现支撑。 */
public final class ObjectCastUtils {

    /**
     * 初始化 {@code ObjectCastUtils}。
     */
    private ObjectCastUtils() {
    }

    /**
     * 将 JDBC 字符大对象读取为字符串。
     */
    public static String clob2Str(java.sql.Clob value) {
        return JdbcValueConversions.clobToString(value);
    }

    /**
     * 将 JDBC 二进制大对象读取为字节数组。
     */
    public static byte[] blob2Bytes(java.sql.Blob value) {
        return JdbcValueConversions.blobToBytes(value);
    }

    /**
     * 转换为字节数组。
     */
    public static byte[] toByteArray(Object value) {
        return JdbcValueConversions.toBytes(value);
    }

    /**
     * 按通用 JDBC 转换约定将值转换为字符串。
     */
    public static String castToString(Object value) {
        return JdbcValueConversions.toText(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为字节整数。
     */
    public static Byte castToByte(Object value) {
        return JdbcValueConversions.toByte(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为短整数。
     */
    public static Short castToShort(Object value) {
        return JdbcValueConversions.toShort(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为整数。
     */
    public static Integer castToInteger(Object value) {
        return JdbcValueConversions.toInteger(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为长整数。
     */
    public static Long castToLong(Object value) {
        return JdbcValueConversions.toLong(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为精确小数。
     */
    public static Number castToNumeric(Object value) {
        return JdbcValueConversions.toDecimal(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为单精度浮点数。
     */
    public static Float castToFloat(Object value) {
        return JdbcValueConversions.toFloat(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为双精度浮点数。
     */
    public static Double castToDouble(Object value) {
        return JdbcValueConversions.toDouble(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为本地日期。
     */
    public static java.time.LocalDate castToLocalDate(Object value) {
        return JdbcValueConversions.toDate(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为本地时间。
     */
    public static java.time.LocalTime castToLocalTime(Object value) {
        return JdbcValueConversions.toTime(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为本地日期时间。
     */
    public static java.time.LocalDateTime castToLocalDateTime(Object value) {
        return JdbcValueConversions.toDateTime(value);
    }

    /**
     * 按通用 JDBC 转换约定将值转换为字节数组。
     */
    public static byte[] castToByteArray(Object value) {
        return JdbcValueConversions.toBytes(value);
    }

    /**
     * 按 JDBC 值转换约定将输入转换为布尔值。
     */
    public static Boolean castToBoolean(Object value) {
        return JdbcValueConversions.toBoolean(value);
    }

    /**
     * 根据运行时 JDBC 值类型规范化当前值。
     */
    public static Object castByDetermine(Object value) {
        return JdbcValueConversions.normalize(value);
    }

    /**
     * 按当前原生值转换约定生成文本表示。
     */
    public static String objectToString(Object value) {
        return JdbcValueConversions.meaningfulString(value);
    }
}
