package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcPoolOptions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/** 仅负责迁移 DTO 适配；物理驱动和连接池归属由驱动模块管理。 */
public final class DataSourceUtils {

    /**
     * 初始化 {@code DataSourceUtils}。
     */
    private DataSourceUtils() {
    }

    /**
     * 按源端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties) {
        return createSourceDataSource(properties, null, null);
    }

    /**
     * 按源端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties,
            ThreadFactory threads) {
        return createSourceDataSource(properties, threads, null);
    }

    /**
     * 按源端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer) {
        return createSourceDataSource(properties, threads, observer, null);
    }

    /**
     * 按源端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver) {
        return createSourceDataSource(properties, threads, observer, driver, new DriverRuntimeRegistry());
    }

    /**
     * 按源端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver, DriverRuntimeRegistry runtimes) {
        return createSourceDataSource(properties, threads, observer, driver, runtimes, DbSwitchOptions.defaults());
    }

    /**
     * 按源端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver, DriverRuntimeRegistry runtimes,
            DbSwitchOptions defaults) {
        return createSourceDataSource(properties, threads, observer, driver, runtimes, defaults, null);
    }

    /** 只有自主管理新建连接池的调用方，才可从总操作预算中预留连接获取时间。 */
    public static HikariDataSource createSourceDataSource(SourceDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver, DriverRuntimeRegistry runtimes,
            DbSwitchOptions defaults, Long operationBudgetMillis) {
        rejectUnverifiedPath(properties.getDriverPath(), driver);
        var parameters = new Properties();
        // GBase 8a 基于 Connector/J 5.1 的驱动在固定设置
        // remarksReporting=true 时会中止连接建立（已在 8.6.2.43-R7-free 验证）；它只通过
        // information_schema 读取元数据。
        if (properties.getSourceProductType() != ProductTypeEnum.GBASE8A) {
            parameters.setProperty("remarksReporting", "true");
        }
        parameters.setProperty("useInformationSchema", "true");
        return DriverPoolFactory.create(options(properties.getSourceProductType(), normalizedUrl(properties.getUrl()),
                properties.getDriverClassName(), properties.getUsername(), properties.getPassword(), parameters,
                properties.getPoolSize(), properties.getMaxLifeTime(), properties.getConnectionTimeout(),
                null, threads, observer, defaults, operationBudgetMillis), driver, runtimes);
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties) {
        return createTargetDataSource(properties, null, null);
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties,
            ThreadFactory threads) {
        return createTargetDataSource(properties, threads, null);
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer) {
        return createTargetDataSource(properties, threads, observer, null);
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver) {
        return createTargetDataSource(properties, threads, observer, driver, new DriverRuntimeRegistry());
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver, DriverRuntimeRegistry runtimes) {
        return createTargetDataSource(properties, threads, observer, driver, runtimes, DbSwitchOptions.defaults());
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver, DriverRuntimeRegistry runtimes,
            DbSwitchOptions defaults) {
        return createTargetDataSource(properties, threads, observer, driver, runtimes, defaults, null);
    }

    /**
     * 按目标端配置创建数据源及其连接资源。
     */
    public static HikariDataSource createTargetDataSource(TargetDataSourceProperties properties, ThreadFactory threads,
            Consumer<Connection> observer, ConfiguredJdbcDrivers.Binding driver, DriverRuntimeRegistry runtimes,
            DbSwitchOptions defaults, Long operationBudgetMillis) {
        rejectUnverifiedPath(properties.getDriverPath(), driver);
        ProductTypeEnum product = properties.getTargetProductType();
        if (product != null && product.isUnsupportedTargetSqlite(properties.getUrl())) {
            throw new IllegalArgumentException(
                    "SQLite resource connections are read-only and cannot be migration targets");
        }
        String url = normalizedUrl(properties.getUrl());
        String initialization = null;
        if (product == ProductTypeEnum.DORIS || product == ProductTypeEnum.STARROCKS) {
            url = normalizeMysqlProtocolAnalyticJdbcUrl(url);
            initialization = "set enable_insert_strict = true";
        } else if (product == ProductTypeEnum.GREENPLUM) {
            initialization = "set optimizer to 'off'";
        }
        return DriverPoolFactory.create(options(product, url, properties.getDriverClassName(), properties.getUsername(),
                        properties.getPassword(), new Properties(), properties.getPoolSize(), properties.getMaxLifeTime(),
                        properties.getConnectionTimeout(), initialization, threads, observer, defaults, operationBudgetMillis),
                driver, runtimes);
    }

    /**
     * 返回配置选项。
     */
    private static JdbcPoolOptions options(ProductTypeEnum product, String url, String driverClass, String username,
            String password, Properties parameters, int size, long lifetime, long timeout, String initialization,
            ThreadFactory threads, Consumer<Connection> observer, DbSwitchOptions defaults,
            Long operationBudgetMillis) {
        long validation = defaults.poolValidationTimeoutMs();
        if (operationBudgetMillis != null) {
            if (operationBudgetMillis < 1000 || operationBudgetMillis > 120_000 || timeout < 0 || timeout > 0 && timeout < 250) {
                throw new IllegalArgumentException("Invalid owned connection acquisition budget");
            }
            long half = operationBudgetMillis / 2;
            timeout = Math.min(timeout == 0 ? Long.MAX_VALUE : timeout, half);
            validation = Math.min(validation, half);
        }
        return new JdbcPoolOptions(product, url, driverClass, username, password, parameters, size, lifetime, timeout,
                validation, defaults.poolIdleTimeoutMs(),
                defaults.poolLeakDetectionThresholdMs(), defaults.poolMinimumIdle(),
                initialization, threads, observer);
    }

    /**
     * 拒绝当前产品未经验证的操作路径。
     */
    private static void rejectUnverifiedPath(String path, ConfiguredJdbcDrivers.Binding binding) {
        if (binding == null && path != null && !path.isBlank()) {
            throw new IllegalArgumentException(
                    "driverPath requires a trusted verified driver bundle; directory loading is not supported");
        }
    }

    /** 不会向调用方 URL 追加降低 TLS 安全策略的设置或凭据。 */
    private static String normalizedUrl(String url) {
        return url == null ? "" : url.trim();
    }

    /**
     * 规范化使用 MySQL 协议的分析型数据库连接地址。
     */
    private static String normalizeMysqlProtocolAnalyticJdbcUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:mysql:")) {
            return url;
        }
        if (!lower.contains("rewritebatchedstatements=")) {
            url = append(url, "rewriteBatchedStatements=true");
        }
        if (!lower.contains("useserverprepstmts=")) {
            url = append(url, "useServerPrepStmts=false");
        }
        if (!lower.contains("cacheprepstmts=")) {
            url = append(url, "cachePrepStmts=true");
        }
        if (!lower.contains("prepstmtcachesize=")) {
            url = append(url, "prepStmtCacheSize=250");
        }
        if (!lower.contains("prepstmtcachesqllimit=")) {
            url = append(url, "prepStmtCacheSqlLimit=2048");
        }
        return url;
    }

    /**
     * 将给定内容追加到当前缓冲区，并更新累计大小。
     */
    private static String append(String url, String parameter) {
        return url + (url.endsWith("?") || url.endsWith("&") ? "" : url.contains("?") ? "&" : "?") + parameter;
    }

    /** 已有的观测器测试和内部调用方使用同一连接池管理方。 */
    private static HikariDataSource createPool(HikariConfig config, Consumer<Connection> observer) {
        return DriverPoolFactory.createConfigured(config, observer);
    }
}
