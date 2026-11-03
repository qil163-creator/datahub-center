package cn.iocoder.yudao.framework.dbswitch.driver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeParseException;

/**
 * Influx 时间戳是绝对 UTC 时刻。Arrow Flight JDBC 18.1 在时间读取方法中
 * 会两次按 JVM 时区解释无时区服务器向量。
 * 其文本读取方法保留原值和纳秒。在受控连接边界规范化此产品的
 * 时间读取，包括迁移 getObject 路径。
 * Calendar 参数不能重新解释已经确定的绝对 UTC 时刻，也不会被修改。
 * Arrow 无符号 BIGINT 的对象读取方法也返回有符号 Long。其文本读取方法
 * 保留全部 64 位，但 isSigned 元数据对有符号和无符号向量均返回 false。
 * 精确读取所有 BIGINT 文本，能表示时保留 Long，否则使用 BigInteger。
 * 其他产品不经过这些转换。
 */
final class InfluxFlightJdbcAdapter {

    /**
     * 初始化 {@code InfluxFlightJdbcAdapter}。
     */
    private InfluxFlightJdbcAdapter() {
    }

    /**
     * 为当前底层对象创建受控包装或代理。
     */
    static Connection wrap(Connection source, ClassLoader loader) {
        return (Connection) proxy(new Class<?>[]{Connection.class, DriverNativeAccess.ConnectionAccess.class}, loader,
                (self, method, args) -> {
                    if (method.getDeclaringClass() == DriverNativeAccess.ConnectionAccess.class) {
                        if (method.getName().equals("physicalTermination") || method.getName().equals("invokeNative")
                                || method.getName().equals("requestClose")) {
                            return invoke(source, method, args);
                        }
                        if (method.getName().equals("unwrapNative") && Connection.class.getName().equals(args[0])) {
                            return self;
                        }
                        throw new SQLFeatureNotSupportedException(
                                "Native access cannot bypass the Influx timestamp contract");
                    }
                    Object result = invoke(source, method, args);
                    if (result instanceof Statement statement) {
                        return statement(statement, (Connection) self, loader);
                    }
                    if (result instanceof DatabaseMetaData metadata) {
                        return metadata(metadata, (Connection) self, loader);
                    }
                    return result;
                });
    }

    /**
     * 返回语句。
     */
    private static Statement statement(Statement source, Connection connection, ClassLoader loader) {
        Class<?> api = source instanceof CallableStatement ? CallableStatement.class
                : source instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) proxy(new Class<?>[]{api}, loader, (self, method, args) -> {
            if (method.getName().equals("getConnection")) {
                return connection;
            }
            Object result = invoke(source, method, args);
            if (result instanceof ResultSet rows) {
                return rows(rows, (Statement) self, connection, loader);
            }
            return result;
        });
    }

    /**
     * 返回元数据。
     */
    private static DatabaseMetaData metadata(DatabaseMetaData source, Connection connection, ClassLoader loader) {
        return (DatabaseMetaData) proxy(new Class<?>[]{DatabaseMetaData.class}, loader, (self, method, args) -> {
            if (method.getName().equals("getConnection")) {
                return connection;
            }
            Object result = invoke(source, method, args);
            if (result instanceof ResultSet rows) {
                return rows(rows, null, connection, loader);
            }
            return result;
        });
    }

    /**
     * 返回数据行。
     */
    private static ResultSet rows(ResultSet source, Statement statement, Connection connection, ClassLoader loader) {
        return (ResultSet) proxy(new Class<?>[]{ResultSet.class}, loader, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("getStatement")) {
                if (statement != null) {
                    return statement;
                }
                Statement original = source.getStatement();
                return original == null ? null : statement(original, connection, loader);
            }
            if ((name.equals("getObject") || name.equals("getTimestamp") || name.equals("getString")
                    || name.equals("getBigDecimal") || name.equals("getLong") || name.equals("getInt")
                    || name.equals("getShort") || name.equals("getByte"))
                    && args != null && args.length >= 1) {
                int column = args[0] instanceof Integer index ? index : source.findColumn((String) args[0]);
                int type = source.getMetaData().getColumnType(column);
                if ((type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE)
                        && (name.equals("getObject") || name.equals("getTimestamp") || name.equals("getString"))) {
                    Class<?> target = name.equals("getString") ? String.class : Timestamp.class;
                    if (name.equals("getObject") && args.length == 2 && args[1] instanceof Class<?> requested) {
                        target = requested;
                    }
                    return temporalValue(source, column, target);
                }
                if (type == Types.BIGINT && !name.equals("getTimestamp")) {
                    Class<?> target = switch (name) {
                        case "getString" -> String.class;
                        case "getBigDecimal" -> BigDecimal.class;
                        case "getLong" -> long.class;
                        case "getInt" -> int.class;
                        case "getShort" -> short.class;
                        case "getByte" -> byte.class;
                        default -> Object.class;
                    };
                    if (name.equals("getObject") && args.length == 2 && args[1] instanceof Class<?> requested) {
                        target = requested;
                    }
                    Object value = integerValue(source, column, target);
                    if (value instanceof BigDecimal decimal && name.equals("getBigDecimal") && args.length == 2) {
                        return decimal.setScale((Integer) args[1], java.math.RoundingMode.UNNECESSARY);
                    }
                    return value;
                }
            }
            return invoke(source, method, args);
        });
    }

    /**
     * 检查并返回当前配置中的整数值。
     */
    private static Object integerValue(ResultSet source, int column, Class<?> target) throws SQLException {
        String text = source.getString(column);
        if (text == null) {
            if (target == long.class) {
                return 0L;
            }
            if (target == int.class) {
                return 0;
            }
            if (target == short.class) {
                return (short) 0;
            }
            if (target == byte.class) {
                return (byte) 0;
            }
            return null;
        }
        final BigInteger value;
        try {
            if (text.isEmpty() || text.length() > 20 || !text.matches("-?[0-9]+")) {
                throw new NumberFormatException();
            }
            value = new BigInteger(text);
            if (value.compareTo(
                    BigInteger.valueOf(Long.MIN_VALUE)) < 0 || value.signum() >= 0 && value.bitLength() > 64) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException invalid) {
            throw new SQLException("Influx integer cannot be decoded exactly", "22003");
        }
        try {
            if (target == Object.class) {
                return value.bitLength() < 64 ? value.longValueExact() : value;
            }
            if (target == BigInteger.class) {
                return value;
            }
            if (target == BigDecimal.class) {
                return new BigDecimal(value);
            }
            if (target == String.class) {
                return text;
            }
            if (target == Long.class || target == long.class) {
                return value.longValueExact();
            }
            if (target == Integer.class || target == int.class) {
                return value.intValueExact();
            }
            if (target == Short.class || target == short.class) {
                return value.shortValueExact();
            }
            if (target == Byte.class || target == byte.class) {
                return value.byteValueExact();
            }
        } catch (ArithmeticException overflow) {
            throw new SQLException("Influx integer exceeds the requested exact type", "22003");
        }
        throw new SQLFeatureNotSupportedException("Influx integer target type is not supported");
    }

    /**
     * 保留委托对象的 wasNull 状态。
     */
    private static Object temporalValue(ResultSet source, int column, Class<?> target) throws SQLException {
        String text = source.getString(column);
        if (text == null) {
            return null; // 保留委托对象的 wasNull 状态。
        }
        final Instant instant;
        try {
            if (text.length() < 19 || text.length() > 40) {
                throw new DateTimeParseException("Invalid timestamp", "", 0);
            }
            String iso = text.charAt(10) == ' ' ? text.substring(0, 10) + 'T' + text.substring(11) : text;
            if (iso.endsWith("Z") || iso.lastIndexOf('+') > 10 || iso.lastIndexOf('-') > 10) {
                instant = OffsetDateTime.parse(iso).toInstant();
            } else {
                instant = LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException | IndexOutOfBoundsException failure) {
            throw new SQLException("Influx timestamp cannot be decoded without losing its UTC meaning", "22007");
        }
        if (target == String.class) {
            return text;
        }
        if (target == Timestamp.class || target == Object.class) {
            return Timestamp.from(instant);
        }
        if (target == Instant.class) {
            return instant;
        }
        if (target == OffsetDateTime.class) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (target == LocalDateTime.class) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        throw new SQLFeatureNotSupportedException("Influx timestamp target type is not supported");
    }

    /**
     * 创建当前接口使用的受控代理对象。
     */
    private static Object proxy(Class<?>[] apis, ClassLoader loader, Call call) {
        return Proxy.newProxyInstance(DriverNativeAccess.class.getClassLoader(), apis, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "InfluxUtcJdbc[" + apis[0].getSimpleName() + "]";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new IllegalStateException("Unsupported object operation");
                };
            }
            if (method.getName().equals("unwrap")) {
                if (((Class<?>) args[0]).isInstance(self)) {
                    return self;
                }
                throw new SQLFeatureNotSupportedException("Unwrap cannot bypass the Influx timestamp contract");
            }
            if (method.getName().equals("isWrapperFor")) {
                return ((Class<?>) args[0]).isInstance(self);
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                return call.invoke(self, method, args);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        });
    }

    /**
     * 调用当前受控运行时的目标方法并传播调用结果。
     */
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @FunctionalInterface
    private interface Call {

        /**
         * 调用当前受控运行时的目标方法并传播调用结果。
         */
        Object invoke(Object self, Method method, Object[] args) throws Throwable;
    }
}
