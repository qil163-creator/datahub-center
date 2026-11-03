package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 部署保留的已验证 OceanBase 租户源代际。MySQL 的 instance-id 是
 * 在实际连接上观测到的租户持久 server_uuid，Oracle 使用租户创建与切换标识；generation 是运维人员旋转、
 * 保留于数据库之外的 UUID。不向租户写入任何内容，运行时只读。恢复或克隆的
 * 租户若保留 server_uuid，此处无法检测：恢复、克隆、切换或 TRUNCATE 后重载
 * 仍要求停止并排空消费方，并部署新代际。每个游标计划
 * 分别绑定表和 tablet 标识。
 */
public final class OceanbaseSourceGeneration {

    /**
     * 初始化 {@code OceanbaseSourceGeneration}。
     */
    private OceanbaseSourceGeneration() {
    }

    /** 管理员辅助工具：只读验证租户，并生成待部署的新代际。 */
    public static SourceGenerationPin initialize(Connection connection, String schema,
            int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        return new SourceGenerationPin(schema, instance(connection, timeout), UUID.randomUUID().toString());
    }

    /** 管理员辅助工具：由调用方提供下一个 UUID，使重复旋转得到相同固定信息。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        if (!expected.instanceId().equals(instance(connection, timeout))) {
            throw new SQLException("OceanBase tenant differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署固定信息必须指向此连接实际观测到的租户。 */
    public static void verify(OceanbaseCursorMetadata.Tenant tenant, SourceGenerationPin pin) throws SQLException {
        if (tenant == null || pin == null || !pin.instanceId().equals(tenant.serverUuid())) {
            throw OceanbaseCursorMetadata.unsupported("Deployed OceanBase generation pin does not name this tenant");
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    private static String instance(Connection connection, int timeout) throws SQLException {
        if (connection == null || timeout < 1 || timeout > 120 || !connection.getAutoCommit()) {
            throw new SQLException("Administrator requires an idle owned connection and bounded timeout");
        }
        if (!cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils.isOceanBaseUseMysqlMode(connection)) {
            return cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseOracleReplayProfile.generationInstance(
                    connection, timeout);
        }
        OceanbaseCursorMetadata.requireSession(connection, timeout, false);
        OceanbaseCursorMetadata.requireIdle(connection, timeout);
        return OceanbaseCursorMetadata.tenant(connection, timeout).serverUuid();
    }
}
