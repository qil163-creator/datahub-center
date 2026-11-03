package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 由部署保留的单个 TDengine 数据库代次。固定绑定的 schema 是数据库；instance-id 是
 * 根据集群编号、集群创建时间，以及数据库名和数据库创建时间生成的名称型 UUID（版本 8），这些信息均
 * 从实际 WebSocket 连接观察。不向 TDengine 写入任何内容。删除并重建数据库，
 * 或通过 taosdump 恢复到新数据库，会改变 instance-id。原地 DELETE/重载、保留期
 * 过期或保留相同编号的集群克隆无法观察：先停止并排空消费方，然后轮换
 * 外部保留的代次，再恢复消费。
 */
public final class TdengineSourceGeneration {

    /**
     * 初始化 {@code TdengineSourceGeneration}。
     */
    private TdengineSourceGeneration() {
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
            throw new SQLException("TDengine database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署的固定绑定必须指向此连接实际读取的数据库。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String database,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(database) || !pin.instanceId()
                .equals(instance(connection, database, timeout))) {
            throw unsupported("Deployed TDengine generation pin does not name this database");
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String instance(Connection connection, String database, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("TDengine generation requires a bounded connection");
        }
        SourceGenerationPin.validateSchema(database);
        TdengineJdbcSupport.requireValueDriver(connection);
        String[] cluster = one(connection,
                "SELECT CAST(id AS VARCHAR(64)),CAST(CAST(create_time AS BIGINT) AS VARCHAR(32)) "
                        + "FROM information_schema.ins_cluster LIMIT 2", 2, timeout);
        String[] created = one(connection,
                "SELECT CAST(CAST(create_time AS BIGINT) AS VARCHAR(32)),`precision`,`status` "
                        + "FROM information_schema.ins_databases WHERE name='" + database + "' LIMIT 2", 3, timeout);
        if (!cluster[0].matches("[1-9][0-9]{0,19}") || !cluster[1].matches("[1-9][0-9]{0,18}") || !created[0].matches(
                "[1-9][0-9]{0,18}")
                || !"ready".equals(created[2]) || !java.util.Set.of("ms", "us", "ns").contains(created[1])) {
            throw unsupported("TDengine cluster or database creation identity is not verified");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\0", "TDENGINE_SOURCE_INSTANCE_V1",
                    cluster[0], cluster[1], database, created[0]).getBytes(StandardCharsets.UTF_8));
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
    private static String[] one(Connection connection, String sql, int columns, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw unsupported("TDengine generation evidence is absent or not visible");
                }
                String[] values = new String[columns];
                for (int i = 0; i < columns; i++) {
                    values[i] = rows.getString(i + 1);
                    if (values[i] == null || values[i].isBlank() || values[i].length() > 64) {
                        throw unsupported("TDengine generation evidence is unavailable");
                    }
                }
                if (rows.next()) {
                    throw unsupported("TDengine generation evidence is ambiguous");
                }
                return values;
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
