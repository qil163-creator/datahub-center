package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UXsinoDB 2.1.0.3 中单张永久堆表的原生身份。
 * 目录为 {@code ux_catalog}，不是 {@code pg_catalog}；{@code server_version=10.0} 和控制
 * 版本仅是构建凭据，不得使用 PostgreSQL、HighGo、Kingbase 或 Greenplum 的身份逻辑。
 * {@code current_user} 是 SQL 关键字，服务器拒绝 {@code ux_catalog.current_user}。
 * 堆表存储为 {@code relam=0}，{@code ux_am} 不含堆表记录，因此不应关联。
 */
public final class UxdbRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String VERSION_PREFIX = "UXsinoDB 2.1.0.3";

    private static final String SERVER_VERSION = "10.0";

    private static final String CONTROL_VERSION = "1002";

    private static final String CATALOG_VERSION = "201707211";

    private static final String SQL = "SELECT ux_catalog.version(), ux_catalog.current_setting('server_version'), "
            + "s.ux_control_version::ux_catalog.text, s.catalog_version_no::ux_catalog.text, "
            + "s.system_identifier::ux_catalog.text, ux_catalog.current_database(), d.oid::ux_catalog.text, current_user, "
            + "n.nspname, c.relname, c.oid::ux_catalog.text, c.relfilenode::ux_catalog.text, c.relam::ux_catalog.text, "
            + "c.relkind='r' AND c.relpersistence='p' AND c.relfilenode>0 AND NOT c.relispartition "
            + "AND NOT c.relhassubclass AND NOT c.relhasrules AND NOT c.relrowsecurity "
            + "AND NOT c.relforcerowsecurity AND c.relpartbound IS NULL AND c.relam=0 AND c.reloptions IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM ux_catalog.ux_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM ux_catalog.ux_policy pol WHERE pol.polrelid=c.oid) "
            + "AND NOT EXISTS (SELECT 1 FROM ux_catalog.ux_seclabel sec WHERE sec.objoid=c.oid "
            + "AND sec.classoid=CAST('ux_class' AS ux_catalog.regclass)) "
            + "FROM ux_catalog.ux_class c JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace "
            + "JOIN ux_catalog.ux_database d ON d.datname=ux_catalog.current_database() "
            + "CROSS JOIN ux_catalog.ux_control_system() s WHERE c.oid=CAST(? AS ux_catalog.regclass)";

    /**
     * 初始化 {@code UxdbRelationIdentity}。
     */
    private UxdbRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("UXDB quoted or complex references are not verified");
        }
        List<String> first = identity(connection, reference);
        if (!first.equals(identity(connection, reference))) {
            throw unsupported("UXDB native identity changed during capture");
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
                    throw unsupported("UXDB relation is absent or not visible");
                }
                var fields = new ArrayList<>(List.of("UXDB_2_1_0_3_HEAP_V1"));
                for (int index = 1; index <= 13; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                        throw unsupported("UXDB native identity field is unavailable");
                    }
                    fields.add(value);
                }
                String[] names = reference.toLowerCase(Locale.ROOT).split("\\.");
                boolean qualified = rows.getBoolean(14);
                if (!fields.get(1).startsWith(VERSION_PREFIX) || !SERVER_VERSION.equals(fields.get(2))
                        || !CONTROL_VERSION.equals(fields.get(3)) || !CATALOG_VERSION.equals(fields.get(4))
                        || !fields.get(5).matches("[1-9][0-9]{0,19}") || !fields.get(7).matches("[1-9][0-9]{0,9}")
                        || !fields.get(11).matches("[1-9][0-9]{0,9}") || !fields.get(12).matches("[1-9][0-9]{0,9}")
                        || !"0".equals(fields.get(13)) || !qualified || rows.wasNull()
                        || !names[names.length - 1].equals(fields.get(10))
                        || names.length == 2 && !names[0].equals(fields.get(9)) || rows.next()) {
                    throw unsupported("UXDB version, resolved object or storage is not verified");
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
