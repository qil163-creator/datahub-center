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
     * 以同一连接两次原生观测固定普通物理名称与代次；不开放仅凭语法的授权入口。
     */
    public static cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity resolveCatalogRelation(
            Connection connection, String schema, String table, int timeout) throws SQLException {
        if (connection == null || schema == null || table == null
                || !schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}") || timeout < 1 || timeout > 60) {
            throw new IllegalArgumentException("Doris catalog 需要普通物理名称及一至六十秒期限");
        }
        String reference = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.DORIS, schema, table);
        String first = read(connection, reference, timeout);
        if (!first.equals(read(connection, reference, timeout))) {
            throw unsupported("Doris catalog relation changed during resolution");
        }
        return new cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity(
                ProductTypeEnum.DORIS, schema, table, first);
    }

    /** 原生列表只暴露 internal OLAP 基表；每个条目继续通过临时遮蔽、版本及代次核验。 */
    public static List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(
            Connection connection, String schema, int maxTables, int timeout) throws SQLException {
        if (connection == null || schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || maxTables < 1 || maxTables > 10_000 || timeout < 1 || timeout > 60) {
            throw new IllegalArgumentException("Doris catalog 列表范围或读取期限无效");
        }
        cluster(connection, timeout);
        session(connection, timeout);
        List<String> names = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT TABLE_SCHEMA,TABLE_NAME FROM "
                + "internal.information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' "
                + "AND ENGINE='Doris' ORDER BY TABLE_NAME")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(maxTables + 1);
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(2);
                    if (!schema.equals(rows.getString(1)) || name == null
                            || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}") || names.contains(name)) {
                        throw unsupported("Doris catalog table names are ambiguous or unsupported");
                    }
                    names.add(name);
                    if (names.size() > maxTables) {
                        throw new IllegalArgumentException("Doris 表列表超过读取上限");
                    }
                }
            }
        }
        var result = new ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription>();
        for (String name : names) {
            var identity = resolveCatalogRelation(connection, schema, name, timeout);
            var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
            table.setSchemaName(identity.schemaName());
            table.setTableName(identity.tableName());
            table.setCatalogObjectId(identity.objectId());
            table.setTableType("TABLE");
            result.add(table);
        }
        return List.copyOf(result);
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Doris connection and relation reference are required");
        }
        try {
            return read(connection, reference, 5);
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
    private static String read(Connection connection, String reference, int timeout) throws SQLException {
        String cluster = cluster(connection, timeout);
        var session = session(connection, timeout);
        var relation = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.DORIS, reference, session.schema());
        String schema = relation.getSchemaName();
        String table = relation.getTableName();
        String qualified = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.DORIS, schema, table);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
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
        // Doris 目录的 TABLE_NAME 等值下推可能按 LIKE 解释下划线；STRCMP 保持物理名称精确比较。
        // 大小写模式为 0 时还须验证返回名称，不能仅信任过滤结果。
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, ENGINE"
                        + " FROM internal.information_schema.tables WHERE TABLE_SCHEMA=? AND STRCMP(TABLE_NAME,?)=0")) {
            statement.setQueryTimeout(timeout);
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
        List<String> ids = objectIds(connection, schema, table, timeout);
        TreeSet<String> partitions = new TreeSet<>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
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
        if (partitions.isEmpty() || !ids.equals(objectIds(connection, schema, table, timeout))) {
            throw unsupported("Doris relation changed or has no permanent partition generation");
        }
        List<String> fields = new ArrayList<>(
                List.of("DORIS_INTERNAL_OLAP_V1", cluster, session.role(), "internal", schema, table, "0"));
        fields.addAll(ids);
        fields.addAll(partitions);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /** 即使列表为空，也须确认会话当前目录及名称大小写语义。 */
    private static Session session(Connection connection, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT CURRENT_CATALOG(), DATABASE(), CURRENT_USER(), @@lower_case_table_names")) {
                if (!rows.next() || !"internal".equals(rows.getString(1))) {
                    throw unsupported("Only Doris internal catalog is verified");
                }
                String schema = rows.getString(2);
                String role = rows.getString(3);
                int lowerCase = rows.getInt(4);
                if (rows.wasNull() || lowerCase != 0 || role == null || role.isBlank() || rows.next()) {
                    throw unsupported("Doris session identity or case mode is not verified");
                }
                return new Session(schema, role);
            }
        }
    }

    private record Session(String schema, String role) { }

    /**
     * 从原生目录读取表及其所属目录、数据库的对象编号。
     */
    private static List<String> objectIds(Connection connection, String schema, String table, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT CATALOG_ID, DATABASE_ID, TABLE_ID, DATABASE_NAME, TABLE_NAME"
                        + " FROM internal.information_schema.metadata_name_ids WHERE CATALOG_NAME='internal' AND DATABASE_NAME=? AND TABLE_NAME=?")) {
            statement.setQueryTimeout(timeout);
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
    private static String cluster(Connection connection, int timeout) throws SQLException {
        String cluster = null;
        boolean connected = false;
        int count = 0;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                while (rows.next()) {
                    String id = rows.getString("ClusterId");
                    String version = rows.getString("Version");
                    if (++count > 64 || !validId(id) || cluster != null && !cluster.equals(id)
                            || version == null || !version.matches("doris-4\\.0\\.[0-9]+(?:[-+].*)?")) {
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
