package cn.iocoder.yudao.framework.dbswitch.common.entity;

import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/** 物理数据源，包含私有 Properties 快照及自有驱动租约。 */
public final class InvisibleDataSource implements DataSource, AutoCloseable {

    private final DriverRuntimeRegistry.Lease lease;

    private final String jdbcUrl;

    private final Properties properties;

    private final Set<Connection> physicalConnections = Collections.newSetFromMap(new IdentityHashMap<>());

    private int creating;

    private boolean closing;

    private boolean leaseReleased;

    private final java.util.concurrent.CompletableFuture<Void> physicalTermination = new java.util.concurrent.CompletableFuture<>();

    /**
     * 校验构造参数并初始化 {@code InvisibleDataSource}。
     */
    public InvisibleDataSource(DriverRuntimeRegistry.Lease lease, String jdbcUrl, String username,
            String password, Properties properties) {
        this.lease = Objects.requireNonNull(lease);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
        this.properties = snapshot(properties);
        if (username != null) {
            this.properties.setProperty("user", username);
        }
        if (password != null) {
            this.properties.setProperty("password", password);
        }
    }

    /**
     * 返回当前驱动运行时使用的类加载器。
     */
    public ClassLoader getClassLoader() {
        return lease.classLoader();
    }

    /**
     * 返回JDBC 连接地址。
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 返回驱动类名。
     */
    public String getDriverClassName() {
        return lease.driverClassName();
    }

    /**
     * 返回属性配置。
     */
    public Properties getProperties() {
        return snapshot(properties);
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public String fingerprint() {
        return lease.fingerprint();
    }

    /**
     * 返回快照。
     */
    public static Properties snapshot(Properties original) {
        Objects.requireNonNull(original, "JDBC properties are required");
        var copy = new Properties();
        for (String name : original.stringPropertyNames()) {
            copy.setProperty(name, original.getProperty(name));
        }
        for (var entry : original.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("JDBC properties must contain immutable string keys and values");
            }
        }
        return copy;
    }

    /**
     * 根据当前驱动配置获取数据库连接。
     */
    @Override
    public Connection getConnection() throws SQLException {
        return connect(snapshot(properties));
    }

    /**
     * 根据当前驱动配置获取数据库连接。
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        var parameters = snapshot(properties);
        if (username != null) {
            parameters.setProperty("user", username);
            if (parameters.containsKey("username")) {
                parameters.setProperty("username", username);
            }
        }
        if (password != null) {
            parameters.setProperty("password", password);
        }
        return connect(parameters);
    }

    /**
     * 根据当前驱动及连接配置建立数据库连接。
     */
    private Connection connect(Properties parameters) throws SQLException {
        synchronized (this) {
            if (closing) {
                throw new SQLException("Driver datasource is draining");
            }
            creating++;
        }
        try {
            Connection physical = lease.connect(jdbcUrl, parameters);
            Connection proxy = wrap(physical);
            boolean discard;
            synchronized (this) {
                physicalConnections.add(physical);
                discard = closing;
            }
            if (physical instanceof DriverNativeAccess.ConnectionAccess access) {
                try {
                    access.physicalTermination().thenRun(() -> {
                        synchronized (InvisibleDataSource.this) {
                            physicalConnections.remove(physical);
                            releaseIfExited();
                        }
                    });
                } catch (SQLFeatureNotSupportedException unsupported) {
                    // 调用方持有的旧包装器保留下方同步关闭观测。
                }
            }
            if (discard) {
                proxy.close();
                throw new SQLException("Connection completed after its datasource began draining");
            }
            return proxy;
        } finally {
            synchronized (this) {
                creating--;
                releaseIfExited();
            }
        }
    }

    /**
     * 为当前底层对象创建受控包装或代理。
     */
    private Connection wrap(Connection physical) {
        return (Connection) Proxy.newProxyInstance(DriverNativeAccess.ConnectionAccess.class.getClassLoader(),
                new Class<?>[]{Connection.class, DriverNativeAccess.ConnectionAccess.class}, (proxy, method, args) -> {
                    ClassLoader previous = Thread.currentThread().getContextClassLoader();
                    try {
                        Thread.currentThread().setContextClassLoader(lease.classLoader());
                        if (method.getDeclaringClass() == DriverNativeAccess.ConnectionAccess.class) {
                            if (physical instanceof DriverNativeAccess.ConnectionAccess access) {
                                if (method.getName().equals("invokeNative")) {
                                    return access.invokeNative((java.util.concurrent.Callable<?>) args[0],
                                            (long) args[1]);
                                }
                                if (method.getName().equals("physicalTermination")) {
                                    return access.physicalTermination();
                                }
                                if (method.getName().equals("requestClose")) {
                                    return access.requestClose();
                                }
                                return access.unwrapNative((String) args[0]);
                            }
                            if (!method.getName().equals("unwrapNative")) {
                                throw new SQLFeatureNotSupportedException("Controlled native execution is unavailable");
                            }
                            Class<?> type = Class.forName((String) args[0], false, lease.classLoader());
                            return type.isInstance(physical) ? physical : physical.unwrap(type);
                        }
                        if (method.getName().equals("unwrap") && ((Class<?>) args[0]).isInstance(proxy)) {
                            return proxy;
                        }
                        if (method.getName().equals("isWrapperFor") && ((Class<?>) args[0]).isInstance(proxy)) {
                            return true;
                        }
                        try {
                            return method.invoke(physical, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        } finally {
                            if (method.getName().equals("close") || method.getName().equals("abort")) {
                                try {
                                    if (physical.isClosed()) {
                                        synchronized (InvisibleDataSource.this) {
                                            physicalConnections.remove(physical);
                                            releaseIfExited();
                                        }
                                    }
                                } catch (SQLException ignored) { /* 没有物理退出依据：保留租约。 */ }
                            }
                        }
                    } catch (ClassNotFoundException failure) {
                        throw new SQLException("Native JDBC interface is unavailable in this driver runtime");
                    } finally {
                        Thread.currentThread().setContextClassLoader(previous);
                    }
                });
    }

    /** 仅由连接池所有者在 Hikari.close() 后调用；连接池仍接受任务时绝不调用。 */
    @Override
    public synchronized void close() {
        closing = true;
        if (creating != 0 || !physicalConnections.isEmpty()) {
            lease.markDraining();
        }
        releaseIfExited();
    }

    /**
     * 在原生调用退出后释放其占用的资源预算。
     */
    private void releaseIfExited() {
        if (closing && creating == 0 && physicalConnections.isEmpty() && !leaseReleased) {
            lease.close();
            leaseReleased = true;
            physicalTermination.complete(null);
        }
    }

    /**
     * 判断是否具有退出。
     */
    public synchronized boolean hasExited() {
        return closing && leaseReleased;
    }

    /** 只有排空完成、所有正在创建的连接结束，且每个跟踪到的物理资源均退出后才完成。
     * 调用方无法通过返回的阶段完成或取消本组件持有的信号。 */
    public java.util.concurrent.CompletionStage<Void> physicalTermination() {
        return physicalTermination.minimalCompletionStage();
    }

    /**
     * 返回符合指定接口类型的底层对象。
     */
    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLFeatureNotSupportedException();
    }

    /**
     * 检查当前包装对象是否支持指定接口。
     */
    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    /**
     * 拒绝当前实现未支持的 {@code getLogWriter} 操作。
     */
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    /**
     * 拒绝当前实现未支持的 {@code setLogWriter} 操作。
     */
    @Override
    public void setLogWriter(PrintWriter writer) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    /**
     * 拒绝当前实现未支持的 {@code setLoginTimeout} 操作。
     */
    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        if (seconds != 0) {
            throw new SQLFeatureNotSupportedException(
                    "Configure a product-specific connection timeout; global DriverManager timeout is not used");
        }
    }

    /**
     * 返回当前数据源的登录超时秒数。
     */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    /**
     * 拒绝当前实现未支持的 {@code getParentLogger} 操作。
     */
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
