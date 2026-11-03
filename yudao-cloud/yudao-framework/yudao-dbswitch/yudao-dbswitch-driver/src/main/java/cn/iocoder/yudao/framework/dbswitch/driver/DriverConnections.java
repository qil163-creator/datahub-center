package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

/** 直连元数据连接与迁移连接池使用相同的已验证驱动边界。 */
public final class DriverConnections {

    /**
     * 初始化 {@code DriverConnections}。
     */
    private DriverConnections() {
    }

    /**
     * 根据当前驱动及连接配置建立数据库连接。
     */
    public static Connection open(ProductTypeEnum product, ConfiguredJdbcDrivers.Binding binding,
            DriverRuntimeRegistry runtimes, String jdbcUrl, String username, String password) throws SQLException {
        return open(product, binding, runtimes, jdbcUrl, username, password, ConnectionBudget.Role.METADATA);
    }

    /**
     * 根据当前驱动及连接配置建立数据库连接。
     */
    public static Connection open(ProductTypeEnum product, ConfiguredJdbcDrivers.Binding binding,
            DriverRuntimeRegistry runtimes, String jdbcUrl, String username, String password,
            ConnectionBudget.Role role) throws SQLException {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(runtimes, "runtimes");
        Objects.requireNonNull(role, "role");
        if (binding != null && binding.product() != product) {
            throw new IllegalArgumentException("Driver product does not match the operation product");
        }
        var parameters = new Properties();
        parameters.setProperty("remarksReporting", "true");
        parameters.setProperty("useInformationSchema", "true");
        if (product == ProductTypeEnum.ORACLE) {
            parameters.setProperty("oracle.jdbc.J2EE13Compliant", "true");
        }
        // Phoenix HTTP 认证使用 Avatica 专有属性，普通 user/password 不能代替。
        if (product == ProductTypeEnum.PHOENIX) {
            if (username != null) {
                parameters.setProperty("avatica_user", username);
            }
            if (password != null) {
                parameters.setProperty("avatica_password", password);
            }
        }
        if (product == ProductTypeEnum.INFLUXDB3) {
            parameters.setProperty("token", DriverPoolFactory.influxToken(jdbcUrl, username, password));
            username = null;
            password = null;
        }
        var lease = binding == null ? runtimes.acquireApplication(product, product.getDriveClassName(), role)
                : binding.acquire(role);
        var owner = new InvisibleDataSource(lease, jdbcUrl, username, password, parameters);
        final Connection connection;
        try {
            connection = owner.getConnection();
        } catch (SQLException | RuntimeException | Error failure) {
            owner.close();
            throw failure;
        }
        return (Connection) Proxy.newProxyInstance(DriverNativeAccess.ConnectionAccess.class.getClassLoader(),
                new Class<?>[]{Connection.class, DriverNativeAccess.ConnectionAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("unwrap") && ((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    if (method.getName().equals("isWrapperFor") && ((Class<?>) args[0]).isInstance(proxy)) {
                        return true;
                    }
                    try {
                        Object result = method.invoke(connection, args);
                        // 物理退出与数据源释放租约是不同阶段。不能返回物理 future 后
                        // 假设其兄弟回调已经执行；直连关闭凭证必须等待所属数据源排空。
                        return method.getName().equals("requestClose") ? owner.physicalTermination() : result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    } finally {
                        // 失败或异步 abort 若没有物理退出依据，就不能释放租约。
                        if (method.getName().equals("close") || method.getName().equals("abort")
                                || method.getName().equals("requestClose")) {
                            owner.close();
                        }
                    }
                });
    }
}
