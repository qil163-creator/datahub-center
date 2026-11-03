package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 部署保留的单个 Hive 数据库代际。固定信息的 schema 是数据库；其 instance-id 是
 * 由实际连接观测到的服务器版本和数据库目录标识生成的
 * 名称型 UUID（版本 8）。不向 Hive 写入任何内容。删除数据库后在不同仓库路径重建
 * 会改变 instance-id；原地表重载或恢复到相同路径无法观测：应停止并
 * 排空消费方，在恢复前旋转外部保留的代际。
 */
public final class HiveSourceGeneration {

    /**
     * 初始化 {@code HiveSourceGeneration}。
     */
    private HiveSourceGeneration() {
    }

    /** 管理员辅助工具：只读验证数据库并生成待部署的新代际。 */
    public static SourceGenerationPin initialize(Connection connection, String schema,
            int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        return new SourceGenerationPin(schema, instance(connection, schema, timeout), UUID.randomUUID().toString());
    }

    /** 管理员辅助工具：由调用方提供下一个 UUID，使重复旋转得到相同固定信息。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(connection, expected.schema(), timeout))) {
            throw new SQLException("Hive database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署固定信息必须指向此连接实际读取的数据库。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String schema,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(schema) || !pin.instanceId()
                .equals(instance(connection, schema, timeout))) {
            throw unsupported("Deployed Hive generation pin does not name this database");
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String instance(Connection connection, String schema, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("Hive generation requires a bounded connection");
        }
        SourceGenerationPin.validateSchema(schema);
        HiveCursorMetadata.product(connection);
        String version = connection.getMetaData().getDatabaseProductVersion();
        String[] row;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("DESCRIBE DATABASE " + schema)) {
                if (!rows.next()) {
                    throw unsupported("Hive database evidence is absent");
                }
                int columns = rows.getMetaData().getColumnCount();
                if (columns < 4) {
                    throw unsupported("Hive database evidence shape is not supported");
                }
                row = new String[columns];
                for (int i = 0; i < columns; i++) {
                    row[i] = rows.getString(i + 1);
                }
                if (rows.next()) {
                    throw unsupported("Hive database evidence is ambiguous");
                }
            }
        }
        if (row[0] == null || !row[0].strip()
                .equals(schema) || row[2] == null || row[2].isBlank() || row[2].length() > 2048
                || row[3] == null || row[3].isBlank() || row[3].length() > 2048) {
            throw unsupported("Hive database directory identity is not verified");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\0", "HIVE_SOURCE_INSTANCE_V1",
                    version, schema, row[2].strip(), row[3].strip()).getBytes(StandardCharsets.UTF_8));
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
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
