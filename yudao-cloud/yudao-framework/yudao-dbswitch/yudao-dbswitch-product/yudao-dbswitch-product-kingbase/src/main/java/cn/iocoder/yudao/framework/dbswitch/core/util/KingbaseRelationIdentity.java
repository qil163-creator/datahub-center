package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KingbaseES V008R006C009B0014 中单张永久堆表的原生身份。
 * 此版本使用 {@code database_mode=oracle} 和 {@code enable_ci=on}；{@code server_version=12.1}
 * 仅是兼容性凭据，不得使用 PostgreSQL、HighGo、openGauss、Greenplum、Oracle 或 DM 的身份逻辑。
 * 未加引号的名称保存为小写，带引号名称保留目录大小写，但比较不区分大小写。
 * 引用自身仍须为普通标识符，按返回的存储拼写生成哈希。
 */
public final class KingbaseRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String VERSION = "KingbaseES V008R006C009B0014 on x86_64-pc-linux-gnu, "
            + "compiled by gcc (GCC) 4.8.5 20150623 (Red Hat 4.8.5-28), 64-bit";

    private static final String SQL = "SELECT pg_catalog.version(), pg_catalog.current_setting('server_version'), "
            + "pg_catalog.current_setting('database_mode'), pg_catalog.current_setting('enable_ci'), "
            + "s.system_identifier::pg_catalog.text, pg_catalog.current_database(), d.oid::pg_catalog.text, "
            + "pg_catalog.current_user, n.nspname, c.relname, c.oid::pg_catalog.text, c.relfilenode::pg_catalog.text, a.amname, "
            + "c.relkind='r' AND c.relpersistence='p' AND c.relfilenode>0 AND NOT c.relispartition "
            + "AND NOT c.relhassubclass AND NOT c.relhasrules AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity "
            + "AND c.relpartbound IS NULL AND a.amname='heap' AND c.reloptions IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policy pol WHERE pol.polrelid=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_seclabel sec WHERE sec.objoid=c.oid "
            + "AND sec.classoid=CAST('pg_class' AS pg_catalog.regclass)) "
            + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
            + "JOIN pg_catalog.pg_database d ON d.datname=pg_catalog.current_database() "
            + "JOIN pg_catalog.pg_am a ON a.oid=c.relam "
            + "CROSS JOIN pg_catalog.pg_control_system() s WHERE c.oid=CAST(? AS pg_catalog.regclass)";

    /**
     * 初始化 {@code KingbaseRelationIdentity}。
     */
    private KingbaseRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("Kingbase quoted or complex references are not verified");
        }
        List<String> first = identity(connection, reference);
        if (!first.equals(identity(connection, reference))) {
            throw unsupported("Kingbase native identity changed during capture");
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
                    throw unsupported("Kingbase relation is absent or not visible");
                }
                var fields = new ArrayList<>(List.of("KINGBASE_V008R006C009B0014_ORACLE_CI_HEAP_V1"));
                for (int index = 1; index <= 13; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                        throw unsupported("Kingbase native identity field is unavailable");
                    }
                    fields.add(value);
                }
                String[] names = reference.split("\\.");
                boolean qualified = rows.getBoolean(14);
                if (!VERSION.equals(fields.get(1)) || !"12.1".equals(fields.get(2))
                        || !"oracle".equals(fields.get(3)) || !"on".equals(fields.get(4))
                        || !fields.get(5).matches("[1-9][0-9]{0,19}") || !fields.get(7).matches("[1-9][0-9]{0,9}")
                        || !fields.get(11).matches("[1-9][0-9]{0,9}") || !fields.get(12).matches("[1-9][0-9]{0,9}")
                        || !"heap".equals(fields.get(13)) || !qualified || rows.wasNull()
                        || !sameName(fields.get(10), names[names.length - 1])
                        || names.length == 2 && !sameName(fields.get(9), names[0]) || rows.next()) {
                    throw unsupported("Kingbase version, mode, resolved object or storage is not verified");
                }
                return fields;
            }
        }
    }

    /**
     * 按当前产品的名称规则判断两个对象名是否相同。
     */
    private static boolean sameName(String stored, String given) {
        return stored.toLowerCase(Locale.ROOT).equals(given.toLowerCase(Locale.ROOT));
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
