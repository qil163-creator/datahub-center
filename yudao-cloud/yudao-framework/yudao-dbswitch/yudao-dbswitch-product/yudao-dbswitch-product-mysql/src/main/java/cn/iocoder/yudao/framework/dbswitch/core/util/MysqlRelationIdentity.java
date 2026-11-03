package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/**
 * MySQL 8 InnoDB 普通表身份：server UUID、有效账号、实际库/表名、原生 TABLE_ID。
 * 不使用 CREATE_TIME、表结构或行数替代对象世代。不修改连接属性或接管事务。
 * 当前未证明分区/视图/临时表及 InnoDB 文件名编码，明确拒绝，不冒充全产品兼容。
 */
public final class MysqlRelationIdentity {

    /**
     * 初始化 {@code MysqlRelationIdentity}。
     */
    private MysqlRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("MySQL connection and relation reference are required");
        }
        var metadata = connection.getMetaData();
        if (!"MySQL".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 8) {
            throw unsupported("Only native MySQL 8 relation identity has been implemented");
        }
        String serverUuid;
        String role;
        String defaultSchema;
        int lowerCase;
        try (var statement = connection.prepareStatement(
                "SELECT @@server_uuid, CURRENT_USER(), DATABASE(), @@lower_case_table_names")) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("MySQL session identity is unavailable");
                }
                serverUuid = rows.getString(1);
                role = rows.getString(2);
                defaultSchema = rows.getString(3);
                lowerCase = rows.getInt(4);
                if (serverUuid == null || serverUuid.isBlank() || role == null || role.isBlank()
                        || lowerCase < 0 || lowerCase > 2 || rows.next()) {
                    throw unsupported("MySQL session identity cannot be verified");
                }
            }
        }
        var relation = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.MYSQL, reference, defaultSchema);
        String schema = relation.getSchemaName();
        String table = relation.getTableName();
        // INNODB_TABLES.NAME 使用文件名编码，而非 SQL 标识符引用，绝不猜测其映射。
        if (!schema.matches("[A-Za-z0-9_]+") || !table.matches("[A-Za-z0-9_]+")) {
            throw unsupported("InnoDB encoded relation names have not been verified");
        }
        String qualified = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.MYSQL, schema, table);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + qualified)) {
                if (!rows.next()) {
                    throw new SQLException("MySQL relation cannot be resolved");
                }
                String ddl = rows.getString(2);
                // 会话临时表遮蔽永久表时，INFORMATION_SCHEMA 仍能看到永久表。
                if (ddl == null || !ddl.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE ")) {
                    throw unsupported("Temporary tables and views cannot provide stable relation identity");
                }
                if (rows.next()) {
                    throw new SQLException("MySQL relation identity is ambiguous");
                }
            }
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1142 && "42000".equals(ex.getSQLState())) {
                // 仅列级 SELECT 权限可支持质量计数，但不允许 SHOW CREATE TABLE。
                throw unsupported("MySQL relation definition privileges are insufficient");
            }
            throw ex;
        }
        if (lowerCase != 0) {
            schema = schema.toLowerCase(Locale.ROOT);
            table = table.toLowerCase(Locale.ROOT);
        }
        String schemaColumn = lowerCase == 0 ? "TABLE_SCHEMA" : "LOWER(TABLE_SCHEMA)";
        String tableColumn = lowerCase == 0 ? "TABLE_NAME" : "LOWER(TABLE_NAME)";
        try (var statement = connection.prepareStatement("SELECT TABLE_TYPE, ENGINE FROM information_schema.TABLES"
                + " WHERE BINARY " + schemaColumn + " = BINARY ? AND BINARY " + tableColumn + " = BINARY ?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("MySQL relation cannot be resolved");
                }
                if (!"BASE TABLE".equals(rows.getString(1)) || !"InnoDB".equalsIgnoreCase(rows.getString(2))) {
                    throw unsupported("Only InnoDB base tables provide this relation identity");
                }
                if (rows.next()) {
                    throw new SQLException("MySQL relation identity is ambiguous");
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_ID FROM information_schema.INNODB_TABLES WHERE BINARY NAME = BINARY ?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, schema + "/" + table);
            try (var rows = statement.executeQuery()) {
                // 分区表为每个分区设置字典名，而非使用此单一关系编号。
                if (!rows.next()) {
                    throw unsupported("A single InnoDB relation identity is unavailable");
                }
                String tableId = rows.getString(1);
                if (tableId == null || !tableId.matches("[0-9]+") || rows.next()) {
                    throw new SQLException("MySQL relation identity is ambiguous");
                }
                return DigestUtil.sha256Hex(String.join("\0", "MYSQL_INNODB_V1", serverUuid, role,
                        schema, table, Integer.toString(lowerCase), tableId));
            }
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1227 && "42000".equals(ex.getSQLState())) {
                // 不自动授予权限；保留普通质量读取能力，但不声称已证明标识。
                throw unsupported("MySQL relation identity metadata privileges are insufficient");
            }
            throw ex;
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
