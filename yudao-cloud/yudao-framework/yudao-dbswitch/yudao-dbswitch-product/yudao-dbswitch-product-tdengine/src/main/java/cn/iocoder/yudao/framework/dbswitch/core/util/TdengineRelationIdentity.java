package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 原生 TDengine 社区版 3.3.8.8 普通表代次和未过滤读取身份。
 * 仅负责有界读取语句，不拥有调用方会话或事务。不向源写入标记。
 */
public final class TdengineRelationIdentity {

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    /**
     * 初始化 {@code TdengineRelationIdentity}。
     */
    private TdengineRelationIdentity() {
    }

    /** 与原生迁移共享的物理集群证据；独立于 URL 写法和账号。 */
    public static String clusterFingerprint(Connection connection, int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw unsupported("TDengine cluster observation requires a bounded connection");
        }
        var cluster = one(connection,
                "SELECT CAST(id AS VARCHAR(64)),name,version FROM information_schema.ins_cluster LIMIT 2", 3, false,
                timeout);
        if (!cluster.getFirst().matches("[1-9][0-9]{0,19}")) {
            throw unsupported("TDengine cluster generation is not verified");
        }
        return DigestUtil.sha256Hex("TDENGINE_CLUSTER_V1\0" + String.join("\0", cluster));
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || !reference.matches(NAME + "(?:\\." + NAME + ")?")) {
            throw unsupported("TDengine requires an ordinary table reference");
        }
        List<String> first = identity(connection, reference.toLowerCase(Locale.ROOT));
        if (!first.equals(identity(connection, reference.toLowerCase(Locale.ROOT)))) {
            throw unsupported("TDengine native identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String reference) throws SQLException {
        var fields = new ArrayList<>(List.of("TDENGINE_3_3_8_8_NORMAL_V1"));
        List<String> session = one(connection, "SELECT server_version(),current_user(),database()", 3, true);
        if (!"3.3.8.8".equals(session.get(0))) {
            throw unsupported("TDengine version is not verified");
        }
        String principal = session.get(1);
        int separator = principal.indexOf('@');
        if (separator <= 0 || separator != principal.lastIndexOf('@') || separator == principal.length() - 1
                || !principal.substring(0, separator).matches("[A-Za-z_][A-Za-z0-9_]{0,23}")) {
            throw unsupported("TDengine session principal is not verified");
        }
        // 后缀是服务节点主机名，而非账号身份。排除后缀可允许节点变化。
        String user = principal.substring(0, separator);
        String[] parts = reference.split("\\.");
        String schema = parts.length == 2 ? parts[0] : session.get(2);
        String table = parts[parts.length - 1];
        if (!schema.matches(NAME) || "information_schema".equals(schema) || "performance_schema".equals(schema)) {
            throw unsupported("TDengine user database is required");
        }
        fields.add(session.get(0));
        fields.add(user);
        fields.add(schema);
        fields.add(table);
        var cluster = one(connection,
                "SELECT CAST(id AS VARCHAR(64)),name,version FROM information_schema.ins_cluster LIMIT 2", 3, false);
        if (!cluster.get(0).matches("[1-9][0-9]{0,19}") || !"community".equals(cluster.get(2))) {
            throw unsupported("TDengine native cluster identity or edition is not verified");
        }
        fields.addAll(cluster);
        var account = one(connection,
                "SELECT `name`,CAST(CAST(create_time AS BIGINT) AS VARCHAR(32)),`super`,`enable`,`sysinfo` "
                        + "FROM information_schema.ins_users WHERE `name`='" + user + "' LIMIT 2", 5, false);
        if (!user.equals(account.get(0)) || !"1".equals(account.get(3)) || !"1".equals(account.get(4))) {
            throw unsupported("TDengine enabled account with native metadata visibility is required");
        }
        fields.addAll(account);
        var database = one(connection, "SELECT CAST(CAST(create_time AS BIGINT) AS VARCHAR(32)),`precision`,`status` "
                + "FROM information_schema.ins_databases WHERE name='" + schema + "' LIMIT 2", 3, false);
        if (!List.of("ms", "us", "ns").contains(database.get(1)) || !"ready".equals(database.get(2))) {
            throw unsupported("TDengine database state is not verified");
        }
        fields.addAll(database);
        var relation = one(connection, "SELECT table_name,db_name,CAST(uid AS VARCHAR(64)),"
                        + "CAST(CAST(create_time AS BIGINT) AS VARCHAR(32)),`type`,COALESCE(stable_name,'') "
                        + "FROM information_schema.ins_tables WHERE db_name='" + schema + "' AND table_name='" + table + "' LIMIT 2",
                6, true);
        if (!table.equals(relation.get(0)) || !schema.equals(relation.get(1))
                || !relation.get(2).matches("[1-9][0-9]{0,19}") || relation.get(3).isBlank()
                || !"NORMAL_TABLE".equals(relation.get(4)) || !relation.get(5).isEmpty()) {
            throw unsupported("TDengine ordinary physical table generation is not verified");
        }
        fields.addAll(relation);
        // 拒绝带条件的授权。仅保留与此物理关系相关的有限集合；
        // 数据库或表授权变化使证据失效，不能伪装为修复。
        String grants = "SELECT privilege,db_name,table_name,CASE WHEN `condition` IS NULL OR LENGTH(`condition`)=0 THEN 0 ELSE 1 END "
                + "FROM information_schema.ins_user_privileges WHERE user_name='" + user + "' AND (db_name='all' OR db_name='"
                + schema + "') AND (table_name='' OR table_name='*' OR table_name='" + table + "') LIMIT 513";
        var permissions = new ArrayList<String>();
        boolean readable = false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(513);
            try (var rows = statement.executeQuery(grants)) {
                while (rows.next()) {
                    if (permissions.size() == 512 || rows.getInt(4) != 0 || rows.wasNull()) {
                        throw unsupported("TDengine conditional or excessive grants are not verified");
                    }
                    String privilege = value(rows.getString(1), false);
                    String db = value(rows.getString(2), false);
                    String target = value(rows.getString(3), true);
                    readable |= "read".equals(privilege) || "all".equals(privilege);
                    permissions.add(privilege + "\0" + db + "\0" + target);
                }
            }
        }
        if (!readable) {
            throw unsupported("TDengine unconditional read grant is required");
        }
        permissions.sort(String::compareTo);
        fields.addAll(permissions);
        return fields;
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static List<String> one(Connection connection, String sql, int columns,
            boolean allowEmpty) throws SQLException {
        return one(connection, sql, columns, allowEmpty, 5);
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static List<String> one(Connection connection, String sql, int columns, boolean allowEmpty,
            int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw unsupported("TDengine native identity is absent or not visible");
                }
                var result = new ArrayList<String>();
                for (int i = 1; i <= columns; i++) {
                    result.add(value(rows.getString(i), allowEmpty));
                }
                if (rows.next()) {
                    throw unsupported("TDengine native identity is ambiguous");
                }
                return result;
            }
        }
    }

    /**
     * 返回值。
     */
    private static String value(String value, boolean allowEmpty) throws SQLException {
        if (value == null && allowEmpty) {
            return "";
        }
        if (value == null || !allowEmpty && value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
            throw unsupported("TDengine native identity field is unavailable");
        }
        return value;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
