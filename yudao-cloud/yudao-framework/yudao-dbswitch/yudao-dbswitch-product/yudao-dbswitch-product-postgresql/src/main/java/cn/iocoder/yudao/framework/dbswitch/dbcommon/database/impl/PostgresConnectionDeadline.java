package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeUnit;

/** 阶段预算，包含有界的自有连接池获取、验证、JDBC 状态修改和关闭。 */
final class PostgresConnectionDeadline implements AutoCloseable {

    private final long started = System.nanoTime();

    private final int budgetMillis;

    private final DataSource source;

    private JdbcReadDeadline jdbc;

    /**
     * 校验构造参数并初始化 {@code PostgresConnectionDeadline}。
     */
    PostgresConnectionDeadline(DataSource source, int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive PostgreSQL phase budget required");
        }
        budgetMillis = Math.min(120, timeoutSeconds) * 1000;
        // Hikari 获取连接时可能同时验证借用的物理连接，两项限制都须预留。
        // 此处连接池配置不可变，只有负责迁移的工厂设置这些限制。
        if (!isBounded(source, timeoutSeconds)) {
            throw new IllegalArgumentException(
                    "A controlled owned pool with bounded acquisition and validation is required");
        }
        this.source = source;
    }

    /**
     * 判断有界。
     */
    static boolean isBounded(DataSource source, int timeoutSeconds) {
        int budgetMillis = Math.min(120, timeoutSeconds) * 1000;
        return source instanceof HikariDataSource pool && pool.getConnectionTimeout() >= 250 && pool.getValidationTimeout() >= 250
                && pool.getConnectionTimeout() <= budgetMillis - pool.getValidationTimeout()
                && pool.getInitializationFailTimeout() < 0 && !pool.isAllowPoolSuspension();
    }

    /**
     * 根据当前驱动及连接配置建立数据库连接。
     */
    Connection open() throws SQLException {
        Connection connection = source.getConnection();
        try {
            observe(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            throw failure;
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    void observe(Connection connection) throws SQLException {
        if (jdbc != null) {
            throw new IllegalStateException("A connection deadline cannot own multiple borrows");
        }
        long remaining = budgetMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (remaining <= 0) {
            throw new SQLTimeoutException("PostgreSQL phase expired before JDBC work");
        }
        jdbc = new JdbcReadDeadline(connection, (int) remaining);
    }

    /**
     * 检查当前状态是否满足当前操作的约定。
     */
    void check() throws SQLException {
        if (jdbc == null) {
            throw new IllegalStateException("Connection was not observed");
        }
        jdbc.check();
    }

    /**
     * 校验原生列报告的 JDBC 类型是否属于允许范围。
     */
    JdbcReadDeadline jdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("Connection was not observed");
        }
        return jdbc;
    }

    /**
     * 关闭 {@code PostgresConnectionDeadline} 当前持有的资源。
     */
    @Override
    public void close() {
        if (jdbc != null) {
            jdbc.close();
        }
    }
}
