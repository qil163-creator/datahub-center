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
 * 由部署保留的单个 Doris 数据库代次。固定绑定的 schema 是数据库；instance-id 是
 * 根据实际连接观察到的集群编号和数据库物理 DbId 生成的
 * 名称型 UUID（版本 8）。不向 Doris 写入任何内容。删除并重建数据库会改变 DbId；原地
 * DELETE/重载、保留期过期或保留相同编号的集群克隆无法观察：先停止
 * 并排空消费方，然后轮换外部保留的代次，再恢复消费。表和分区身份
 * 由每个游标计划单独绑定。
 */
public final class DorisSourceGeneration {

    /**
     * 初始化 {@code DorisSourceGeneration}。
     */
    private DorisSourceGeneration() {
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
            throw new SQLException("Doris database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署的固定绑定必须指向此连接实际读取的数据库。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String database,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(database) || !pin.instanceId()
                .equals(instance(connection, database, timeout))) {
            throw unsupported("Deployed Doris generation pin does not name this database");
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String instance(Connection connection, String database, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("Doris generation requires a bounded connection");
        }
        SourceGenerationPin.validateSchema(database);
        String cluster = cluster(connection, timeout);
        String dbId = null;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW PROC '/dbs'")) {
                while (rows.next()) {
                    String name = rows.getString("DbName");
                    if (!database.equals(name)) {
                        continue;
                    }
                    String id = rows.getString("DbId");
                    if (dbId != null || !validId(id)) {
                        throw unsupported("Doris database identity is ambiguous or unavailable");
                    }
                    dbId = id;
                }
            }
        }
        if (dbId == null) {
            throw unsupported("Doris database identity is absent or not visible");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\0", "DORIS_SOURCE_INSTANCE_V1",
                    cluster, dbId, database).getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            String hex = HexFormat.of().formatHex(digest, 0, 16);
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(
                    16, 20) + "-" + hex.substring(20);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /** 连接到的唯一健康 FE 的集群编号；版本前缀属于准入证据。 */
    private static String cluster(Connection connection, int timeout) throws SQLException {
        String cluster = null;
        boolean connected = false;
        int count = 0;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                while (rows.next()) {
                    String id = rows.getString("ClusterId"), version = rows.getString("Version");
                    if (++count > 64 || !validId(id) || cluster != null && !cluster.equals(id)
                            || version == null || !version.startsWith("doris-4.0.")) {
                        throw unsupported("Doris cluster identity/version is not verified");
                    }
                    cluster = id;
                    if ("Yes".equals(rows.getString("CurrentConnected"))) {
                        if (connected || !rows.getBoolean("Alive") || !rows.getBoolean("Join")) {
                            throw unsupported("Doris connected FE is not uniquely healthy");
                        }
                        connected = true;
                    }
                }
            }
        }
        if (!connected) {
            throw unsupported("Doris connected FE identity is unavailable");
        }
        return cluster;
    }

    /**
     * 校验当前标识符是否满足长度及字符限制。
     */
    private static boolean validId(String id) {
        return id != null && id.matches("[1-9][0-9]{0,18}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
