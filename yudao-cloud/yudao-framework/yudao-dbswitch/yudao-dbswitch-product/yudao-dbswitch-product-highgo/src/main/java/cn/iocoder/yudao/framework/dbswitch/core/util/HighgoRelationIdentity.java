package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HighGo 6.0.4.2 中单张永久堆表的原生身份。
 * 此版本的目录具有 PostgreSQL 12 形态，但 {@code version()} 返回 HighGo 标识，
 * {@code server_version=12.7} 仅是兼容性凭据；不得使用 PostgreSQL 或 Greenplum 的身份逻辑。
 * {@code current_user} 为 SQL 关键字；服务器拒绝将 {@code pg_catalog.current_user} 作为列引用。
 */
public final class HighgoRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String VERSION_PREFIX = "HighGo Database 6 Release 6.0.4.2";

    private static final String SERVER_VERSION = "12.7";

    private static final String SQL = "SELECT pg_catalog.version(), pg_catalog.current_setting('server_version'), "
            + "s.system_identifier::pg_catalog.text, pg_catalog.current_database(), d.oid::pg_catalog.text, current_user, "
            + "n.nspname, c.relname, c.oid::pg_catalog.text, c.relfilenode::pg_catalog.text, a.amname, "
            + "c.relkind='r' AND c.relpersistence='p' AND c.relfilenode>0 AND NOT c.relispartition "
            + "AND NOT c.relhassubclass AND NOT c.relhasrules AND NOT c.relrowsecurity AND c.relpartbound IS NULL "
            + "AND a.amname='heap' AND c.reloptions IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policy pol WHERE pol.polrelid=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_seclabel sec WHERE sec.objoid=c.oid "
            + "AND sec.classoid=CAST('pg_class' AS pg_catalog.regclass)) "
            + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
            + "JOIN pg_catalog.pg_database d ON d.datname=pg_catalog.current_database() "
            + "JOIN pg_catalog.pg_am a ON a.oid=c.relam "
            + "CROSS JOIN pg_catalog.pg_control_system() s WHERE c.oid=CAST(? AS pg_catalog.regclass)";

    /**
     * 初始化 {@code HighgoRelationIdentity}。
     */
    private HighgoRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("HighGo quoted or complex references are not verified");
        }
        List<String> first = identity(connection, reference);
        if (!first.equals(identity(connection, reference))) {
            throw unsupported("HighGo native identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String reference) throws SQLException {
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, reference);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("HighGo relation is absent or not visible");
                }
                var fields = new ArrayList<>(List.of("HIGHGO_6_0_4_2_HEAP_V1"));
                for (int index = 1; index <= 11; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                        throw unsupported("HighGo native identity field is unavailable");
                    }
                    fields.add(value);
                }
                String[] names = reference.toLowerCase(Locale.ROOT).split("\\.");
                boolean qualified = rows.getBoolean(12);
                if (!fields.get(1).startsWith(VERSION_PREFIX) || !SERVER_VERSION.equals(fields.get(2))
                        || !fields.get(3).matches("[1-9][0-9]{0,19}") || !fields.get(5).matches("[1-9][0-9]{0,9}")
                        || !fields.get(9).matches("[1-9][0-9]{0,9}") || !fields.get(10).matches("[1-9][0-9]{0,9}")
                        || !"heap".equals(fields.get(11)) || !qualified || rows.wasNull()
                        || !names[names.length - 1].equals(fields.get(8))
                        || names.length == 2 && !names[0].equals(fields.get(7)) || rows.next()) {
                    throw unsupported("HighGo version, resolved object or storage is not verified");
                }
                return fields;
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
