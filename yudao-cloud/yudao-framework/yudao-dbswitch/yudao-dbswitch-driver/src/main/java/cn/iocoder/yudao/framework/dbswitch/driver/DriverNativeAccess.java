package cn.iocoder.yudao.framework.dbswitch.driver;

import java.sql.Connection;
import java.sql.SQLException;

/** 内部适配器边界：在物理连接的运行时中查找厂商类型。 */
public final class DriverNativeAccess {

    /**
     * 初始化 {@code DriverNativeAccess}。
     */
    private DriverNativeAccess() {
    }

    public interface ConnectionAccess {

        /**
         * 解包原生。
         */
        Object unwrapNative(String typeName) throws SQLException;

        /**
         * 在当前驱动类加载器及受控线程上下文中调用原生接口。
         */
        default Object invokeNative(java.util.concurrent.Callable<?> operation,
                long timeoutMillis) throws SQLException {
            throw new java.sql.SQLFeatureNotSupportedException(
                    "Bounded native execution is unavailable for this connection");
        }

        /**
         * 登记或检查底层资源的实际终止状态。
         */
        default java.util.concurrent.CompletionStage<Void> physicalTermination() throws SQLException {
            throw new java.sql.SQLFeatureNotSupportedException("Physical termination observation is unavailable");
        }

        /** 发起受控物理关闭但不等待；未来完成才表示工作线程和连接均已退出。 */
        default java.util.concurrent.CompletionStage<Void> requestClose() throws SQLException {
            throw new java.sql.SQLFeatureNotSupportedException("Controlled asynchronous closure is unavailable");
        }
    }

    /** 仅通过上下文持有的物理连接执行。不回退到调用方的无界连接池。 */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(Connection connection, long timeoutMillis,
            java.util.concurrent.Callable<T> operation) throws SQLException {
        ConnectionAccess access;
        if (connection instanceof ConnectionAccess direct) {
            access = direct;
        } else if (connection.isWrapperFor(ConnectionAccess.class)) {
            access = connection.unwrap(ConnectionAccess.class);
        } else {
            throw new java.sql.SQLFeatureNotSupportedException("Native operations require a controlled driver runtime");
        }
        return (T) access.invokeNative(operation, timeoutMillis);
    }

    /**
     * 返回符合指定接口类型的底层对象。
     */
    public static Object unwrap(Connection connection, String typeName) throws SQLException {
        if (connection instanceof ConnectionAccess access) {
            return access.unwrapNative(typeName);
        }
        if (connection.isWrapperFor(ConnectionAccess.class)) {
            return connection.unwrap(ConnectionAccess.class).unwrapNative(typeName);
        }
        // 应用类路径连接可能早于运行时包装器存在，例如调用方持有的连接池。
        try {
            Class<?> type;
            try {
                type = Class.forName(typeName, false, connection.getClass().getClassLoader());
            } catch (ClassNotFoundException absentFromJdkProxy) {
                // 调用方持有的 JDK 代理可能使用平台类加载器。固定的连接始终
                // 走上方显式 ConnectionAccess 路径，绝不进入此回退分支。
                type = Class.forName(typeName, false, DriverNativeAccess.class.getClassLoader());
            }
            return connection.unwrap(type);
        } catch (ClassNotFoundException failure) {
            throw new SQLException("Native JDBC interface is unavailable in this connection runtime");
        }
    }

    /** 清理提交不依赖调用方的剩余等待预算，也不经过诊断网络任务队列。 */
    public static java.util.concurrent.CompletionStage<Void> requestClose(Connection connection) throws SQLException {
        if (connection instanceof ConnectionAccess direct) {
            return direct.requestClose();
        }
        if (connection.isWrapperFor(ConnectionAccess.class)) {
            return connection.unwrap(ConnectionAccess.class).requestClose();
        }
        throw new java.sql.SQLFeatureNotSupportedException("Asynchronous closure requires a controlled driver runtime");
    }
}
