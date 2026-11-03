package cn.iocoder.yudao.framework.dbswitch.common.util;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * OceanBase 兼容模式探测：仅接受精确的 MySQL 或 Oracle 模式，失败时不猜测方言。
 * Connection 重载不接管调用方连接/事务；DataSource 重载只关闭自身借用的连接。
 */
public final class OceanbaseUtils {

    /**
     * 初始化 {@code OceanbaseUtils}。
     */
    private OceanbaseUtils() {
    }

    /**
     * 检查当前 OceanBase 连接是否使用 MySQL 协议模式。
     */
    public static boolean isOceanBaseUseMysqlMode(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "OceanBase data source is required");
        try (Connection connection = dataSource.getConnection()) {
            return isOceanBaseUseMysqlMode(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("OceanBase compatibility mode cannot be verified", e);
        }
    }

    /**
     * 检查当前 OceanBase 连接是否使用 MySQL 协议模式。
     */
    public static boolean isOceanBaseUseMysqlMode(Connection connection) {
        Objects.requireNonNull(connection, "OceanBase connection is required");
        String sql = "show global variables where variable_name = 'ob_compatibility_mode'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String value = resultSet.getString(2);
                    if (resultSet.next()) {
                        throw new IllegalStateException("OceanBase compatibility mode is ambiguous");
                    }
                    if ("MYSQL".equalsIgnoreCase(value)) {
                        return true;
                    }
                    if ("ORACLE".equalsIgnoreCase(value)) {
                        return false;
                    }
                    throw new IllegalStateException("OceanBase compatibility mode is unsupported");
                }
                throw new IllegalStateException("OceanBase compatibility mode is unavailable");
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("OceanBase compatibility mode cannot be verified", sqlException);
        }
    }
}
