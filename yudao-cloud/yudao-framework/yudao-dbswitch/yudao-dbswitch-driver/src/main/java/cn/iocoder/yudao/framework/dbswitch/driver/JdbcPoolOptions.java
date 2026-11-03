package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/** 仅包含 JDBC 的连接池输入。不含迁移、业务或 Spring 配置 DTO。 */
public record JdbcPoolOptions(ProductTypeEnum product, String url, String driverClassName,
                              String username, String password, Properties properties, int maximumPoolSize,
                              long maxLifetimeMs, long connectionTimeoutMs, long validationTimeoutMs,
                              long idleTimeoutMs, long leakDetectionThresholdMs, int minimumIdle,
                              String connectionInitSql, ThreadFactory threadFactory, Consumer<Connection> observer) {

    /**
     * 初始化 {@code JdbcPoolOptions}。
     */
    public JdbcPoolOptions {
        properties = InvisibleDataSource.snapshot(properties);
    }

    /**
     * 返回属性配置。
     */
    @Override
    public Properties properties() {
        return InvisibleDataSource.snapshot(properties);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "JdbcPoolOptions[product=" + product + ", maximumPoolSize=" + maximumPoolSize + "]";
    }
}
