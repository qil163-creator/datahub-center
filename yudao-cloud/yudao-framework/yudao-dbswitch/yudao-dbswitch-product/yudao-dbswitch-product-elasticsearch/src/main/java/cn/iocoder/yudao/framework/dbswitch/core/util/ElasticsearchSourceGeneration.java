package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 由部署保留的单个 Elasticsearch 集群源代际。固定信息的 schema 是索引名；其
 * instance-id 是由集群 UUID 和已验证服务器构建标识生成的名称型 UUID（版本 8），均
 * 通过有界原生传输观测。不向 Elasticsearch 写入任何内容，固定信息在重启后仍保留。
 * 删除并重建索引会改变其 UUID，由游标结构指纹拒绝，而不是由此
 * 固定信息拒绝；若快照恢复或集群克隆有意保留集群 UUID 和索引 UUID，
 * 则无法观测，恢复前必须在外部旋转代际。
 */
public final class ElasticsearchSourceGeneration {

    /**
     * 初始化 {@code ElasticsearchSourceGeneration}。
     */
    private ElasticsearchSourceGeneration() {
    }

    /** 管理员辅助工具：只读验证集群并生成待部署的新代际。 */
    public static SourceGenerationPin initialize(Connection connection, String index, int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(index);
        ElasticsearchNativeSession.index(index);
        return new SourceGenerationPin(index, instance(connection, timeout), UUID.randomUUID().toString());
    }

    /** 管理员辅助工具：由调用方提供下一个 UUID，使重复旋转得到相同固定信息。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(connection, timeout))) {
            throw new SQLException("Elasticsearch cluster differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署固定信息必须指向此连接实际读取的索引。 */
    public static void verify(Connection connection, SourceGenerationPin pin, String index,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(index) || !pin.instanceId().equals(instance(connection, timeout))) {
            throw unsupported("Deployed Elasticsearch generation pin does not name this index");
        }
    }

    /** 稳定的名称型标识：集群 UUID 加已验证的 8.x 服务器构建信息；不含凭据或运行时状态。 */
    public static String instance(Connection connection, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("Elasticsearch generation requires a bounded connection");
        }
        var session = ElasticsearchNativeSession.open(connection, 131072, timeout);
        var root = session.request("GET", "/", null).requireSuccess();
        String cluster = text(root.path("cluster_uuid"), "cluster_uuid");
        String version = text(root.path("version").path("number"), "version.number");
        String buildHash = text(root.path("version").path("build_hash"), "version.build_hash");
        String flavor = text(root.path("version").path("build_flavor"), "version.build_flavor");
        // 集群 ID 是 22 个字符的 base64url 标识符，不是规范 UUID。
        if (!cluster.matches("[A-Za-z0-9_-]{1,64}")
                || !version.matches("8\\.[0-9]+\\.[0-9]+.*") || !buildHash.matches("[0-9a-f]{40}")
                || !"default".equals(flavor)) {
            throw unsupported("Elasticsearch cluster identity is not verified");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\0", "ELASTICSEARCH_SOURCE_INSTANCE_V1",
                            cluster, version, buildHash, flavor).getBytes(StandardCharsets.UTF_8));
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
                .length() > 512) {
            throw unsupported("Elasticsearch generation evidence field " + name + " is unavailable");
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
