package cn.iocoder.yudao.framework.dbswitch.pgwriter.nativecopy;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

/** PostgreSQL 反射仅限此处，厂商实例不会强制转换到应用驱动命名空间。 */
public final class PostgresCopySession {

    /**
     * 初始化 {@code PostgresCopySession}。
     */
    private PostgresCopySession() {
    }

    /**
     * 打开COPY会话并准备后续访问。
     */
    public static CopySession open(Connection connection, String sql) throws SQLException {
        return prepare(connection).start(sql);
    }

    @FunctionalInterface
    public interface PreparedCopy {

        /**
         * 返回当前起点。
         */
        CopySession start(String sql) throws SQLException;
    }

    /** 调用方将协议标记为可能已开始前，先解析原生接口。 */
    public static PreparedCopy prepare(Connection connection) throws SQLException {
        return prepareNative(DriverNativeAccess.unwrap(connection, "org.postgresql.PGConnection"));
    }

    /** 兼容适配器，供已自主管理原生连接的旧版 pgwriter 调用方使用。 */
    public static CopySession openNative(Object connection, String sql) throws SQLException {
        return prepareNative(connection).start(sql);
    }

    /**
     * 准备原生。
     */
    private static PreparedCopy prepareNative(Object connection) throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("COPY connection is required");
        }
        ClassLoader loader = connection.getClass().getClassLoader();
        try {
            Class<?> pg = Class.forName("org.postgresql.PGConnection", false, loader);
            Class<?> managerType = Class.forName("org.postgresql.copy.CopyManager", false, loader);
            Class<?> copyType = Class.forName("org.postgresql.copy.CopyIn", false, loader);
            if (!pg.isInstance(connection)) {
                throw new SQLException("Connection does not implement its runtime PostgreSQL interface");
            }
            // 协议启动前解析全部方法。
            var methods = new CopyMethods(copyType);
            Object manager = invoke(loader, pg.getMethod("getCopyAPI"), connection);
            Method start = managerType.getMethod("copyIn", String.class);
            var started = new java.util.concurrent.atomic.AtomicBoolean();
            return sql -> {
                if (sql == null || sql.isBlank()) {
                    throw new IllegalArgumentException("COPY SQL is required");
                }
                if (!started.compareAndSet(false, true)) {
                    throw new IllegalStateException("COPY opener is already consumed");
                }
                Object operation = invoke(loader, start, manager, sql);
                if (!copyType.isInstance(operation)) {
                    throw new SQLException("COPY driver returned an invalid protocol handle");
                }
                return new ReflectedSession(loader, operation, methods);
            };
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new SQLException("PostgreSQL COPY interface is unavailable in this connection runtime");
        }
    }

    private record CopyMethods(Method write, Method flush, Method end, Method cancel, Method active, Method count) {

        /**
         * 使用给定参数初始化 {@code CopyMethods}。
         */
        CopyMethods(Class<?> type) throws NoSuchMethodException {
            this(type.getMethod("writeToCopy", byte[].class, int.class, int.class), type.getMethod("flushCopy"),
                    type.getMethod("endCopy"), type.getMethod("cancelCopy"), type.getMethod("isActive"),
                    type.getMethod("getHandledRowCount"));
        }
    }

    private record ReflectedSession(ClassLoader loader, Object operation, CopyMethods methods) implements CopySession {

        /**
         * 写入目标COPY。
         */
        @Override
        public void writeToCopy(byte[] bytes, int offset, int length) throws SQLException {
            invoke(loader, methods.write, operation, bytes, offset, length);
        }

        /**
         * 刷新COPY。
         */
        @Override
        public void flushCopy() throws SQLException {
            invoke(loader, methods.flush, operation);
        }

        /**
         * 结束当前 PostgreSQL COPY 会话并取得写入结果。
         */
        @Override
        public long endCopy() throws SQLException {
            return number(invoke(loader, methods.end, operation));
        }

        /**
         * 取消COPY。
         */
        @Override
        public void cancelCopy() throws SQLException {
            invoke(loader, methods.cancel, operation);
        }

        /**
         * 判断活跃。
         */
        @Override
        public boolean isActive() throws SQLException {
            Object value = invoke(loader, methods.active, operation);
            if (value instanceof Boolean active) {
                return active;
            }
            throw new SQLException("COPY driver returned an invalid state");
        }

        /**
         * 返回当前处理器累计处理的数据行数。
         */
        @Override
        public long getHandledRowCount() throws SQLException {
            return number(invoke(loader, methods.count, operation));
        }

        /**
         * 按当前值域约定解析或转换数值。
         */
        private static long number(Object value) throws SQLException {
            if (value instanceof Long number) {
                return number;
            }
            throw new SQLException("COPY driver returned an invalid acknowledgement");
        }
    }

    /**
     * 调用当前受控运行时的目标方法并传播调用结果。
     */
    private static Object invoke(ClassLoader loader, Method method, Object target,
            Object... arguments) throws SQLException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException reflected) {
            Throwable failure = reflected.getCause();
            if (failure instanceof InterruptedException) {
                thread.interrupt();
            }
            if (failure instanceof SQLException sql) {
                throw sql;
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw new SQLException("PostgreSQL COPY driver call failed");
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            throw new SQLException("PostgreSQL COPY driver contract is incompatible");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
