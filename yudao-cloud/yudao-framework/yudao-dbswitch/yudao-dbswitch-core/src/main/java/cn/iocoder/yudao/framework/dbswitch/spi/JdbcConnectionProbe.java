package cn.iocoder.yudao.framework.dbswitch.spi;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/** 仅执行产品内置的最小语句；不接受来自调用方的校验 SQL。 */
public final class JdbcConnectionProbe {

    /**
     * 初始化 {@code JdbcConnectionProbe}。
     */
    private JdbcConnectionProbe() {
    }

    /**
     * 返回当前标准。
     */
    public static ConnectionProbe standard() {
        return (request, opener) -> {
            if (request.jdbcUrl() == null || !request.jdbcUrl().startsWith("jdbc:")) {
                throw new IllegalArgumentException("缺少合法 JDBC 地址");
            }
            try (var connection = opener.open()) {
                if (request.schema() != null && !request.schema().isBlank()) {
                    connection.setSchema(request.schema());
                }
                fixedSql(connection, request.product().getTestSql(), (int) request.timeout().toSeconds());
            }
        };
    }

    /** 仅由产品拥有者传入内置语句；连接的所有权仍属于调用方。 */
    public static void fixedSql(Connection connection, String sql, int timeoutSeconds) throws SQLException {
        if (sql == null || sql.isBlank() || sql.equals("/* ping */")) {
            throw new SQLFeatureNotSupportedException("产品未声明有效协议探测");
        }
        try (var statement = connection.createStatement()) {
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException ignored) {
            }
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw new SQLException("探测语句未返回结果");
                }
            }
        }
    }
}
