package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 部署保留的单个 MongoDB 数据库代际。固定信息的 schema 是数据库；其 instance-id 是
 * 由机器主机名（hostInfo）、已验证构建标识、传输边界及
 * 数据库名生成的名称型 UUID（版本 8）。不向 MongoDB 写入任何内容，固定信息在服务器重启后仍保留。删除并重建
 * 数据库会改变每个集合 UUID，由游标结构指纹拒绝，而不是此固定信息；
 * 若恢复时有意保留集合 UUID（mongorestore --preserveUUID），或在
 * 同一主机以相同构建重建服务器，则无法观测，恢复前必须在外部旋转代际。
 */
public final class MongoSourceGeneration {

    /**
     * 初始化 {@code MongoSourceGeneration}。
     */
    private MongoSourceGeneration() {
    }

    /** 管理员辅助工具：只读验证服务器和数据库，并生成待部署的新代际。 */
    public static SourceGenerationPin initialize(Connection connection, String database,
            int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(database);
        MongoNativeMetadata.identifier(database);
        return new SourceGenerationPin(database, instance(connection, database, timeout), UUID.randomUUID().toString());
    }

    /** 管理员辅助工具：由调用方提供下一个 UUID，使重复旋转得到相同固定信息。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(connection, expected.schema(), timeout))) {
            throw new SQLException("MongoDB server or database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署固定信息必须指向此连接实际读取的数据库。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String database,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(database) || !pin.instanceId()
                .equals(instance(connection, database, timeout))) {
            throw unsupported("Deployed MongoDB generation pin does not name this database");
        }
    }

    /** 稳定的名称型标识：机器主机、已验证构建标识、传输边界和数据库名。 */
    public static String instance(Connection connection, String database, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("MongoDB generation requires a bounded connection");
        }
        MongoNativeMetadata.identifier(database);
        var session = MongoNativeSession.open(connection, 131072, timeout);
        var hello = MongoReplies.success(session, session.command("admin",
                session.encode("{\"hello\":1,\"maxTimeMS\":" + (1000L * timeout) + "}")));
        long wire = MongoReplies.integer(hello.path("maxWireVersion")), bson = MongoReplies.integer(
                hello.path("maxBsonObjectSize"));
        if (!Boolean.TRUE.equals(hello.path("isWritablePrimary").asBoolean()) || wire < 1 || bson < 1024) {
            throw unsupported("MongoDB server identity is not verified");
        }
        var hostInfo = MongoReplies.success(session, session.command("admin",
                session.encode("{\"hostInfo\":1,\"maxTimeMS\":" + (1000L * timeout) + "}")));
        String host = text(hostInfo.path("system").path("hostname"), "hostname");
        var build = MongoReplies.success(session, session.command("admin",
                session.encode("{\"buildInfo\":1,\"maxTimeMS\":" + (1000L * timeout) + "}")));
        String version = text(build.path("version"), "version"), git = text(build.path("gitVersion"), "gitVersion");
        if (!"7.0.43".equals(version) || build.path("modules") == null || !build.path("modules").isEmpty()
                || !host.matches("[A-Za-z0-9._-]{1,253}") || !git.matches("[0-9a-f]{40}")) {
            throw unsupported("MongoDB server identity is not verified");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\0", "MONGODB_SOURCE_INSTANCE_V1",
                            host, version, git, Long.toString(wire), Long.toString(bson), database)
                    .getBytes(StandardCharsets.UTF_8));
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
     * 按当前值域约定解析或转换文本。
     */
    private static String text(com.fasterxml.jackson.databind.JsonNode node, String name) throws SQLException {
        if (node == null || !node.isTextual() || node.textValue().indexOf('\0') >= 0 || node.textValue()
                .length() > 1024) {
            throw unsupported("MongoDB generation evidence field " + name + " is unavailable");
        }
        return node.textValue();
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
