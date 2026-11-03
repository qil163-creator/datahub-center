package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalTime;

/** 为已验证 JDBC 4.2 TIME 读取方法能保留小数秒的产品显式启用。 */
public final class JdbcTimeValues {

    /**
     * 初始化 {@code JdbcTimeValues}。
     */
    private JdbcTimeValues() {
    }

    /**
     * 使用保留当前类型精确语义的读取路径。
     */
    public static StatementResultSet losslessRead(StatementResultSet resource) {
        return read(resource, false, false);
    }

    /** 为已验证的微秒时钟值域显式启用；无法表示的 24:00 载体绝不能变为有效值。 */
    public static StatementResultSet losslessMicrosecondTimeRead(StatementResultSet resource) {
        return read(resource, false, true);
    }

    /** 为已验证的 JDBC 文本协议 TIME 字节显式启用；部分驱动的 getString 会丢失小数秒。 */
    public static StatementResultSet losslessDurationRead(StatementResultSet resource) {
        return read(resource, true, false);
    }

    /**
     * 从 JDBC 结果集读取当前字段或数据行。
     */
    private static StatementResultSet read(StatementResultSet resource, boolean duration, boolean microseconds) {
        ResultSet delegate = resource.getResultset();
        ResultSet proxy = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (ignored, method, args) -> {
                    if (method.getName().equals("getObject") && args != null && (args.length == 1
                            || microseconds && args.length == 2 && args[1] == LocalTime.class)) {
                        int index = args[0] instanceof Integer number ? number : delegate.findColumn((String) args[0]);
                        if (delegate.getMetaData().getColumnType(index) == Types.TIME) {
                            if (duration) {
                                byte[] raw = delegate.getBytes(index);
                                if (raw != null && raw.length > 17) {
                                    throw new java.sql.SQLException("Native TIME duration exceeds its bound");
                                }
                                String value = raw == null ? null : new String(raw,
                                        java.nio.charset.StandardCharsets.US_ASCII);
                                if (value != null) {
                                    cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.timeDurationMicros(
                                            value);
                                }
                                return value;
                            }
                            LocalTime value = delegate.getObject(index, LocalTime.class);
                            // pgjdbc 在文本和二进制协议中均将原生 24:00:00 映射为 LocalTime.MAX。
                            // 实际微秒 TIME 不可能产生其 999999999 纳秒值。
                            if (microseconds && value != null && value.getNano() % 1000 != 0) {
                                throw new java.sql.SQLException(
                                        "Native TIME 24:00:00 or non-microsecond value has no exact clock carrier");
                            }
                            return value;
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        // 结果集、语句和连接仍由原始资源持有。
        resource.setResultset(proxy);
        return resource;
    }
}
