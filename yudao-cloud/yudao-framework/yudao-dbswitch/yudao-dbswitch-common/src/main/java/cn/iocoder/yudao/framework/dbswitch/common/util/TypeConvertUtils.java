package cn.iocoder.yudao.framework.dbswitch.common.util;

/** 现有同步 API：数值保持精确，转换失败绝不会变成 SQL NULL。 */
public final class TypeConvertUtils {

    /**
     * 初始化 {@code TypeConvertUtils}。
     */
    private TypeConvertUtils() {
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
     * 按通用 JDBC 转换约定将值转换为字符串。
     */
    public static String castToString(Object value) {
        return JdbcValueConversions.toText(value);
    }

    /**
     * 按通用 JDBC 转换约定将值转换为字节数组。
     */
    public static byte[] castToByteArray(Object value) {
        return JdbcValueConversions.toBytes(value);
    }

    /**
     * 根据运行时 JDBC 值类型规范化当前值。
     */
    public static Object castByDetermine(Object value) {
        return JdbcValueConversions.normalize(value);
    }
}
