package cn.iocoder.yudao.framework.dbswitch.product.oracle;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 共享 Oracle 语法的精确值读取器与 MERGE 执行器所使用的产品凭据。
 * 兼容 SQL 语法不能继承 Oracle 的服务端、存储或事务认证。 */
public interface OracleReplayProfile {

    OracleReplayProfile ORACLE = new OracleReplayProfile() {
    };

    /**
     * 返回产品名称。
     */
    default String productName() {
        return "Oracle";
    }

    /**
     * 准备会话。
     */
    default void prepareSession(Connection connection, int timeout, boolean writer) throws SQLException {
        OracleCursorMetadata.requireSession(connection, timeout, writer);
        OracleCursorMetadata.pinSession(connection, timeout);
        OracleCursorMetadata.requireBinaryComparison(connection, timeout);
    }

    /**
     * 返回当前比较。
     */
    default String comparison(Connection connection, int timeout) throws SQLException {
        return OracleCursorMetadata.requireBinaryComparison(connection, timeout);
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    default void lock(Connection connection, String schema, String table, int timeout,
            boolean writer) throws SQLException {
        if (!writer) {
            OracleCursorMetadata.lock(connection, schema, table, timeout);
        } else {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeout);
                statement.execute(
                        "LOCK TABLE " + OracleCursorMetadata.relation(schema, table) + " IN ROW EXCLUSIVE MODE");
            }
        }
    }

    /**
     * 读取 Oracle 目标表的键列及精确重放元数据。
     */
    default OracleCursorMetadata.Shape inspect(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        return OracleCursorMetadata.inspect(connection, schema, table, timeout);
    }

    /**
     * 返回身份信息。
     */
    default String identity(OracleCursorMetadata.Shape shape) {
        return shape.identity();
    }

    /**
     * 返回键集合。
     */
    default CursorKeyContract keys(OracleCursorMetadata.Shape shape, List<String> keys,
            String comparison) throws SQLException {
        return OracleCursorMetadata.keyContract(shape, keys, comparison);
    }

    /**
     * 检查当前资源代次是否与预期代次一致。
     */
    default void verifyGeneration(Connection connection, SourceGenerationPin pin, int timeout) throws SQLException {
        OracleSourceGeneration.verify(connection, pin, timeout);
    }

    /**
     * 校验目标端。
     */
    default void verifyTarget(Connection connection, OracleCursorMetadata.Shape shape, List<String> fields,
            List<String> keys, int timeout) throws SQLException {
        OracleCursorMetadata.requireSideEffects(connection, shape, fields, keys, timeout);
    }

    /** 可选事务标识；若产品配置返回该值，则要求各页之间保持不变。 */
    default String snapshotIdentity(Connection connection, int timeout) throws SQLException {
        return null;
    }

    /** 即使 MERGE 与原生值域共享 Oracle 语法，载荷传输方式仍可能不同。 */
    default String payloadParameter(OracleCursorMetadata.Column column) {
        return "?";
    }

    /**
     * 返回精确。
     */
    default Object payloadParameterValue(OracleCursorMetadata.Column column, Object exact) throws SQLException {
        return exact;
    }

    /**
     * 确定当前重放载荷使用的 JDBC 参数绑定类型。
     */
    default int payloadBindType(OracleCursorMetadata.Column column) throws SQLException {
        return OracleExactValues.bindType(column);
    }

    /**
     * 按当前产品的精确值域读取或转换字段值。
     */
    default Object exactValue(OracleCursorMetadata.Column column, Object value, boolean key) throws SQLException {
        return key ? OracleCursorMetadata.key(value, column) : OracleExactValues.write(column, value);
    }
}
