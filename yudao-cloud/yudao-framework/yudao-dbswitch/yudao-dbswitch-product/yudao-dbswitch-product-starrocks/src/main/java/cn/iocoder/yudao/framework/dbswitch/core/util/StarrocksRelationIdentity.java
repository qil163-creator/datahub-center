package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * StarRocks 3.5 原生内部 OLAP 身份，非 MySQL 协议版本。
 * 绑定集群、表、分区代次及有效主体、角色。不改变事务或会话。
 * 拒绝外部目录、声明的 Ranger 过滤、临时遮蔽表及不可观察元数据。
 * 要求 FE 启动时即使用原生访问控制；access_control 改变后，全部 FE 必须重启，
 * 调用方必须重建基线。SQL 配置值无法证明内存中的控制器状态。
 */
public final class StarrocksRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final int MAX_PARTITIONS = 1024;

    /**
     * 初始化 {@code StarrocksRelationIdentity}。
     */
    private StarrocksRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("StarRocks complex/quoted relation names are not verified");
        }
        List<String> session = session(connection);
        String[] names = reference.split("\\.");
        String schema = names.length == 2 ? names[0] : session.get(1);
        String table = names[names.length - 1];
        if (!schema.matches(IDENTIFIER)) {
            throw unsupported("StarRocks default database is not verified");
        }
        List<String> object = object(connection, schema, table);
        if (!object.equals(object(connection, schema, table)) || !session.equals(session(connection))) {
            throw unsupported("StarRocks native relation/session changed during identity capture");
        }
        var fields = new ArrayList<>(List.of("STARROCKS_NATIVE_OLAP_V1"));
        fields.addAll(session);
        fields.addAll(object);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /**
     * 返回会话。
     */
    private static List<String> session(Connection connection) throws SQLException {
        String cluster = null;
        int count = 0;
        boolean leader = false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                while (rows.next()) {
                    String id = rows.getString("ClusterId"), version = rows.getString("Version");
                    if (++count > 64 || !validId(id) || cluster != null && !cluster.equals(id)
                            || version == null || !version.startsWith("3.5.")
                            || !rows.getBoolean("Alive") || !rows.getBoolean("Join")) {
                        throw unsupported("StarRocks native cluster/version is not verified");
                    }
                    if ("LEADER".equals(rows.getString("Role"))) {
                        if (leader) {
                            throw unsupported("StarRocks cluster leadership is ambiguous");
                        }
                        leader = true;
                    }
                    cluster = id;
                }
            }
            if (!leader) {
                throw unsupported("StarRocks cluster identity is unavailable");
            }
            // 空结果或受权限过滤的结果，不能证明没有行过滤或脱敏。
            statement.setMaxRows(2);
            // Connector/J 不将 StarRocks ADMIN SHOW 识别为查询。execute 仍仅发送此固定读取语句。
            if (!statement.execute("ADMIN SHOW FRONTEND CONFIG LIKE 'access_control'")) {
                throw unsupported("StarRocks access-control metadata unavailable");
            }
            try (var rows = statement.getResultSet()) {
                if (rows == null || !rows.next() || !"access_control".equals(rows.getString("Key"))
                        || !"native".equals(rows.getString("Value")) || rows.next()) {
                    throw unsupported("StarRocks native access control is not proven");
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT CATALOG(), DATABASE(), CURRENT_USER(), CURRENT_ROLE(), @@lower_case_table_names")) {
                if (!rows.next()) {
                    throw unsupported("StarRocks session unavailable");
                }
                String catalog = rows.getString(1), database = rows.getString(2), user = rows.getString(
                        3), roles = rows.getString(4);
                int lowerCase = rows.getInt(5);
                if (rows.wasNull() || lowerCase != 0 || !"default_catalog".equals(catalog) || database == null
                        || user == null || user.isBlank() || user.indexOf('\0') >= 0
                        || roles == null || roles.length() > 16384 || roles.indexOf('\0') >= 0 || rows.next()) {
                    throw unsupported("StarRocks session/catalog/case mode is not verified");
                }
                return List.of(cluster, database, user, roles, catalog, "3.5", "native", "0");
            }
        }
    }

    /**
     * 按当前值域约定解析或转换对象。
     */
    private static List<String> object(Connection connection, String schema, String table) throws SQLException {
        String qualified = "`" + schema + "`.`" + table + "`";
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + qualified)) {
                if (!rows.next()) {
                    throw unsupported("StarRocks relation unavailable");
                }
                String ddl = rows.getString(2);
                if (ddl == null || !ddl.stripLeading().toUpperCase(Locale.ROOT)
                        .startsWith("CREATE TABLE ") || rows.next()) {
                    throw unsupported("StarRocks views and temporary relations are not verified");
                }
            }
        }
        String id;
        try (var statement = connection.prepareStatement(
                "SELECT c.TABLE_SCHEMA,c.TABLE_NAME,c.TABLE_ENGINE,c.TABLE_MODEL,c.TABLE_ID,t.TABLE_TYPE,t.ENGINE"
                        + " FROM default_catalog.information_schema.tables_config c JOIN default_catalog.information_schema.tables t"
                        + " ON c.TABLE_SCHEMA=t.TABLE_SCHEMA AND c.TABLE_NAME=t.TABLE_NAME WHERE c.TABLE_SCHEMA=? AND c.TABLE_NAME=? LIMIT 2")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !schema.equals(rows.getString(1)) || !table.equals(rows.getString(2))
                        || !"OLAP".equals(rows.getString(3))
                        || !("PRIMARY_KEYS".equals(rows.getString(4)) || "DUP_KEYS".equals(rows.getString(4)))
                        || !"BASE TABLE".equals(rows.getString(6)) || !"StarRocks".equals(rows.getString(7))) {
                    throw unsupported("Only permanent StarRocks native PRIMARY/DUPLICATE OLAP tables are verified");
                }
                id = rows.getString(5);
                if (!validId(id) || rows.next()) {
                    throw unsupported("StarRocks native table ID is ambiguous");
                }
            }
        }
        // TABLE_ID 在 TRUNCATE 后保持；必须纳入分区代次。不得对可见行版本取哈希。
        var partitions = new TreeSet<String>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(MAX_PARTITIONS + 1);
            try (var rows = statement.executeQuery(
                    "SHOW PARTITIONS FROM " + qualified + " LIMIT " + (MAX_PARTITIONS + 1))) {
                while (rows.next()) {
                    String partition = rows.getString("PartitionId");
                    if (!validId(partition) || !"NORMAL".equals(rows.getString("State")) || !partitions.add(partition)
                            || partitions.size() > MAX_PARTITIONS) {
                        throw unsupported("StarRocks partition generation is incomplete or exceeds its bound");
                    }
                }
            }
        }
        if (partitions.isEmpty()) {
            throw unsupported("StarRocks has no permanent partition generation");
        }
        var fields = new ArrayList<>(List.of(schema, table, id));
        fields.addAll(partitions);
        return fields;
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
