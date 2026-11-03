package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Greenplum 7.1.0 单容器中具有哈希分布键的永久堆表原生身份。
 * 此处不声明与 PostgreSQL 或 openGauss 目录兼容：此版本没有 {@code relrowsecurity} 的替代项，
 * 分布信息保存在 {@code gp_distribution_policy} 中，且无法在协调节点关联 {@code pg_exttable}。
 */
public final class GreenplumRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String VERSION_PREFIX = "PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev)";

    private static final String SERVER_VERSION = "7.1.0 build dev";

    private static final String SQL = "SELECT pg_catalog.version(), pg_catalog.current_setting('gp_server_version'), "
            + "s.system_identifier::pg_catalog.text, pg_catalog.current_database(), d.oid::pg_catalog.text, current_user, "
            + "n.nspname, c.relname, c.oid::pg_catalog.text, c.relfilenode::pg_catalog.text, "
            + "p.policytype::pg_catalog.text, p.numsegments::pg_catalog.text, p.distkey::pg_catalog.text, a.amname, "
            + "c.relkind='r' AND c.relpersistence='p' AND c.relfilenode>0 AND NOT c.relispartition "
            + "AND NOT c.relhassubclass AND NOT c.relhasrules AND NOT c.relrowsecurity AND c.relpartbound IS NULL "
            + "AND a.amname='heap' AND p.policytype='p' "
            + "AND p.distkey::pg_catalog.text ~ '^[1-9][0-9]*( [1-9][0-9]*)*$' "
            + "AND p.numsegments=(SELECT count(*) FROM pg_catalog.gp_segment_configuration g "
            + "WHERE g.content>=0 AND g.role='p' AND g.status='u') "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policy pol WHERE pol.polrelid=c.oid) "
            + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
            + "JOIN pg_catalog.pg_database d ON d.datname=pg_catalog.current_database() "
            + "JOIN pg_catalog.pg_am a ON a.oid=c.relam "
            + "JOIN pg_catalog.gp_distribution_policy p ON p.localoid=c.oid "
            + "CROSS JOIN pg_catalog.pg_control_system() s WHERE c.oid=CAST(? AS pg_catalog.regclass)";

    /**
     * 初始化 {@code GreenplumRelationIdentity}。
     */
    private GreenplumRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("Greenplum quoted or complex references are not verified");
        }
        List<String> first = identity(connection, reference);
        if (!first.equals(identity(connection, reference))) {
            throw unsupported("Greenplum native identity changed during capture");
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
                    throw unsupported("Greenplum relation is absent or not visible");
                }
                var fields = new ArrayList<>(List.of("GREENPLUM_7_1_HEAP_HASH_V1"));
                for (int index = 1; index <= 14; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                        throw unsupported("Greenplum native identity field is unavailable");
                    }
                    fields.add(value);
                }
                String[] names = reference.toLowerCase(Locale.ROOT).split("\\.");
                boolean qualified = rows.getBoolean(15);
                if (!fields.get(1).startsWith(VERSION_PREFIX) || !SERVER_VERSION.equals(fields.get(2))
                        || !fields.get(3).matches("[1-9][0-9]{0,19}") || !fields.get(5).matches("[1-9][0-9]{0,9}")
                        || !fields.get(9).matches("[1-9][0-9]{0,9}") || !fields.get(10).matches("[1-9][0-9]{0,9}")
                        || !"p".equals(fields.get(11)) || !fields.get(12).matches("[1-9][0-9]{0,4}")
                        || !fields.get(13).matches("[1-9][0-9]*( [1-9][0-9]*)*") || !"heap".equals(fields.get(14))
                        || !qualified || rows.wasNull()
                        || !names[names.length - 1].equals(fields.get(8))
                        || names.length == 2 && !names[0].equals(fields.get(7)) || rows.next()) {
                    throw unsupported("Greenplum version, resolved object, storage or distribution is not verified");
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
