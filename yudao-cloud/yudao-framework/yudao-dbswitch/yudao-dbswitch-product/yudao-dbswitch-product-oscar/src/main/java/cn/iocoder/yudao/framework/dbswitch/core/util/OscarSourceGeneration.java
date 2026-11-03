package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarReplayMetadata;

import java.sql.*;
import java.util.List;

/** 显式 ShenTong 管理员协议；配置的命名空间是现有物理 schema。 */
public final class OscarSourceGeneration {

    /**
     * 初始化 {@code OscarSourceGeneration}。
     */
    private OscarSourceGeneration() {
    }

    private static final ManagedJdbcSourceGeneration PROTOCOL = new ManagedJdbcSourceGeneration(
            new ManagedJdbcSourceGeneration.Backend() {
                /**
                 * 返回当前操作使用的数据库产品。
                 */
                public String product() {
                    return "OSCAR";
                }

                /**
                 * 核对连接实际产品与当前实现的产品要求。
                 */
                public void verifyProduct(Connection c) throws SQLException {
                    OscarReplayMetadata.product(c);
                }

                /**
                 * 生成模式与表组成的限定关系名。
                 */
                public String relation(String namespace, String table) {
                    return OscarReplayMetadata.relation(namespace, table);
                }

                /**
                 * 返回当前存在性。
                 */
                public boolean exists(Connection c, String namespace, String table, int timeout) throws SQLException {
                    try (var p = c.prepareStatement(
                            "SELECT c.OID FROM INFO_SCHEM.SYS_CLASS c JOIN INFO_SCHEM.SYS_NAMESPACE n ON n.OID=c.RELNAMESPACE WHERE n.NSPNAME=? AND c.RELNAME=?")) {
                        p.setQueryTimeout(timeout);
                        p.setString(1, namespace);
                        p.setString(2, table);
                        try (var r = p.executeQuery()) {
                            if (!r.next()) {
                                return false;
                            }
                            if (r.next()) {
                                throw new SQLException("Oscar generation installation is ambiguous");
                            }
                            return true;
                        }
                    }
                }

                /**
                 * 获取当前操作所需的数据库或资源锁。
                 */
                public void lock(Connection c, String relation, int timeout) throws SQLException {
                    OscarReplayMetadata.lock(c, relation, true, timeout);
                }

                /**
                 * 校验源代次存储表结构与当前产品声明一致。
                 */
                public void validate(Connection c, String namespace, String table, boolean head,
                        int timeout) throws SQLException {
                    var shape = OscarReplayMetadata.read(c, namespace, table, timeout);
                    var expected = head ? List.of("SINGLETON_ID:int:false", "PROTOCOL_VERSION:int:false",
                            "INSTANCE_ID:varchar(36):false", "GENERATION:varchar(36):false", "STATE:varchar(6):false")
                            : List.of("GENERATION:varchar(36):false", "INSTANCE_ID:varchar(36):false",
                            "PREVIOUS_GENERATION:varchar(36):true");
                    var actual = shape.columns().entrySet().stream()
                            .map(e -> e.getKey() + ":" + e.getValue().nativeType() + ":" + e.getValue().nullable())
                            .toList();
                    if (!actual.equals(expected) || !shape.schema().primaryKeys()
                            .equals(List.of(head ? "SINGLETON_ID" : "GENERATION"))) {
                        throw new SQLException("Oscar generation installation shape differs");
                    }
                }
            });

    /**
     * 初始化当前组件的运行资源和内部状态。
     */
    public static SourceGenerationPin initialize(Connection c, String namespace, int timeout) throws SQLException {
        return PROTOCOL.initialize(c, namespace, timeout);
    }

    /**
     * 校验当前资源身份与代次，阻止使用已经失效的读写状态。
     */
    public static void fence(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        PROTOCOL.fence(c, pin, timeout);
    }

    /**
     * 校验旧代次并切换当前资源的代次标记。
     */
    public static SourceGenerationPin rotate(Connection c, SourceGenerationPin pin, String next,
            int timeout) throws SQLException {
        return PROTOCOL.rotate(c, pin, next, timeout);
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    public static void verifyCurrent(Connection c, SourceGenerationPin pin, int timeout) throws SQLException {
        PROTOCOL.verifyCurrent(c, pin, timeout);
    }

    /**
     * 重新核对目标事实后删除当前恢复材料指定的对象。
     */
    public static void drop(Connection c, String namespace, int timeout) throws SQLException {
        PROTOCOL.drop(c, namespace, timeout);
    }
}
