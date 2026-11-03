package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 已验证 Oracle Free 23.26.3 PDB 的部署保留源代次。固定记录的 instance-id
 * 是根据可观测 PDB 标识确定性生成的名称 UUID；标识包含经 USERENV
 * 与 SYS.STANDARD.SYS_CONTEXT 在实际连接上读取的 CON_NAME、CON_DBID 及 CON_ID。代次是由操作人员轮换、
 * 保留在数据库之外的 UUID。运行时仅读取，不向 PDB 写入任何内容。此 Free 构建中的 CON_GUID 报
 * ORA-02003，运行时仅有 SELECT 权限的主体无法读取 V$DATABASE/V$DATABASE_INCARNATION，
 * 因此无法观察 resetlogs 或实例代次：保持同一
 * DBID 的恢复或克隆无法在此检测。恢复、克隆、卸载再挂载、TRUNCATE 后重载及快照回退
 * 仍要求消费者停止并完全退出，执行隔离且部署新代次；与已恢复数据库一并复制的固定记录
 * 无法证明此次恢复。每份计划分别绑定关系及对象
 * 标识。
 */
public final class OracleSourceGeneration {

    /**
     * 初始化 {@code OracleSourceGeneration}。
     */
    private OracleSourceGeneration() {
    }

    /** 管理员辅助方法：只读 PDB 凭据及待部署的新代次。 */
    public static SourceGenerationPin initialize(Connection connection, String schema,
            int timeout) throws SQLException {
        SourceGenerationPin.validateSchema(schema);
        requireAdministrator(connection, timeout);
        return new SourceGenerationPin(schema, instance(connection, timeout), UUID.randomUUID().toString());
    }

    /** 管理员辅助工具：由调用方提供下一个 UUID，使重复旋转得到相同固定信息。 */
    public static SourceGenerationPin rotate(Connection connection, SourceGenerationPin expected, String generation,
            int timeout) throws SQLException {
        var next = new SourceGenerationPin(expected.schema(), expected.instanceId(), generation);
        if (next.equals(expected)) {
            throw new SQLException("Rotation requires a fresh generation UUID");
        }
        requireAdministrator(connection, timeout);
        if (!expected.instanceId().equals(instance(connection, timeout))) {
            throw new SQLException("Oracle PDB differs from the expected pin");
        }
        return next;
    }

    /** 运行时准入：部署的固定记录必须指向此连接实际观测到的 PDB。 */
    public static void verify(Connection connection, SourceGenerationPin pin, int timeout) throws SQLException {
        if (pin == null) {
            throw OracleCursorMetadata.unsupported("Deployed Oracle generation pin is required");
        }
        OracleCursorMetadata.requireSession(connection, timeout, false);
        if (!pin.instanceId().equals(instance(connection, timeout))) {
            throw OracleCursorMetadata.unsupported("Deployed Oracle generation pin does not name this PDB");
        }
    }

    /** CON_GUID 不可用，报 ORA-02003，且 resetlogs 或实例代次视图不可读；PDB DBID 三元组是可观测标识。 */
    static String instance(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT SYS.STANDARD.SYS_CONTEXT('USERENV','CON_NAME'),"
                    + "SYS.STANDARD.SYS_CONTEXT('USERENV','CON_DBID'),SYS.STANDARD.SYS_CONTEXT('USERENV','CON_ID') FROM dual")) {
                if (!rows.next()) {
                    throw OracleCursorMetadata.unsupported("Oracle PDB identity is unavailable");
                }
                String name = rows.getString(1), dbid = rows.getString(2), conId = rows.getString(3);
                if (name == null || !name.matches("[A-Z][A-Z0-9_$#]{0,127}") || dbid == null || !dbid.matches(
                        "[1-9][0-9]{0,19}")
                        || conId == null || !conId.matches("[1-9][0-9]{0,8}") || rows.next()) {
                    throw OracleCursorMetadata.unsupported("Oracle PDB identity is ambiguous");
                }
                return UUID.nameUUIDFromBytes(("ORACLE_PDB_GENERATION_V1\0" + name + "\0" + dbid + "\0" + conId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            }
        }
    }

    /**
     * 校验并取得管理员。
     */
    private static void requireAdministrator(Connection connection, int timeout) throws SQLException {
        if (connection == null || timeout < 1 || timeout > 120 || !connection.getAutoCommit()) {
            throw new SQLException("Administrator requires an idle owned connection and bounded timeout");
        }
        OracleCursorMetadata.requireSession(connection, timeout, false);
    }
}
