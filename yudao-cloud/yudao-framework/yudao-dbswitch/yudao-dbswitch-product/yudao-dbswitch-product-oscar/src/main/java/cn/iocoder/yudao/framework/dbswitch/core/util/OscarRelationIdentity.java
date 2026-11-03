package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单个永久堆表的原生 ShenTong V8.0.251217 标识。
 * 未加引号名称存为大写。带引号名称保留原拼写，不匹配大写形式。
 * 目录表位于 INFO_SCHEM。不要调用 Oracle、DM、PostgreSQL 或 Kingbase 的标识能力。
 * 仅限普通引用；存储名称按返回原样计算哈希。
 */
public final class OscarRelationIdentity {

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    private static final String SQL = "SELECT VERSION(), (SELECT USER FROM DUAL), d.DATNAME, "
            + "CAST(d.OID AS VARCHAR(40)), n.NSPNAME, CAST(n.OID AS VARCHAR(40)), c.RELNAME, "
            + "CAST(c.OID AS VARCHAR(40)), CAST(c.RELSID AS VARCHAR(40)), CAST(c.RELAM AS VARCHAR(40)), "
            + "c.RELKIND='r' AND c.RELFLAGS=1 AND c.RELAM=0 AND c.RELSID>0 "
            + "AND NOT c.RELHASRULES AND NOT c.RELHASSUBCLASS AND n.NSPNAME NOT LIKE 'SYS_TMP%' "
            + "AND (SELECT COUNT(*) FROM INFO_SCHEM.SYS_SEGMENT s WHERE s.OBJID=c.OID)=1 "
            + "AND EXISTS (SELECT 1 FROM INFO_SCHEM.SYS_SEGMENT s WHERE s.OBJID=c.OID "
            + "AND s.PCTFREE=10 AND s.LOGGING AND s.ENCRYPT=0 AND s.STYPE=0) "
            + "AND NOT EXISTS (SELECT 1 FROM INFO_SCHEM.SYS_EXTERNAL_TABLE e WHERE e.RELID=c.OID) "
            + "AND NOT EXISTS (SELECT 1 FROM INFO_SCHEM.SYS_PARTOBJ p WHERE p.OID=c.OID) "
            + "AND NOT EXISTS (SELECT 1 FROM INFO_SCHEM.SYS_TABPART tp WHERE tp.OID=c.OID) "
            + "AND NOT EXISTS (SELECT 1 FROM INFO_SCHEM.SYS_INHERITS i WHERE i.INHRELID=c.OID OR i.INHPARENT=c.OID) "
            + "AND NOT EXISTS (SELECT 1 FROM INFO_SCHEM.SYS_POLICY pol WHERE pol.POLOBJECT=c.OID) "
            + "FROM INFO_SCHEM.SYS_CLASS c "
            + "JOIN INFO_SCHEM.SYS_NAMESPACE n ON n.OID=c.RELNAMESPACE "
            + "JOIN INFO_SCHEM.SYS_DATABASE d ON d.OID=n.NSPDBID "
            + "WHERE c.RELNAME=? AND n.NSPNAME=COALESCE(?, SYS_CONTEXT('USERENV','CURRENT_SCHEMA'))";

    /**
     * 初始化 {@code OscarRelationIdentity}。
     */
    private OscarRelationIdentity() {
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Connection and relation required");
        }
        if (!reference.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")) {
            throw unsupported("Oscar quoted or complex references are not verified");
        }
        List<String> first = identity(connection, reference);
        if (!first.equals(identity(connection, reference))) {
            throw unsupported("Oscar native identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String reference) throws SQLException {
        String[] names = reference.split("\\.");
        String table = names[names.length - 1].toUpperCase(Locale.ROOT);
        String schema = names.length == 2 ? names[0].toUpperCase(Locale.ROOT) : null;
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, table);
            if (schema == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, schema);
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("Oscar relation is absent or not visible");
                }
                var fields = new ArrayList<>(List.of("OSCAR_V8_0_251217_HEAP_V1"));
                for (int index = 1; index <= 10; index++) {
                    String value = rows.getString(index);
                    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) {
                        throw unsupported("Oscar native identity field is unavailable");
                    }
                    fields.add(value);
                }
                boolean verified = rows.getBoolean(11);
                if (!OscarNativeCatalog.verifiedVersion(fields.get(1)) || !fields.get(4).matches("[1-9][0-9]{0,19}")
                        || !fields.get(6).matches("[1-9][0-9]{0,19}") || !fields.get(8).matches("[1-9][0-9]{0,19}")
                        || !fields.get(9).matches("[1-9][0-9]{0,19}") || !"0".equals(fields.get(10))
                        || !table.equals(fields.get(7)) || schema != null && !schema.equals(fields.get(5))
                        || !verified || rows.wasNull() || rows.next()) {
                    throw unsupported("Oscar version, resolved object or storage is not verified");
                }
                fields.set(1, OscarNativeCatalog.SERVER_BUILD);
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
