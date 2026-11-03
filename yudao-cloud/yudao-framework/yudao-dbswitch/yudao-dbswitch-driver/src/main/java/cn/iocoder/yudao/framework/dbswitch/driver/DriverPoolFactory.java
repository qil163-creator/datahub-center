package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** 源、目标和直连 JDBC 消费方共用单一物理连接及连接池构造路径。 */
public final class DriverPoolFactory {

    /**
     * 初始化 {@code DriverPoolFactory}。
     */
    private DriverPoolFactory() {
    }

    /**
     * 此处通过受控驱动运行时创建的连接池所提供的物理退出回执。应在
     * 关闭连接池前获取，再在 close() 后按调用方显式截止时间等待。Hikari 的
     * 关闭标记、失败操作、取消的 future 以及连接观察器均不能证明退出：
     * 回执还会等待正在创建的连接和延迟发生的物理关闭回调。
     * 正常完成只证明此连接池跟踪的资源已退出；不证明
     * 无关任务停止或数据库写入提交。未知或调用方持有的连接池没有
     * 此类依据，因此拒绝，而不是返回已完成的回执。
     */
    public static java.util.concurrent.CompletionStage<Void> physicalTermination(DataSource pool)
            throws java.sql.SQLFeatureNotSupportedException {
        if (pool instanceof OwnedHikariDataSource owned && owned.runtime != null) {
            return owned.runtime.physicalTermination();
        }
        throw new java.sql.SQLFeatureNotSupportedException(
                "Physical termination requires a DB Switch owned driver pool");
    }

    /**
     * 根据给定参数创建 {@code HikariDataSource} 实例。
     */
    public static HikariDataSource create(JdbcPoolOptions options, ConfiguredJdbcDrivers.Binding binding,
            DriverRuntimeRegistry runtimes) {
        return create(options, binding, runtimes, ConnectionBudget.Role.WORKLOAD);
    }

    /**
     * 根据给定参数创建 {@code HikariDataSource} 实例。
     */
    public static HikariDataSource create(JdbcPoolOptions options, ConfiguredJdbcDrivers.Binding binding,
            DriverRuntimeRegistry runtimes, ConnectionBudget.Role role) {
        if (binding != null && binding.product() != options.product()) {
            throw new IllegalArgumentException("Pinned JDBC driver product mismatch");
        }
        var config = new HikariConfig();
        config.setThreadFactory(options.threadFactory());
        config.setMaximumPoolSize(options.maximumPoolSize());
        config.setMaxLifetime(options.maxLifetimeMs());
        config.setConnectionTimeout(options.connectionTimeoutMs());
        config.setValidationTimeout(options.validationTimeoutMs());
        config.setIdleTimeout(options.idleTimeoutMs());
        config.setLeakDetectionThreshold(options.leakDetectionThresholdMs());
        config.setMinimumIdle(options.minimumIdle());
        config.setInitializationFailTimeout(-1);
        // Mongo 桥接器的 SQL ping 会初始化 shell/Graal 运行时。其 JDBC isValid
        // 只检查本地关闭状态；服务器存活检查需要显式且有界的原生 ping。
        if (options.product() != ProductTypeEnum.MONGODB) {
            config.setConnectionTestQuery(options.product().getTestSql());
        }
        config.setConnectionInitSql(options.connectionInitSql());
        var parameters = options.properties();
        String username = options.username();
        String password = options.password();
        if (options.product() == ProductTypeEnum.INFLUXDB3) {
            String token = influxToken(options.url(), username, password);
            parameters.setProperty("token", token);
            username = null;
            password = null;
            config.setPassword(token); // 现有原生写入器通过 Hikari 获取被掩码包装的凭据。
        } else {
            config.setUsername(username);
            config.setPassword(password);
        }
        // 经批准的原生产品适配器仍可访问 URL 和凭据。
        config.setJdbcUrl(options.url());
        DriverRuntimeRegistry.Lease lease = binding != null ? binding.acquire(role)
                : runtimes.acquireApplication(options.product(), options.driverClassName(), role);
        InvisibleDataSource physical = null;
        try {
            if (lease.driverClassName().toLowerCase(Locale.ROOT).contains("oracle")) {
                parameters.setProperty("oracle.jdbc.J2EE13Compliant", "true");
            }
            physical = new InvisibleDataSource(lease, options.url(), username, password, parameters);
            config.setDataSource(physical);
            config.validate();
            return createConfigured(config, options.observer());
        } catch (RuntimeException | Error failure) {
            if (physical == null) {
                lease.close();
            } else {
                physical.close();
            }
            throw failure;
        }
    }

    /** 保留现有物理连接观察器契约，包括外部传入的 DataSource。 */
    public static HikariDataSource createConfigured(HikariConfig config, Consumer<Connection> observer) {
        DataSource physical = config.getDataSource();
        if (physical == null) {
            throw new IllegalArgumentException("A resolved physical JDBC datasource is required");
        }
        if (observer != null) {
            config.setDataSource((DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        Object value;
                        try {
                            value = method.invoke(physical, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (value instanceof Connection connection && method.getName().equals("getConnection")) {
                            try {
                                observer.accept(connection);
                            } catch (RuntimeException | Error failure) {
                                try {
                                    connection.close();
                                } catch (SQLException ignored) {
                                }
                                throw failure;
                            }
                        }
                        return value;
                    }));
        }
        return new OwnedHikariDataSource(config, physical instanceof InvisibleDataSource runtime ? runtime : null);
    }

    private static final class OwnedHikariDataSource extends HikariDataSource {

        private final InvisibleDataSource runtime;

        /**
         * 初始化 {@code OwnedHikariDataSource}及其父类状态。
         */
        OwnedHikariDataSource(HikariConfig config, InvisibleDataSource runtime) {
            super(config);
            this.runtime = runtime;
        }

        /**
         * 关闭当前数据源或连接池持有的连接资源。
         */
        @Override
        public void close() {
            try {
                super.close();
            } finally {
                if (runtime != null) {
                    runtime.close();
                }
            }
        }
    }

    /**
     * 从当前连接配置读取 InfluxDB 访问令牌。
     */
    static String influxToken(String url, String username, String password) {
        validateFlightUrl(url);
        String token = password != null && !password.isBlank() ? password : username;
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("InfluxDB3 requires a credential token");
        }
        return token;
    }

    /**
     * 检查 Arrow Flight JDBC 地址是否符合允许的端点约定。
     */
    private static void validateFlightUrl(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:arrow-flight-sql://")) {
                throw new IllegalArgumentException();
            }
            var uri = java.net.URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            if (uri.getRawQuery() != null) {
                for (String item : uri.getRawQuery().split("&")) {
                    String key = java.net.URLDecoder.decode(item.split("=", 2)[0],
                            java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (Set.of("token", "password", "user", "username", "authorization").contains(key)) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("InfluxDB3 requires a Flight SQL URL without embedded credentials");
        }
    }
}
