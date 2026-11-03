package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/** 显式受管恢复或克隆轮换协议下，MariaDB 普通 InnoDB 对象的标识。 */
public final class MariaDbRelationIdentity {

    /**
     * 初始化 {@code MariaDbRelationIdentity}。
     */
    private MariaDbRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference,
            SourceGenerationPin pin) throws SQLException {
        if (connection == null || pin == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection, relation and managed source generation are required");
        }
        ConfiguredSourceGenerations.validateManagedConnection(connection);
        MariaDbSourceGeneration.readVerified(connection, pin);
        String principal;
        String role;
        String defaultSchema;
        int lowerCase;
        try (var query = connection.prepareStatement(
                "SELECT CURRENT_USER(), CURRENT_ROLE(), DATABASE(), @@lower_case_table_names")) {
            query.setQueryTimeout(5);
            query.setMaxRows(2);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("MariaDB session identity unavailable");
                }
                principal = rows.getString(1);
                role = rows.getString(2);
                defaultSchema = rows.getString(3);
                lowerCase = rows.getInt(4);
                if (principal == null || principal.isBlank() || rows.wasNull()
                        || lowerCase < 0 || lowerCase > 1 || rows.next()) {
                    throw unsupported("MariaDB session identity is not verified");
                }
                if (role == null) {
                    role = ""; // 未选择角色时，原生 CURRENT_ROLE() 为 NULL。
                }
            }
        }
        var relation = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.MARIADB, reference, defaultSchema);
        String schema = relation.getSchemaName(), table = relation.getTableName();
        if (!schema.matches("[A-Za-z0-9_]+") || !table.matches("[A-Za-z0-9_]+")) {
            throw unsupported("MariaDB encoded dictionary names are not verified");
        }
        String qualified = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.MARIADB, schema, table);
        try (var query = connection.createStatement()) {
            query.setQueryTimeout(5);
            query.setMaxRows(2);
            try (var rows = query.executeQuery("SHOW CREATE TABLE " + qualified)) {
                if (!rows.next()) {
                    throw new SQLException("MariaDB relation unavailable");
                }
                String ddl = rows.getString(2);
                if (ddl == null || !ddl.stripLeading().toUpperCase(Locale.ROOT)
                        .startsWith("CREATE TABLE ") || rows.next()) {
                    throw unsupported("MariaDB temporary tables and views cannot prove relation identity");
                }
            }
        }
        if (lowerCase == 1) {
            schema = schema.toLowerCase(Locale.ROOT);
            table = table.toLowerCase(Locale.ROOT);
        }
        String schemaField = lowerCase == 0 ? "TABLE_SCHEMA" : "LOWER(TABLE_SCHEMA)";
        String tableField = lowerCase == 0 ? "TABLE_NAME" : "LOWER(TABLE_NAME)";
        try (var query = connection.prepareStatement(
                "SELECT TABLE_TYPE,ENGINE FROM information_schema.TABLES WHERE BINARY "
                        + schemaField + "=BINARY ? AND BINARY " + tableField + "=BINARY ?")) {
            query.setQueryTimeout(5);
            query.setMaxRows(2);
            query.setString(1, schema);
            query.setString(2, table);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("MariaDB relation unavailable");
                }
                if (!"BASE TABLE".equals(rows.getString(1)) || !"InnoDB".equalsIgnoreCase(
                        rows.getString(2)) || rows.next()) {
                    throw unsupported("Only ordinary MariaDB InnoDB base tables are verified");
                }
            }
        }
        String tableId, space;
        try (var query = connection.prepareStatement(
                "SELECT TABLE_ID,SPACE FROM information_schema.INNODB_SYS_TABLES WHERE BINARY NAME=BINARY ?")) {
            query.setQueryTimeout(5);
            query.setMaxRows(2);
            query.setString(1, schema + "/" + table);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("MariaDB partitioned or non-dictionary relation is not verified");
                }
                tableId = rows.getString(1);
                space = rows.getString(2);
                if (tableId == null || !tableId.matches("[1-9][0-9]{0,19}") || space == null
                        || !space.matches("[1-9][0-9]{0,19}") || rows.next()) {
                    throw unsupported("MariaDB local relation generation is ambiguous");
                }
            }
        }
        MariaDbSourceGeneration.readVerified(connection, pin);
        return DigestUtil.sha256Hex(String.join("\0", "MARIADB_MANAGED_INNODB_V1", pin.fingerprint(), principal,
                role, schema, table, Integer.toString(lowerCase), tableId, space));
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }
}
