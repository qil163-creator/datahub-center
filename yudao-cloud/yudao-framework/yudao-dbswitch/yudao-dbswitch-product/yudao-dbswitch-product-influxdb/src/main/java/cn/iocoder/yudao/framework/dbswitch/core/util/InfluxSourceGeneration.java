package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 由部署保留的单个 InfluxDB3 Core 数据库代次。固定绑定的 schema 是数据库名；instance-id
 * 是根据受控原生会话观察到的服务器目录 UUID 和数据库名生成的
 * 名称型 UUID（版本 8）。不向 InfluxDB3 写入任何内容。在相同集群以相同名称删除并重建数据库，
 * 无法通过只读目录证据观察（与 TDengine 游标记录的边界相同）：先停止
 * 并排空消费方，然后轮换外部保留的代次，再恢复消费。每次运行时准入都拒绝
 * 已变化的服务器目录或缺失的数据库。
 */
public final class InfluxSourceGeneration {

    /**
     * 初始化 {@code InfluxSourceGeneration}。
     */
    private InfluxSourceGeneration() {
    }

    /** 管理员辅助方法：读取数据库的只读证明，并生成待部署的新代次。 */
    public static SourceGenerationPin initialize(DataSource source, Connection connection, String database,
            int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(database);
        return new SourceGenerationPin(database, instance(source, connection, database, timeout),
                UUID.randomUUID().toString());
    }

    /** 管理员辅助方法：调用方提供下一个 UUID，使重复轮换得到相同的固定绑定。 */
    public static SourceGenerationPin rotate(DataSource source, Connection connection, SourceGenerationPin expected,
            String generation, int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(source, connection, expected.schema(), timeout))) {
            throw new SQLException("InfluxDB3 database differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署的固定绑定必须指向此连接实际读取的数据库。 */
    public static void verify(DataSource source, Connection connection, SourceGenerationPin pin, String database,
            int timeout) throws SQLException {
        if (pin == null || !pin.schema().equals(database) || !pin.instanceId()
                .equals(instance(source, connection, database, timeout))) {
            throw unsupported("Deployed InfluxDB3 generation pin does not name this database");
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String instance(DataSource source, Connection connection, String database,
            int timeout) throws SQLException {
        if (source == null || connection == null || timeout < 1) {
            throw unsupported("InfluxDB3 generation requires a bounded controlled connection");
        }
        SourceGenerationPin.validateSchema(database);
        try {
            var session = InfluxNativeSession.open(source, connection, 16L * 1024 * 1024, timeout);
            if (!database.equals(session.database())) {
                throw unsupported("InfluxDB3 generation transport names a different database");
            }
            // 保留期探测是数据库存在性的只读证明：服务器会拒绝缺失的数据库。
            var probe = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("db", database)
                    .put("q", "SHOW RETENTION POLICIES").put("format", "json");
            var rows = session.json("POST", "/api/v3/query_influxql", probe);
            if (!rows.isArray() || rows.size() != 1 || !"autogen".equals(rows.get(0).path("name").asText())) {
                throw unsupported("InfluxDB3 database retention evidence is unavailable");
            }
            String cluster = session.clusterIdentity();
            UUID.fromString(cluster);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(String.join("\0", "INFLUX_SOURCE_INSTANCE_V1",
                                cluster, database).getBytes(StandardCharsets.UTF_8));
                digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
                digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
                String hex = HexFormat.of().formatHex(digest, 0, 16);
                return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12,
                        16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
            } catch (java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable");
            }
        } catch (SQLFeatureNotSupportedException unsupported) {
            throw unsupported;
        } catch (SQLException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw unsupported("InfluxDB3 generation evidence cannot be captured from this endpoint");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
