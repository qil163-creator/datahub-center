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
 * 已验证的 OceanBase CE 4.3.5.6 MySQL 行存标识。仅表 ID 会在 TRUNCATE 后保留；
 * 同时绑定其 tablet 代际。使用租户目录 UUID，绝不使用端点、
 * 服务器启动时间或用户可配置的 cluster_id。刻意不推断 Oracle 模式。
 * 所有探测均只读，连接和事务仍由调用方持有。
 */
public final class OceanbaseRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    /**
     * 初始化 {@code OceanbaseRelationIdentity}。
     */
    private OceanbaseRelationIdentity() {
    }

    /** 根据实际连接为仅限 MySQL 的 SQL 模板设置门禁，不使用此前缓存的租户模式。 */
    public static void requireVerifiedMysqlMode(Connection connection) throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("Connection required");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT VERSION(),@@ob_compatibility_mode")) {
                if (!rows.next() || !"5.7.25-OceanBase_CE-v4.3.5.6".equals(rows.getString(1))
                        || !"MYSQL".equals(rows.getString(2)) || rows.next()) {
                    throw unsupported("OceanBase MySQL projection mode/version is not verified");
                }
            }
        }
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("OceanBase complex/quoted relation names are not verified");
        }
        List<String> session = session(connection);
        String[] names = reference.split("\\.");
        String schema = (names.length == 2 ? names[0] : session.get(1)).toLowerCase(Locale.ROOT);
        String table = names[names.length - 1].toLowerCase(Locale.ROOT);
        if (!schema.matches(IDENTIFIER) || List.of("oceanbase", "mysql", "information_schema", "sys")
                .contains(schema)) {
            throw unsupported("OceanBase user schema unavailable");
        }
        List<String> object = object(connection, schema, table);
        if (!object.equals(object(connection, schema, table)) || !session.equals(session(connection))) {
            throw unsupported("OceanBase relation/session changed during identity capture");
        }
        var fields = new ArrayList<>(List.of("OCEANBASE_MYSQL_ROW_V1"));
        fields.addAll(session);
        fields.addAll(object);
        return DigestUtil.sha256Hex(String.join("\0", fields));
    }

    /**
     * 返回会话。
     */
    private static List<String> session(Connection connection) throws SQLException {
        String database, user, role;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT VERSION(),DATABASE(),CURRENT_USER(),CURRENT_ROLE(),"
                    + "@@ob_compatibility_mode,@@lower_case_table_names,@@ob_read_consistency,@@autocommit")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase session unavailable");
                }
                database = rows.getString(2);
                user = rows.getString(3);
                role = rows.getString(4);
                if (!"5.7.25-OceanBase_CE-v4.3.5.6".equals(rows.getString(1)) || !validText(user) || !validText(role)
                        || !"MYSQL".equals(rows.getString(5)) || !"1".equals(rows.getString(6))
                        || !"STRONG".equals(rows.getString(7)) || !"1".equals(rows.getString(8)) || rows.next()) {
                    throw unsupported("OceanBase native version/mode/current-read session is not verified");
                }
                database = database == null ? "" : database.toLowerCase(Locale.ROOT);
                if (!database.isEmpty() && !database.matches(IDENTIFIER)) {
                    throw unsupported("OceanBase default schema is not verified");
                }
            }
            // 仅有 @@autocommit 不能检测显式 BEGIN。不要回滚它，也不改变隔离级别。
            try (var rows = statement.executeQuery(
                    "SELECT TRANS_ID,TRANS_STATE FROM oceanbase.GV$OB_SESSION WHERE ID=CONNECTION_ID()")) {
                if (!rows.next() || !"0".equals(rows.getString(1)) || !"".equals(rows.getString(2)) || rows.next()) {
                    throw unsupported("OceanBase current transaction cannot be verified");
                }
            }
            String tenantId, epoch;
            try (var rows = statement.executeQuery(
                    "SELECT TENANT_ID,TENANT_TYPE,COMPATIBILITY_MODE,STATUS,IN_RECYCLEBIN,"
                            + "LOCKED,TENANT_ROLE,SWITCHOVER_STATUS,SWITCHOVER_EPOCH,COMPATIBLE,RESTORE_DATA_MODE FROM oceanbase.DBA_OB_TENANTS")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase tenant unavailable");
                }
                tenantId = rows.getString(1);
                epoch = rows.getString(9);
                if (!validId(tenantId) || epoch == null || !epoch.matches("0|[1-9][0-9]{0,18}")
                        || !"USER".equals(rows.getString(2)) || !"MYSQL".equals(rows.getString(3))
                        || !"NORMAL".equals(rows.getString(4)) || !"NO".equals(rows.getString(5)) || !"NO".equals(
                        rows.getString(6))
                        || !"PRIMARY".equals(rows.getString(7)) || !"NORMAL".equals(rows.getString(8))
                        || !"4.3.5.6".equals(rows.getString(10)) || !"NORMAL".equals(
                        rows.getString(11)) || rows.next()) {
                    throw unsupported("OceanBase tenant identity/state is not verified");
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT VALUE,SCOPE FROM oceanbase.DBA_OB_SYS_VARIABLES WHERE NAME='server_uuid'")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase persisted tenant UUID unavailable");
                }
                String uuid = rows.getString(1);
                if (uuid == null || !uuid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                        || !"GLOBAL_ONLY".equals(rows.getString(2)) || rows.next()) {
                    throw unsupported("OceanBase persisted tenant UUID ambiguous");
                }
                return List.of(uuid, database, user, role, tenantId, epoch, "4.3.5.6", "MYSQL", "STRONG_AUTOCOMMIT");
            }
        }
    }

    /**
     * 按当前值域约定解析或转换对象。
     */
    private static List<String> object(Connection connection, String schema, String table) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE `" + schema + "`.`" + table + "`")) {
                if (!rows.next()) {
                    throw unsupported("OceanBase relation unavailable");
                }
                String ddl = rows.getString(2);
                if (ddl == null || !ddl.stripLeading().startsWith("CREATE TABLE ") || !ddl.contains(
                        ") ORGANIZATION INDEX ")
                        || ddl.contains("COLUMN GROUP") || rows.next()) {
                    throw unsupported("OceanBase views, temporary and non-row-store tables are not verified");
                }
            }
        }
        var fields = new TreeSet<String>();
        var partitions = new TreeSet<String>();
        String tableId = null;
        try (var statement = connection.prepareStatement(
                "SELECT DISTINCT TABLE_ID,TABLE_TYPE,PARTITION_NAME,SUBPARTITION_NAME,"
                        + "TABLET_ID,OBJECT_ID FROM oceanbase.DBA_OB_TABLE_LOCATIONS WHERE DATABASE_NAME=? AND TABLE_NAME=?"
                        + " AND INDEX_NAME IS NULL AND DATA_TABLE_ID IS NULL LIMIT 1025")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(1025);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    String id = rows.getString(1), partition = locationName(
                            rows.getString(3)), subpartition = locationName(rows.getString(4));
                    String tablet = rows.getString(5), object = rows.getString(6);
                    if (++count > 1024 || !validId(id) || !"USER TABLE".equals(rows.getString(2)) || !validId(tablet)
                            || !validId(object) || (tableId != null && !tableId.equals(id))) {
                        throw unsupported("OceanBase native table/tablet identity is incomplete or ambiguous");
                    }
                    tableId = id;
                    String key = partition + "/" + subpartition;
                    if (!partitions.add(key)) {
                        throw unsupported("OceanBase partition has ambiguous tablet generations");
                    }
                    fields.add(key + "/" + object + "/" + tablet);
                }
            }
        }
        if (fields.isEmpty()) {
            throw unsupported("OceanBase native tablets not visible");
        }
        // 拒绝不完整目录视图，不只比较部分物理分区。
        var expected = new TreeSet<String>();
        try (var statement = connection.prepareStatement(
                "SELECT PARTITION_NAME,SUBPARTITION_NAME FROM information_schema.PARTITIONS"
                        + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? LIMIT 1025")) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(1025);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 1024 || !expected.add(name(rows.getString(1)) + "/" + name(rows.getString(2)))) {
                        throw unsupported("OceanBase partition catalog exceeds its bound or is ambiguous");
                    }
                }
            }
        }
        if (!expected.equals(partitions)) {
            throw unsupported("OceanBase native tablet partition coverage is incomplete");
        }
        var result = new ArrayList<>(List.of(schema, table, tableId));
        result.addAll(fields);
        return result;
    }

    // DBA_OB_TABLE_LOCATIONS 使用字面量 "NULL"，不同于 information_schema.PARTITIONS。

    /**
     * 将目录中的空位置标记规范化为实际对象名称。
     */
    private static String locationName(String value) throws SQLException {
        return "NULL".equals(value) ? "" : name(value);
    }

    /**
     * 返回名称。
     */
    private static String name(String value) throws SQLException {
        if (value == null) {
            return "";
        }
        if (!value.matches(IDENTIFIER)) {
            throw unsupported("OceanBase complex partition name is not verified");
        }
        return value;
    }

    /**
     * 校验文本是否满足当前协议的字符与长度限制。
     */
    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= 16384 && value.indexOf('\0') < 0;
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
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
