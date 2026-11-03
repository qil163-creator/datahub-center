package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** openGauss 7.0 RC3 A 模式永久普通行式堆表的原生身份，不代表 PostgreSQL 目录兼容。 */
public final class OpengaussRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String SQL = "SELECT pg_catalog.version(), pg_catalog.current_setting('sql_compatibility'), "
            + "s.system_identifier::pg_catalog.text, pg_catalog.current_database(), d.oid::pg_catalog.text, current_user, "
            + "n.nspname, c.relname, c.oid::pg_catalog.text, c.relfilenode::pg_catalog.text, "
            + "c.relkind='r' AND c.relpersistence='p' AND c.parttype='n' AND c.relfilenode>0 "
            + "AND NOT c.relhassubclass AND NOT c.relhasrules "
            + "AND (c.reloptions IS NULL OR NOT EXISTS (SELECT 1 FROM pg_catalog.unnest(c.reloptions) opt "
            + "WHERE opt NOT IN ('orientation=row','compression=no','storage_type=heap'))) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rlspolicy p WHERE p.polrelid=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.gs_masking_policy p WHERE p.polenabled) "
            + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
            + "JOIN pg_catalog.pg_database d ON d.datname=pg_catalog.current_database() "
            + "CROSS JOIN pg_catalog.pg_control_system() s WHERE c.oid=CAST(? AS pg_catalog.regclass)";

    /**
     * 初始化 {@code OpengaussRelationIdentity}。
     */
    private OpengaussRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("openGauss quoted/complex references are not verified");
        }
        List<String> first = identity(connection, reference);
        if (!first.equals(identity(connection, reference))) {
            throw unsupported("openGauss native identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /** 重放使用的精确物理名称，独立于认证主体。 */
    public static String physicalFingerprint(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        if (connection == null || timeout < 1) {
            throw new IllegalArgumentException("Connection and positive timeout required");
        }
        String reference = quote(schema) + '.' + quote(table);
        var first = identity(connection, reference, schema, table, timeout);
        if (!first.equals(identity(connection, reference, schema, table, timeout))) {
            throw unsupported("openGauss relation changed during capture");
        }
        var physical = new ArrayList<>(first);
        physical.remove(6);
        return DigestUtil.sha256Hex(String.join("\0", physical));
    }

    /**
     * 按openGauss方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("Invalid openGauss physical identifier");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String reference) throws SQLException {
        String[] names = reference.toLowerCase(Locale.ROOT).split("\\.");
        return identity(connection, reference, names.length == 2 ? names[0] : null, names[names.length - 1], 5);
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String reference, String schema, String table,
            int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, reference);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("openGauss relation is absent or not visible");
                }
                var fields = new ArrayList<>(List.of("OPENGAUSS_7_RC3_HEAP_V1"));
                for (int index = 1; index <= 10; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                        throw unsupported("openGauss native identity field is unavailable");
                    }
                    fields.add(value);
                }
                if (!fields.get(1).startsWith("(openGauss 7.0.0-RC3 ") || !"A".equals(fields.get(2))
                        || !fields.get(3).matches("[1-9][0-9]{0,19}") || !rows.getBoolean(11) || rows.wasNull()
                        || !table.equals(fields.get(8)) || schema != null && !schema.equals(
                        fields.get(7)) || rows.next()) {
                    throw unsupported(
                            "openGauss version, resolved object, storage or security semantics are not verified");
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
