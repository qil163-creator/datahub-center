package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Doris 4.0 内部 OLAP 身份：集群、有效主体、目录、数据库、表及分区代次。
 * 单独的 TABLE_ID 在 TRUNCATE 后保持。绝不以协议版本、行数、时间戳或 DDL 替代身份。
 * 不改变会话或事务；语句具有超时。拒绝外部目录和临时关系。
 */
public final class DorisRelationIdentity {

    private static final int MAX_PARTITIONS = 1024;

    /**
     * 初始化 {@code DorisRelationIdentity}。
     */
    private DorisRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Doris connection and relation reference are required");
        }
        try {
            return read(connection, reference);
        } catch (SQLException ex) {
            if ((ex.getErrorCode() == 1142 || ex.getErrorCode() == 1227) && "42000".equals(ex.getSQLState())) {
                throw unsupported("Doris relation metadata privileges are insufficient");
            }
            throw ex;
        }
    }

    /**
     * 读取 Doris 集群、主体、目录和表的原生身份。
     */
    private static String read(Connection connection, String reference) throws SQLException {
        String cluster = cluster(connection);
        String role;
        String defaultSchema;
        int lowerCase;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(
                    "SELECT CURRENT_CATALOG(), DATABASE(), CURRENT_USER(), @@lower_case_table_names")) {
                if (!rows.next() || !"internal".equals(rows.getString(1))) {
                    throw unsupported("Only Doris internal catalog is verified");
                }
                defaultSchema = rows.getString(2);
                role = rows.getString(3);
                lowerCase = rows.getInt(4);
                if (rows.wasNull() || lowerCase != 0 || role == null || role.isBlank() || rows.next()) {
                    throw unsupported("Doris session identity or case mode is not verified");
                }
            }
        }
        var relation = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.DORIS, reference, defaultSchema);
        String schema = relation.getSchemaName();
        String table = relation.getTableName();
        String qualified = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.DORIS, schema, table);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + qualified)) {
                if (!rows.next()) {
                    throw new SQLException("Doris relation cannot be resolved");
                }
                String ddl = rows.getString(2);
                if (ddl == null || !ddl.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE ")) {
                    throw unsupported("Doris views and temporary relations cannot provide this identity");
                }
                if (rows.next()) {
                    throw new SQLException("Doris relation is ambiguous");
                }
            }
        }
        // 大小写模式为 0 时，元数据名称须精确相等。验证返回名称，不能仅信任过滤结果。
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, ENGINE"
                        + " FROM internal.information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"internal".equals(rows.getString(1)) || !schema.equals(rows.getString(2))
                        || !table.equals(rows.getString(3)) || !"BASE TABLE".equals(rows.getString(4))
                        || !"Doris".equals(rows.getString(5)) || rows.next()) {
                    throw unsupported("Only unambiguous Doris internal OLAP base tables are verified");
                }
            }
        }
        List<String> ids = objectIds(connection, schema, table);
        TreeSet<String> partitions = new TreeSet<>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(MAX_PARTITIONS + 1);
            try (var rows = statement.executeQuery(
                    "SHOW PARTITIONS FROM " + qualified + " LIMIT " + (MAX_PARTITIONS + 1))) {
                while (rows.next()) {
                    String id = rows.getString("PartitionId");
                    if (!validId(id) || !"NORMAL".equals(rows.getString("State")) || !partitions.add(id)
                            || partitions.size() > MAX_PARTITIONS) {
                        throw unsupported("Doris partition identity is incomplete or exceeds its bound");
                    }
                }
            }
        }
        if (partitions.isEmpty() || !ids.equals(objectIds(connection, schema, table))) {
            throw unsupported("Doris relation changed or has no permanent partition generation");
        }
        List<String> fields = new ArrayList<>(
                List.of("DORIS_INTERNAL_OLAP_V1", cluster, role, "internal", schema, table,
                        Integer.toString(lowerCase)));
        fields.addAll(ids);
        fields.addAll(partitions);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /**
     * 从原生目录读取表及其所属目录、数据库的对象编号。
     */
    private static List<String> objectIds(Connection connection, String schema, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT CATALOG_ID, DATABASE_ID, TABLE_ID, DATABASE_NAME, TABLE_NAME"
                        + " FROM internal.information_schema.metadata_name_ids WHERE CATALOG_NAME='internal' AND DATABASE_NAME=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !schema.equals(rows.getString(4)) || !table.equals(rows.getString(5))) {
                    throw unsupported("Doris native object IDs are unavailable");
                }
                String catalog = rows.getString(1), database = rows.getString(2), relation = rows.getString(3);
                if (!"0".equals(catalog) || !validId(database) || !validId(relation) || rows.next()) {
                    throw unsupported("Doris native object IDs are ambiguous");
                }
                return List.of(catalog, database, relation);
            }
        }
    }

    /**
     * 返回当前集群。
     */
    private static String cluster(Connection connection) throws SQLException {
        String cluster = null;
        boolean connected = false;
        int count = 0;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                while (rows.next()) {
                    String id = rows.getString("ClusterId");
                    String version = rows.getString("Version");
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
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }
}
