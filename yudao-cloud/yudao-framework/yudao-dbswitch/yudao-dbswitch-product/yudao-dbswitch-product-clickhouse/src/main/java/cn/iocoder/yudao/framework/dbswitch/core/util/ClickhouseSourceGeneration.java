package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 由部署保留的单个 ClickHouse Atomic 数据库代次。固定绑定的 schema 是数据库；instance-id
 * 是根据实际连接观察到的服务器 UUID 和数据库 UUID 生成的名称型 UUID（版本 8）。
 * 不向 ClickHouse 写入任何内容。删除并重建数据库会改变数据库 UUID；原地
 * TRUNCATE/重载、ALTER DELETE/UPDATE、保留两个 UUID 的恢复或集群克隆无法观察：先停止
 * 并排空消费方，然后轮换外部保留的代次，再恢复消费。表和数据片段身份由
 * 每个游标计划单独绑定。
 */
public final class ClickhouseSourceGeneration {

    /**
     * 初始化 {@code ClickhouseSourceGeneration}。
     */
    private ClickhouseSourceGeneration() {
    }

    /** 管理员辅助方法：读取数据库的只读证明，并生成待部署的新代次。 */
    public static SourceGenerationPin initialize(Connection connection, String database,
            int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(database);
        return new SourceGenerationPin(database, instance(connection, database, timeout), UUID.randomUUID().toString());
    }

    /** 管理员辅助方法：调用方提供下一个 UUID，使重复轮换得到相同的固定绑定。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(connection, expected.schema(), timeout))) {
            throw new SQLException("ClickHouse database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署的固定绑定必须指向此连接实际读取的数据库。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String database,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(database) || !pin.instanceId()
                .equals(instance(connection, database, timeout))) {
            throw unsupported("Deployed ClickHouse generation pin does not name this database");
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String instance(Connection connection, String database, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("ClickHouse generation requires a bounded connection");
        }
        SourceGenerationPin.validateSchema(database);
        String server = one(connection, "SELECT toString(serverUUID())", timeout);
        if (!server.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw unsupported("ClickHouse server identity is not verified");
        }
        String[] databaseRow = null;
        try (var statement = connection.prepareStatement(
                "SELECT toString(uuid), engine FROM system.databases WHERE name=? LIMIT 2")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse database is absent or not visible");
                }
                databaseRow = new String[]{rows.getString(1), rows.getString(2)};
                if (rows.next()) {
                    throw unsupported("ClickHouse database evidence is ambiguous");
                }
            }
        }
        if (!databaseRow[0].matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}") || !"Atomic".equals(
                databaseRow[1])) {
            throw unsupported("Only ClickHouse Atomic database generations are verified");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\0", "CLICKHOUSE_SOURCE_INSTANCE_V1",
                            server, databaseRow[0], database).getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            String hex = HexFormat.of().formatHex(digest, 0, 16);
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(
                    16, 20) + "-" + hex.substring(20);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static String one(Connection connection, String sql, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse generation evidence is absent or not visible");
                }
                String value = rows.getString(1);
                if (value == null || value.isBlank() || value.length() > 64 || rows.next()) {
                    throw unsupported("ClickHouse generation evidence is unavailable");
                }
                return value;
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
